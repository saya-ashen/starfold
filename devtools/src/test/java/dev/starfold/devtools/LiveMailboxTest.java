package dev.starfold.devtools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Timeout(20)
class LiveMailboxTest {
    @TempDir Path gameDirectory;

    private Path root() {
        return gameDirectory.resolve("starfold-devtools/live");
    }

    private JsonObject discovery() throws Exception {
        return JsonParser.parseString(Files.readString(root().resolve("session.json")))
                .getAsJsonObject();
    }

    private JsonObject request(String session) {
        var request = new JsonObject();
        request.addProperty("id", UUID.randomUUID().toString());
        request.addProperty("session", session);
        request.addProperty("operation", "status");
        request.addProperty("expiresAt", System.currentTimeMillis() + 15_000);
        return request;
    }

    private String session() throws Exception {
        return discovery().get("session").getAsString();
    }

    private void publish(JsonObject request) throws Exception {
        publish(request.get("id").getAsString(), request.toString());
    }

    private void publish(String id, String content) throws Exception {
        Path temporary = Files.createTempFile(root().resolve("requests"), ".test-", ".tmp");
        Files.writeString(temporary, content);
        Files.move(
                temporary,
                root().resolve("requests").resolve(id + ".json"),
                StandardCopyOption.ATOMIC_MOVE);
    }

