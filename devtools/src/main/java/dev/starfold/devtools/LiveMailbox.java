package dev.starfold.devtools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Local, session-scoped file transport. The dispatcher must return promptly without blocking. */
final class LiveMailbox implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_RESPONSES = 128;
    private static final long RESPONSE_RETENTION_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final Object DISCOVERY_LOCK = new Object();

    private final Path root;
    private final Path requests;
    private final Path responses;
    private final String session = UUID.randomUUID().toString();
    private final Function<JsonObject, CompletableFuture<JsonObject>> dispatcher;
    private ScheduledExecutorService executor;
    private volatile boolean closed;
    private volatile CompletableFuture<JsonObject> active;

    LiveMailbox(
            Path gameDirectory, Function<JsonObject, CompletableFuture<JsonObject>> dispatcher) {
        root = gameDirectory.toAbsolutePath().normalize().resolve("starfold-devtools/live");
        requests = root.resolve("requests");
        responses = root.resolve("responses");
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("Mailbox is closed");
        }
        if (executor != null) {
            return;
        }
        directory(root.getParent());
        directory(root);
        directory(requests);
        directory(responses);
        var discovery = new JsonObject();
        discovery.addProperty("protocol", 1);
        discovery.addProperty("session", session);
        discovery.addProperty("pid", ProcessHandle.current().pid());
        discovery.addProperty("startedAt", System.currentTimeMillis());
        synchronized (DISCOVERY_LOCK) {
            writeAtomic(root.resolve("session.json"), discovery);
        }
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "starfold-devtools-live");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(this::poll, 0, 200, TimeUnit.MILLISECONDS);
    }

    private static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mailbox directory must not be a symlink: " + path);
        }
    }

    private boolean safeDirectories() {
        return Files.isDirectory(root.getParent(), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(requests, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(responses, LinkOption.NOFOLLOW_LINKS);
    }

    private void poll() {
        if (closed || !safeDirectories()) {
            return;
        }
        try {
            pruneResponses();
            // Stream directory entries; never queue a task or collect an unbounded request list.
            try (var entries = Files.newDirectoryStream(requests, "*.json")) {
                for (Path path : entries) {
                    if (closed) {
                        return;
                    }
                    String name = path.getFileName().toString();
                    String id = name.substring(0, name.length() - 5);
                    if (validId(id)) {
                        process(path, id);
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A disappearing file or transient filesystem error must not stop later polls.
        }
    }

    private void process(Path path, String id) throws IOException {
        var response = new JsonObject();
        response.addProperty("id", id);
        response.addProperty("session", session);
        CompletableFuture<JsonObject> future = null;
        try {
            JsonObject request = readObject(path);
            if (!session.equals(string(request, "session"))) {
                throw new IllegalArgumentException("Stale or invalid session");
            }
            if (!id.equals(string(request, "id"))) {
                throw new IllegalArgumentException("Request id must match filename");
            }
            String operation = string(request, "operation");
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("Operation must be a nonempty string");
            }
            JsonElement expiry = request.get("expiresAt");
            if (expiry == null
                    || !expiry.isJsonPrimitive()
                    || !expiry.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("expiresAt must be epoch milliseconds");
            }
            long expiresAt = expiry.getAsBigDecimal().longValueExact();
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Mailbox closed");
                }
                if (expiresAt <= System.currentTimeMillis()) {
                    throw new IllegalArgumentException("Request expired");
                }
                future = Objects.requireNonNull(dispatcher.apply(request));
                active = future;
            }
            long remaining = Math.min(10_000, expiresAt - System.currentTimeMillis());
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            JsonObject data = future.get(remaining, TimeUnit.MILLISECONDS);
            response.addProperty("ok", true);
            response.add("data", data == null ? new JsonObject() : data);
        } catch (TimeoutException exception) {
            response.addProperty("ok", false);
            response.addProperty("error", "Dispatch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.addProperty("ok", false);
            response.addProperty("error", "Mailbox closed");
        } catch (IOException | RuntimeException | ExecutionException exception) {
            response.addProperty("ok", false);
            Throwable cause =
                    exception instanceof ExecutionException ? exception.getCause() : exception;
            response.addProperty(
                    "error",
                    cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage());
        } finally {
            active = null;
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
            try {
                if (safeDirectories()) {
                    writeAtomic(responses.resolve(id + ".json"), response);
                    pruneResponses();
                }
            } finally {
                if (safeDirectories()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static boolean validId(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular JSON file");
        }
        byte[] bytes;
        try (var channel =
                        Files.newByteChannel(
                                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
        }
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IOException("JSON exceeds 16 KiB");
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement element = GSON.getAdapter(JsonElement.class).read(reader);
            if (element == null
                    || !element.isJsonObject()
                    || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Expected one JSON object");
            }
            return element.getAsJsonObject();
        }
    }

    private static void writeAtomic(Path destination, JsonObject object) throws IOException {
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Refusing symlink: " + destination);
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".live-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(object), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record Retained(Path path, long modified) {}

    private void pruneResponses() throws IOException {
        long cutoff = System.currentTimeMillis() - RESPONSE_RETENTION_MILLIS;
        var retained = new PriorityQueue<Retained>(Comparator.comparingLong(Retained::modified));
        try (var entries = Files.newDirectoryStream(responses, "*.json")) {
            for (Path path : entries) {
                if (!validId(path.getFileName().toString().replaceFirst("\\.json$", ""))) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long modified =
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (modified < cutoff) {
                    Files.deleteIfExists(path);
                } else {
                    retained.add(new Retained(path, modified));
                    if (retained.size() > MAX_RESPONSES) {
                        Files.deleteIfExists(retained.remove().path());
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService stopping;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopping = executor;
            if (active != null) {
                active.cancel(false);
            }
            if (stopping != null) {
                stopping.shutdownNow();
            }
        }
        if (stopping != null) {
            try {
                stopping.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (DISCOVERY_LOCK) {
            try {
                Path discovery = root.resolve("session.json");
                if (safeDirectories() && session.equals(string(readObject(discovery), "session"))) {
                    Files.deleteIfExists(discovery);
                }
            } catch (IOException | RuntimeException ignored) {
                // Never remove discovery belonging to another session or a substituted symlink.
            }
        }
    }
}
