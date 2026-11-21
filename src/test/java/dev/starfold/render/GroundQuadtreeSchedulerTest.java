package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class GroundQuadtreeSchedulerTest {
    @Test
    void directionAndSiblingCompletionRemainPrioritiesAtWideFov() {
        assertTrue(
                GroundQuadtreeScheduler.priority(10, 1, 0, false)
                        > GroundQuadtreeScheduler.priority(10, 0, 0, false));
        assertTrue(
                GroundQuadtreeScheduler.priority(10, 0, 3, false)
                        > GroundQuadtreeScheduler.priority(10, 0, 0, false));
        assertTrue(
                GroundQuadtreeScheduler.priority(.01, 0, 0, true)
                        > GroundQuadtreeScheduler.priority(100, 1, 3, false));
    }

    @Test
    void resetReleasesLatePayloadWithoutPublishingItToNewSession() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            var ticket = new GroundQuadtreeScheduler.Ticket(0, 64, 0, 1);
            scheduler.schedule(
                    List.of(
                            new GroundQuadtreeScheduler.Request<>(
                                    ticket,
                                    1,
                                    () -> true,
                                    () -> {
                                        started.countDown();
                                        assertTrue(finish.await(2, TimeUnit.SECONDS));
                                        return released::countDown;
                                    })));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            scheduler.reset();
            finish.countDown();
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertNull(scheduler.poll());
            assertEquals(0, scheduler.jobs());
        }
    }

    @Test
    void queuedTicketIsRecheckedBeforeBuildingAndCancellationIsNotFailure() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var builds = new AtomicInteger();
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            scheduler.schedule(List.of(
                    new GroundQuadtreeScheduler.Request<>(
                            new GroundQuadtreeScheduler.Ticket(1, 16, 0, 1), 2, () -> true, () -> {
                                started.countDown();
                                assertTrue(finish.await(2, TimeUnit.SECONDS));
                                throw new java.util.concurrent.CancellationException();
                            }),
                    new GroundQuadtreeScheduler.Request<>(
                            new GroundQuadtreeScheduler.Ticket(2, 16, 0, 1), 1, valid::get, () -> {
                                builds.incrementAndGet();
                                return () -> {};
                            })));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            valid.set(false);
            finish.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scheduler.completed() < 2 && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(2, scheduler.completed());
            for (int i = 0; i < 2; i++) {
                var result = scheduler.poll();
                assertNotNull(result);
                assertNull(result.failure());
                assertNull(result.mesh());
            }
            assertEquals(0, builds.get());
            assertEquals(0, scheduler.jobs());
        } finally {
            finish.countDown();
        }
    }

    @Test
    void finishingSharesWorkerAdmissionAndResetCancelsQueuedAndRunningContinuations() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queuedRuns = new AtomicInteger();
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            assertTrue(scheduler.finish(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(scheduler.finish(queuedRuns::incrementAndGet));
            assertEquals(2, scheduler.jobs());
            assertFalse(scheduler.finish(() -> fail("must not exceed existing capacity")));
            scheduler.schedule(List.of(new GroundQuadtreeScheduler.Request<>(
                    new GroundQuadtreeScheduler.Ticket(1, 16, 0, 1), 1, () -> true,
                    () -> { fail("mesh builds share continuation capacity"); return () -> {}; })));
            assertFalse(scheduler.pending(1));
            scheduler.reset();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(0, scheduler.jobs());
            var fresh = new CountDownLatch(1);
            assertTrue(scheduler.finish(fresh::countDown));
            assertTrue(fresh.await(2, TimeUnit.SECONDS));
            assertEquals(0, queuedRuns.get(), "reset must discard queued old-session continuations");
        } finally {
            release.countDown();
        }
    }

    @Test
    void completedBuildMustBePolledBeforeItsSlotAdmitsFinishing() throws Exception {
        var built = new CountDownLatch(2);
        var finished = new CountDownLatch(1);
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            scheduler.schedule(List.of(1, 2).stream().map(node ->
                    new GroundQuadtreeScheduler.Request<AutoCloseable>(
                            new GroundQuadtreeScheduler.Ticket(node, 16, 0, 1), 1, () -> true,
                            () -> { built.countDown(); return () -> {}; })).toList());
            assertTrue(built.await(2, TimeUnit.SECONDS));
            assertFalse(scheduler.finish(finished::countDown));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scheduler.completed() < 2 && System.nanoTime() < until) Thread.sleep(1);
            assertNotNull(scheduler.poll());
            assertTrue(scheduler.finish(finished::countDown));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void packedHitCompletesWhileBuildersAreFullAndMissWaitsForBoundedBuildAdmission() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var lookups = new CountDownLatch(2);
        var hitClosed = new AtomicInteger();
        var rebuilt = new AtomicInteger();
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            scheduler.schedule(List.of(1, 2).stream().map(node ->
                    new GroundQuadtreeScheduler.Request<AutoCloseable>(
                            new GroundQuadtreeScheduler.Ticket(node, 16, 0, 1), 1, () -> true,
                            () -> { started.countDown(); release.await(); return () -> {}; })).toList());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            scheduler.schedule(List.of(
                    new GroundQuadtreeScheduler.Request<AutoCloseable>(
                            new GroundQuadtreeScheduler.Ticket(3, 16, 0, 1), 2, () -> true,
                            () -> { rebuilt.incrementAndGet(); return () -> {}; },
                            () -> { lookups.countDown(); return null; }),
                    new GroundQuadtreeScheduler.Request<AutoCloseable>(
                            new GroundQuadtreeScheduler.Ticket(4, 16, 0, 1), 1, () -> true,
                            () -> { fail("hit must skip meshing"); return null; },
                            () -> { lookups.countDown(); return hitClosed::incrementAndGet; })));
            assertTrue(lookups.await(2, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            GroundQuadtreeScheduler.Completed<AutoCloseable> hit = null;
            while (hit == null && System.nanoTime() < until) {
                hit = scheduler.poll();
                if (hit == null) Thread.sleep(1);
            }
            assertNotNull(hit, "disk hit cannot wait behind blocked geometry builders");
            assertEquals(4, hit.ticket().node());
            assertNull(hit.failure());
            hit.mesh().close();
            assertEquals(1, hitClosed.get());
            assertEquals(0, rebuilt.get(), "miss cannot bypass the build admission bound");
            assertEquals(3, scheduler.jobs());
            release.countDown();
            int completions = 0;
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (completions < 3 && System.nanoTime() < until) {
                var result = scheduler.poll();
                if (result == null) { Thread.sleep(1); continue; }
                assertNull(result.failure());
                assertNotNull(result.mesh());
                result.mesh().close();
                completions++;
            }
            assertEquals(3, completions);
            assertEquals(1, rebuilt.get());
            assertEquals(0, scheduler.jobs());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cacheWindowSpansFramePollingButRemainsBoundedWithoutAddingReaders() throws Exception {
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(2);
        var closed = new AtomicInteger();
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(6)) {
            var requests = java.util.stream.IntStream.range(0, 64).mapToObj(node ->
                    new GroundQuadtreeScheduler.Request<AutoCloseable>(
                            new GroundQuadtreeScheduler.Ticket(node, 16, 0, 1), 1, () -> true,
                            () -> { fail("cache hits must not rebuild"); return null; },
                            () -> { running.countDown(); release.await(); return closed::incrementAndGet; })).toList();
            scheduler.schedule(requests);
            assertTrue(running.await(2, TimeUnit.SECONDS));
            int admitted = scheduler.jobs();
            assertTrue(admitted >= 16, "a few completed hits must not starve the next frame");
            assertTrue(admitted <= 24, "lookup payloads stay bounded");
            assertEquals(2, scheduler.readersActive());
            assertEquals(0, scheduler.lookupSlots());
            scheduler.schedule(requests);
            assertEquals(admitted, scheduler.jobs());
            assertTrue(scheduler.nodeCapacity() >= admitted + 12, "all build and read tickets can be tracked");
            release.countDown();
            int received = 0;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (received < admitted && System.nanoTime() < until) {
                var result = scheduler.poll();
                if (result == null) { Thread.sleep(1); continue; }
                assertNull(result.failure());
                result.mesh().close();
                received++;
            }
            assertEquals(admitted, received);
            assertEquals(admitted, closed.get());
            assertEquals(admitted, scheduler.lookupSlots());
        } finally {
            release.countDown();
        }
    }

    @Test
    void resetReleasesLateCacheHitAndDropsOldMissWithoutRebuilding() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var builds = new AtomicInteger();
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            scheduler.schedule(List.of(new GroundQuadtreeScheduler.Request<>(
                    new GroundQuadtreeScheduler.Ticket(1, 16, 0, 1), 1, () -> true,
                    () -> { builds.incrementAndGet(); return () -> {}; },
                    () -> { started.countDown(); release.await(); return closed::countDown; })));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            scheduler.reset();
            release.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertNull(scheduler.poll());
            assertEquals(0, builds.get());
            assertEquals(0, scheduler.jobs());
        } finally {
            release.countDown();
        }
    }

    @Test
    void invisibleDemandNeverStarts() {
        var builds = new AtomicInteger();
        try (var scheduler = new GroundQuadtreeScheduler<AutoCloseable>(1)) {
            scheduler.schedule(
                    List.of(
                            new GroundQuadtreeScheduler.Request<>(
                                    new GroundQuadtreeScheduler.Ticket(1, 16, 0, 0),
                                    1,
                                    () -> false,
                                    () -> {
                                        builds.incrementAndGet();
                                        return () -> {};
                                    })));
            assertEquals(0, builds.get());
            assertEquals(0, scheduler.jobs());
        }
    }
}
