package dev.starfold.render;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GroundMeshResourcesTest {
    @Test
    void reloadWaitsForActiveReadersAndRejectsQueuedOldWork() throws Exception {
        GroundMeshResources.endReload();
        long old = GroundMeshResources.generation();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reloadStarted = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> GroundMeshResources.read(old, () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                assertEquals(old, GroundMeshResources.generation());
                writes.incrementAndGet();
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var reload = executor.submit(() -> {
                reloadStarted.countDown();
                GroundMeshResources.invalidate();
            });
            assertTrue(reloadStarted.await(5, TimeUnit.SECONDS));
            assertFalse(reload.isDone());
            release.countDown();
            reader.get(5, TimeUnit.SECONDS);
            reload.get(5, TimeUnit.SECONDS);
            GroundMeshResources.read(old, writes::incrementAndGet);
            assertEquals(1, writes.get());
            assertFalse(GroundMeshResources.current(old));
            GroundMeshResources.endReload();
            GroundMeshResources.read(old, writes::incrementAndGet);
            GroundMeshResources.read(GroundMeshResources.generation(), writes::incrementAndGet);
            assertEquals(2, writes.get());
        } finally {
            release.countDown();
            GroundMeshResources.endReload();
        }
    }
}
