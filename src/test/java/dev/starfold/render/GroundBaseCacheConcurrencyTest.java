package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Offline concurrency tests: no renderer, disk workers or game startup. */
class GroundBaseCacheConcurrencyTest {
    @Test
    void fineSingleFlightDoesNotBlockHitsOrOtherKeys() throws Exception {
        var cache = new GroundBaseCache.FineCache(16 * 1024);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var retained = cache.get(2, () -> new long[] {2});
        var packed = new long[] {1, 3};
        var owner =
                start(
                        () ->
                                cache.get(
                                        1,
                                        () -> {
                                            calls.incrementAndGet();
                                            entered.countDown();
                                            await(release);
                                            return packed;
                                        }));
        var followers = new ArrayList<Job<long[]>>();
        try {
            await(entered);
            for (int i = 0; i < 8; i++) {
                var follower = start(() -> cache.get(1, () -> fail("duplicate fine build")));
                followers.add(follower);
                waiting(follower);
            }
            assertSame(retained, result(start(() -> cache.get(2, () -> fail("retained hit")))));
            assertArrayEquals(
                    new long[] {4}, result(start(() -> cache.get(3, () -> new long[] {4}))));
            release.countDown();
            assertSame(packed, result(owner));
            for (var follower : followers) assertSame(packed, result(follower));
            assertEquals(1, calls.get());
            assertEquals(96 + 88 + 88, cache.bytes());
        } finally {
            release.countDown();
            cache.close();
            finish(owner);
            for (var follower : followers) finish(follower);
        }
    }

    @Test
    void fineFailuresReleaseWaitersAndRetryWithoutRetainedBytes() throws Exception {
        var cache = new GroundBaseCache.FineCache(16 * 1024);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var injected = new IllegalStateException("injected failure");
        var owner =
                start(
                        () ->
                                cache.get(
                                        7,
                                        () -> {
                                            entered.countDown();
                                            await(release);
                                            throw injected;
                                        }));
        Job<long[]> follower = null;
        try {
            await(entered);
            follower = start(() -> cache.get(7, () -> fail("duplicate build")));
            waiting(follower);
            release.countDown();
            assertSame(injected, failure(owner));
            assertSame(injected, failure(follower));
            assertEquals(0, cache.bytes());
            var retried = cache.get(7, () -> new long[] {42});
            assertSame(retried, cache.get(7, () -> fail("retained retry")));
            assertEquals(88, cache.bytes());
        } finally {
            release.countDown();
            cache.close();
            finish(owner);
            if (follower != null) finish(follower);
        }
    }