    private JsonObject response(String id) throws Exception {
        Path path = root().resolve("responses").resolve(id + ".json");
        await(() -> Files.exists(path), 13_000);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private JsonObject response(JsonObject request) throws Exception {
        return response(request.get("id").getAsString());
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for mailbox");
    }

    @Test
    void publishesDiscoveryAndRoundTripsRequest() throws Exception {
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> {
                            var data = new JsonObject();
                            data.addProperty("operation", request.get("operation").getAsString());
                            data.add("unavailable", com.google.gson.JsonNull.INSTANCE);
                            return CompletableFuture.completedFuture(data);
                        })) {
            mailbox.start();
            mailbox.start();
            JsonObject discovery = discovery();
            assertEquals(1, discovery.get("protocol").getAsInt());
            assertEquals(ProcessHandle.current().pid(), discovery.get("pid").getAsLong());
            assertTrue(discovery.get("startedAt").getAsLong() <= System.currentTimeMillis());
            UUID.fromString(session());
            JsonObject request = request(session());
            publish(request);
            JsonObject response = response(request);
            assertEquals(request.get("id"), response.get("id"));
            assertEquals(request.get("session"), response.get("session"));
            assertTrue(response.get("ok").getAsBoolean());
            assertTrue(response.getAsJsonObject("data").get("unavailable").isJsonNull());
            assertEquals("status", response.getAsJsonObject("data").get("operation").getAsString());
            await(
                    () ->
                            !Files.exists(
                                    root().resolve("requests")
                                            .resolve(request.get("id").getAsString() + ".json")),
                    1000);
        }
        assertFalse(Files.exists(root().resolve("session.json")));
    }

    @Test
    void rejectsMalformedStaleExpiredAndInvalidEnvelopesWithoutDispatch() throws Exception {
        var calls = new AtomicInteger();
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> {
                            calls.incrementAndGet();
                            return CompletableFuture.completedFuture(new JsonObject());
                        })) {
            mailbox.start();
            for (String malformed :
                    new String[] {
                        "{", "[]", "{operation:'status'}", "{} trailing", "x".repeat(16 * 1024 + 1)
                    }) {
                String id = UUID.randomUUID().toString();
                publish(id, malformed);
                assertFalse(response(id).get("ok").getAsBoolean());
            }
            for (String invalid : new String[] {"session", "id", "operation", "expiresAt"}) {
                JsonObject request = request(session());
                String id = request.get("id").getAsString();
                switch (invalid) {
                    case "session" -> request.addProperty("session", UUID.randomUUID().toString());
                    case "id" -> request.addProperty("id", "../../outside");
                    case "operation" -> request.addProperty("operation", 3);
                    case "expiresAt" ->
                            request.addProperty("expiresAt", System.currentTimeMillis() - 1);
                    default -> fail();
                }
                publish(id, request.toString());
                assertFalse(response(id).get("ok").getAsBoolean());
            }
            JsonObject fractionalExpiry = request(session());
            fractionalExpiry.addProperty("expiresAt", 123.5);
            publish(fractionalExpiry);
            assertFalse(response(fractionalExpiry).get("ok").getAsBoolean());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void serialDispatchChecksExpiryAfterWaitingInDirectory() throws Exception {
        var first = new CompletableFuture<JsonObject>();
        var entered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            return first;
                        })) {
            mailbox.start();
            JsonObject one = request(session());
            publish(one);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            JsonObject two = request(session());
            two.addProperty("expiresAt", System.currentTimeMillis() + 100);
            publish(two);
            Thread.sleep(150);
            assertEquals(1, calls.get());
            first.complete(new JsonObject());
            assertTrue(response(one).get("ok").getAsBoolean());
            JsonObject stale = response(two);
            assertFalse(stale.get("ok").getAsBoolean());
            assertTrue(stale.get("error").getAsString().contains("expired"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void dispatchTimesOutAtTenSecondsAndCancelsFuture() throws Exception {
        var future = new CompletableFuture<JsonObject>();
        try (var mailbox = new LiveMailbox(gameDirectory, request -> future)) {
            mailbox.start();
            JsonObject request = request(session());
            publish(request);
            JsonObject response = response(request);
            assertFalse(response.get("ok").getAsBoolean());
            assertEquals("Dispatch timed out", response.get("error").getAsString());
            assertTrue(future.isCancelled());
        }
    }

    @Test
    void handlerFailuresProduceErrorsAndNextRequestStillRuns() throws Exception {
        var calls = new AtomicInteger();
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> {
                            if (calls.incrementAndGet() == 1) {
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("fixture failure"));
                            }
                            return CompletableFuture.completedFuture(new JsonObject());
                        })) {
            mailbox.start();
            JsonObject one = request(session());
            publish(one);
            JsonObject failed = response(one);
            assertFalse(failed.get("ok").getAsBoolean());
            assertEquals("fixture failure", failed.get("error").getAsString());
            JsonObject two = request(session());
            publish(two);
            assertTrue(response(two).get("ok").getAsBoolean());
        }
    }

    @Test
    void shutdownCancelsPendingDispatchAndPreservesNewSessionDiscovery() throws Exception {
        var future = new CompletableFuture<JsonObject>();
        var entered = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> {
                            worker.set(Thread.currentThread());
                            entered.countDown();
                            return future;
                        });
        try {
            mailbox.start();
            JsonObject request = request(session());
            publish(request);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var replacement = discovery();
            String replacementSession = UUID.randomUUID().toString();
            replacement.addProperty("session", replacementSession);
            Files.writeString(root().resolve("session.json"), replacement.toString());
            long before = System.nanoTime();
            mailbox.close();
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
            assertTrue(future.isCancelled());
            assertTrue(worker.get().isDaemon());
            await(() -> !worker.get().isAlive(), 1000);
            assertEquals(replacementSession, session());
            assertThrows(IllegalStateException.class, mailbox::start);
        } finally {
            mailbox.close();
        }
    }

    @Test
    void refusesSymlinkRequestsAndInvalidFilenameIds() throws Exception {
        var calls = new AtomicInteger();
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> {
                            calls.incrementAndGet();
                            return CompletableFuture.completedFuture(new JsonObject());
                        })) {
            mailbox.start();
            String id = UUID.randomUUID().toString();
            JsonObject request = request(session());
            request.addProperty("id", id);
            Path outside = gameDirectory.resolve("outside.json");
            Files.writeString(outside, request.toString());
            Path link = root().resolve("requests").resolve(id + ".json");
            Files.createSymbolicLink(link, outside);
            publish("not-a-uuid", request.toString());
            assertFalse(response(id).get("ok").getAsBoolean());
            assertEquals(request.toString(), Files.readString(outside));
            assertEquals(0, calls.get());
            assertTrue(Files.exists(root().resolve("requests/not-a-uuid.json")));
        }
    }

    @Test
    void refusesSymlinkMailboxDirectory() throws Exception {
        Path outside = gameDirectory.resolve("outside");
        Files.createDirectory(outside);
        Files.createSymbolicLink(gameDirectory.resolve("starfold-devtools"), outside);
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> CompletableFuture.completedFuture(new JsonObject()))) {
            assertThrows(java.io.IOException.class, mailbox::start);
        }
        assertFalse(Files.exists(outside.resolve("live")));
    }

    @Test
    void boundsResponseCountAndRemovesOldResponses() throws Exception {
        try (var mailbox =
                new LiveMailbox(
                        gameDirectory,
                        request -> CompletableFuture.completedFuture(new JsonObject()))) {
            mailbox.start();
            for (int index = 0; index < 140; index++) {
                Files.writeString(
                        root().resolve("responses").resolve(UUID.randomUUID() + ".json"), "{}");
            }
            Path old = root().resolve("responses").resolve(UUID.randomUUID() + ".json");
            Files.writeString(old, "{}");
            Files.setLastModifiedTime(old, FileTime.fromMillis(1));
            JsonObject request = request(session());
            publish(request);
            assertTrue(response(request).get("ok").getAsBoolean());
            await(
                    () -> {
                        try (var files = Files.list(root().resolve("responses"))) {
                            return files.count() <= 128;
                        } catch (Exception exception) {
                            return false;
                        }
                    },
                    2000);
            assertFalse(Files.exists(old));
        }
    }
}
