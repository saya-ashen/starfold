package dev.starfold.render;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One ordered disk queue; completions are applied only by the client thread. */
final class GroundSurfaceStore implements AutoCloseable {
    private final ExecutorService io;
    private final Queue<Runnable> completed = new ConcurrentLinkedQueue<>();
    private static final class Binding {
        Map<Long, GroundSurface> loaded = Map.of(); // IO thread only
    }
    private Binding binding;
    private long sequence;

    GroundSurfaceStore() {
        this(Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "starfold-surface-io");
            thread.setDaemon(true);
            return thread;
        }));
    }

    GroundSurfaceStore(ExecutorService io) { this.io = io; }

    void bind(Object identity) { binding = identity == null ? null : new Binding(); sequence++; }
    void changed() { sequence++; }
    void drain() { for (Runnable task; (task = completed.poll()) != null;) task.run(); }

    void load(Path path, BitSet changed, Consumer<Map<Long, GroundSurface>> loaded) {
        Binding expected = binding;
        var snapshot = (BitSet) changed.clone();
        io.execute(() -> {
            Map<Long, GroundSurface> images = Map.of();
            try {
                if (Files.exists(path)) images = GroundSurfaceDisk.load(path, snapshot);
            } catch (java.io.IOException | IllegalArgumentException failure) {
                warn("load", path, failure);
            }
            expected.loaded = images;
            var result = images;
            completed.add(() -> { if (binding == expected) loaded.accept(result); });
        });
    }

    void save(Path path, Map<Long, GroundSurface> images, BitSet changed,
              Map<Long, Long> revisions, Runnable saved) {
        Binding expected = binding;
        long version = sequence;
        var snapshot = Map.copyOf(images);
        var changedSnapshot = (BitSet) changed.clone();
        var revisionSnapshot = Map.copyOf(revisions);
        io.execute(() -> {
            try {
                var merged = new HashMap<>(snapshot);
                // A logout may enqueue this save before the load completion reaches the client.
                expected.loaded.forEach((key, image) -> {
                    if (changedSnapshot.get(key.intValue())
                            && revisionSnapshot.getOrDefault(key, -1L) < image.revision())
                        merged.put(key, image);
                });
                GroundSurfaceDisk.save(path, merged);
                completed.add(() -> {
                    if (binding == expected && sequence == version) saved.run();
                });
            } catch (java.io.IOException | IllegalArgumentException failure) {
                warn("save", path, failure);
            }
        });
    }

    private static void warn(String action, Path path, Exception failure) {
        org.slf4j.LoggerFactory.getLogger(GroundSurfaceStore.class)
                .warn("Cannot {} surface cache {}: {}", action, path, failure.toString());
    }

    @Override public void close() {
        io.shutdown();
        boolean interrupted = false;
        while (!io.isTerminated()) {
            try { io.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