    @Test
    void shardQuotasBoundParallelInsertionsAndOversizedValuesAreNotRetained() throws Exception {
        long limit = 16 * 256;
        var cache = new GroundBaseCache.FineCache(limit);
        var jobs = new ArrayList<Job<Void>>();
        var begin = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker * 1000;
                jobs.add(
                        start(
                                () -> {
                                    await(begin);
                                    for (int i = 0; i < 1000; i++) {
                                        cache.get(offset + i, () -> new long[] {1, 2});
                                        assertTrue(cache.bytes() <= limit);
                                    }
                                    return null;
                                }));
            }
            begin.countDown();
            for (var job : jobs) result(job);
            assertTrue(cache.bytes() > 0 && cache.bytes() <= limit);
            var oversized = new long[64];
            assertSame(oversized, cache.get(-1, () -> oversized));
            var replacement = new long[64];
            assertSame(replacement, cache.get(-1, () -> replacement));
            cache.close();
            assertEquals(0, cache.bytes());
        } finally {
            begin.countDown();
            cache.close();
            for (var job : jobs) finish(job);
        }
    }

    @Test
    void closeReleasesWaitersBeforeSlowFineBuilderAndPreventsLateInsertion() throws Exception {
        var cache = new GroundBaseCache.FineCache(16 * 1024);
        cache.get(2, () -> new long[] {2});
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner =
                start(
                        () ->
                                cache.get(
                                        1,
                                        () -> {
                                            entered.countDown();
                                            await(release);
                                            return new long[] {1};
                                        }));
        Job<long[]> follower = null;
        try {
            await(entered);
            follower = start(() -> cache.get(1, () -> fail("duplicate build")));
            waiting(follower);
            cache.close();
            assertInstanceOf(CancellationException.class, failure(follower));
            assertFalse(owner.task.isDone(), "close does not wait for slow work");
            assertEquals(0, cache.bytes());
            assertThrows(CancellationException.class, () -> cache.get(2, () -> fail("closed")));
            release.countDown();
            assertInstanceOf(CancellationException.class, failure(owner));
            assertEquals(0, cache.bytes());
        } finally {
            release.countDown();
            cache.close();
            finish(owner);
            if (follower != null) finish(follower);
        }
    }

    @Test
    void planetConstructionSharesWorkWhileOtherPlanetsAndPublishedHitsProceed() throws Exception {
        PlanetTerrainTest.registry();
        GroundBaseCache.clear();
        var terrain = new PlanetTerrain(42);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var owner =
                start(
                        () ->
                                GroundBaseCache.get(
                                        terrain,
                                        null,
                                        t -> {
                                            calls.incrementAndGet();
                                            entered.countDown();
                                            await(release);
                                            return new GroundBaseCache.Entry(t);
                                        },
                                        false));
        Job<GroundBaseCache.Entry> follower = null;
        try {
            await(entered);
            follower =
                    start(
                            () ->
                                    GroundBaseCache.get(
                                            terrain,
                                            null,
                                            t -> fail("duplicate planet constructor"),
                                            false));
            waiting(follower);
            var other =
                    result(
                            start(
                                    () ->
                                            GroundBaseCache.get(
                                                    new PlanetTerrain(43),
                                                    null,
                                                    GroundBaseCache.Entry::new,
                                                    false)));
            release.countDown();
            var entry = result(owner);
            assertSame(entry, result(follower));
            assertNotSame(other, entry);
            assertEquals(1, calls.get());
            // Simulate a long scheduler scan. Published reads must not acquire this monitor.
            synchronized (GroundBaseCache.class) {
                assertSame(entry, result(start(() -> GroundBaseCache.get(terrain, null))));
                assertTrue(result(start(GroundBaseCache::retainedBytes)) >= 0);
            }
        } finally {
            release.countDown();
            finish(owner);
            if (follower != null) finish(follower);
            GroundBaseCache.clear();
        }
    }

    @Test
    void disconnectCancelsPlanetFlightAndOldConstructionCannotReplaceNewSession() throws Exception {
        PlanetTerrainTest.registry();
        GroundBaseCache.clear();
        var terrain = new PlanetTerrain(42);
        var old = new GroundBaseCache.Entry(terrain);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner =
                start(
                        () ->
                                GroundBaseCache.get(
                                        terrain,
                                        null,
                                        t -> {
                                            entered.countDown();
                                            await(release);
                                            return old;
                                        },
                                        false));
        Job<GroundBaseCache.Entry> follower = null;
        try {
            await(entered);
            follower =
                    start(() -> GroundBaseCache.get(terrain, null, t -> fail("duplicate"), false));
            waiting(follower);
            GroundBaseCache.clear();
            assertInstanceOf(CancellationException.class, failure(follower));
            var replacement = GroundBaseCache.get(terrain, null, GroundBaseCache.Entry::new, false);
            release.countDown();
            assertInstanceOf(CancellationException.class, failure(owner));
            assertEquals(GroundBaseCache.Entry.State.CLOSED, old.state);
            assertNull(old.cache);
            assertSame(replacement, GroundBaseCache.get(terrain, null));
            replacement.fine.get(1, () -> new long[] {1});
            GroundBaseCache.clear();
            assertEquals(0, replacement.fineBytes());
            assertEquals(0, GroundBaseCache.retainedBytes());
        } finally {
            release.countDown();
            finish(owner);
            if (follower != null) finish(follower);
            GroundBaseCache.clear();
        }
    }

    @Test
    void failedPlanetConstructionCanRetry() {
        PlanetTerrainTest.registry();
        GroundBaseCache.clear();
        try {
            var terrain = new PlanetTerrain(42);
            var failed =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    GroundBaseCache.get(
                                            terrain,
                                            null,
                                            t -> {
                                                throw new IllegalStateException(
                                                        "constructor failure");
                                            },
                                            false));
            assertEquals("constructor failure", failed.getCause().getMessage());
            var retried = GroundBaseCache.get(terrain, null, GroundBaseCache.Entry::new, false);
            assertSame(retried, GroundBaseCache.get(terrain, null));
        } finally {
            GroundBaseCache.clear();
        }
    }

    private record Job<T>(Thread thread, FutureTask<T> task) {}

    private static <T> Job<T> start(Supplier<T> action) {
        var task = new FutureTask<T>(action::get);
        var thread = new Thread(task, "base-cache-offline-test");
        thread.setDaemon(true);
        thread.start();
        return new Job<>(thread, task);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static <T> T result(Job<T> job) throws Exception {
        return job.task.get(3, TimeUnit.SECONDS);
    }

    private static Throwable failure(Job<?> job) throws Exception {
        var failed = assertThrows(ExecutionException.class, () -> result(job));
        var cause = failed.getCause();
        return cause instanceof CompletionException ? cause.getCause() : cause;
    }

    private static void waiting(Job<?> job) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (job.thread.getState() != Thread.State.WAITING
                && !job.task.isDone()
                && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(
                Thread.State.WAITING,
                job.thread.getState(),
                "expected waiting singleflight follower");
    }

    private static void finish(Job<?> job) throws InterruptedException {
        job.thread.join(3000);
        assertFalse(job.thread.isAlive(), "test worker failed to finish");
    }
}
