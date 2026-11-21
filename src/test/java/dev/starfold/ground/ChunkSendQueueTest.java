package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.planet.PlanetAccess;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import java.util.stream.LongStream;

class ChunkSendQueueTest {
    @Test
    void flightWarmsOnlyTheLeadingImageRows() {
        var center = new ChunkPos(1324, 76);
        var current = ImageStream.positions(center, 12);
        var approach = ImageStream.approachImages(center, 12, 0, 2.4);
        assertEquals(14, approach.size());
        assertTrue(Collections.disjoint(current, approach));
        assertTrue(approach.contains(ChunkPos.asLong(1327, 90)));
        assertTrue(ImageStream.approachImages(center, 12, -2.4, 0).stream()
                .allMatch(image -> ChunkPos.getX(image) < center.x));
        assertTrue(ImageStream.approachImages(center, 12, 0, 0.2).isEmpty());
        assertTrue(ImageStream.approachImages(center, 12, 0, 200).isEmpty());
        assertTrue(ImageStream.approachImages(center, ImageStream.ALOFT, 0, 2.4).isEmpty());
    }

    private static ChunkSendQueue queue(long... images) {
        var queue = new ChunkSendQueue();
        queue.retain(LongStream.of(images).boxed().toList(), Set.of());
        return queue;
    }

    @Test
    void twentyFifthReadyImageSendsOnSecondTickDespiteTwentyFourMissingOwners() {
        var queue = new ChunkSendQueue();
        queue.retain(LongStream.rangeClosed(1, 25).boxed().toList(), Set.of());
        var attempts = new ArrayList<Long>();
        var sent = new ArrayList<Long>();
        LongPredicate send =
                image -> {
                    attempts.add(image);
                    if (image != 25) return false;
                    sent.add(image);
                    return true;
                };
        queue.drain(() -> 0, 1, 24, send);
        assertEquals(LongStream.rangeClosed(1, 24).boxed().toList(), attempts);
        assertTrue(sent.isEmpty());
        attempts.clear();
        queue.drain(() -> 0, 1, 24, send);
        assertEquals(25L, attempts.getFirst());
        assertEquals(List.of(25L), sent);
        assertEquals(24, queue.size());
    }

    @Test
    void allFailuresRespectDeadlineAndDoNotReceiveSuccessBasedExemptions() {
        var queue = queue(1, 2, 3, 4);
        var time = new AtomicLong();
        var attempts = new ArrayList<Long>();
        queue.drain(
                time::get,
                10,
                24,
                image -> {
                    attempts.add(image);
                    time.addAndGet(7);
                    return false;
                });
        assertEquals(List.of(1L, 2L), attempts);
        assertEquals(4, queue.size());
        attempts.clear();
        queue.drain(
                time::get,
                10,
                24,
                image -> {
                    attempts.add(image);
                    return false;
                });
        assertEquals(List.of(3L), attempts, "Only the first attempt may pass an expired deadline");
    }

    @Test
    void eachImageIsAttemptedOncePerDrainAndRecoversWithoutPrematureSentMarking() {
        var queue = queue(1, 2, 3);
        var attempts = new ArrayList<Long>();
        queue.drain(
                () -> 0,
                1,
                100,
                image -> {
                    attempts.add(image);
                    return false;
                });
        assertEquals(List.of(1L, 2L, 3L), attempts);
        assertEquals(3, queue.size());
        attempts.clear();
        queue.drain(
                () -> 0,
                1,
                100,
                image -> {
                    attempts.add(image);
                    return true;
                });
        assertEquals(List.of(1L, 2L, 3L), attempts);
        assertTrue(queue.isEmpty());
        queue.drain(() -> 0, 1, 100, image -> fail("Already sent"));
    }

    @Test
    void movingWindowStablyInterleavesNewImagesWithRetainedRetries() {
        var queue = queue(1, 2, 3, 4);
        queue.drain(() -> 0, 1, 2, image -> false); // 3,4,1,2
        queue.retain(List.of(6L, 2L, 3L, 1L, 5L, 6L), Set.of(1L));
        var attempts = new ArrayList<Long>();
        queue.drain(
                () -> 0,
                1,
                100,
                image -> {
                    attempts.add(image);
                    return true;
                });
        assertEquals(List.of(6L, 3L, 5L, 2L), attempts);
        queue.retain(List.of(), Set.of());
        assertTrue(queue.isEmpty());
    }

    @Test
    void zeroRemainingSlotsDoNotAttemptEvenWithExpiredDeadline() {
        var queue = queue(1);
        queue.drain(() -> 2, 1, 0, image -> fail("No remaining quota"));
        assertEquals(1, queue.size());
    }

    @Test
    void reframeKeepsClientImageUntilCurrentOwnerIsReadyThenForgetsOnlyOnDeparture() {
        var access = PlanetAccess.SHARED;
        var before = ObserverFrame.identity(0, 0);
        var after = new ObserverFrame(0, 0, 1, 16, -32);
        long a = ChunkPos.asLong(100000, 100000), b = ChunkPos.asLong(100001, 100000);
        var view = new ImageStream.View(null);
        var oldPlan = ImageStream.plan(access, before, new ChunkPos(a));
        view.markSent(a, oldPlan);
        view.light.mark(a, 0, 0);
        view.lightChanges.put(a, new LightChanges());
        view.lightColumns.put(a, null);
        view.probeLight.put(a, new byte[0]);
        view.pending.retain(List.of(b), Set.of());
        var forgotten = new ArrayList<Long>();
        var reroot = ObserverFrame.identity(0, 1);
        assertNotEquals(before, reroot);
        ImageStream.updateWindow(view, access, reroot, List.of(a, b), true, forgotten::add);
        assertSame(oldPlan, view.sent.get(a));
        assertTrue(forgotten.isEmpty());
        ImageStream.updateWindow(view, access, after, List.of(a, b), true, forgotten::add);
        assertTrue(forgotten.isEmpty(), "Reframe must preserve the old client image");
        assertTrue(view.sent.isEmpty());
        assertEquals(Set.of(a), view.replacements);
        assertEquals(0, view.light.size());
        assertTrue(view.lightChanges.isEmpty());
        assertTrue(view.lightColumns.isEmpty());
        assertTrue(view.probeLight.isEmpty());
        for (int tick = 0; tick < 3; tick++) {
            ImageStream.updateWindow(view, access, after, List.of(a, b), false, forgotten::add);
            view.pending.drain(() -> 0, 1, 24, image -> false);
        }
        assertTrue(forgotten.isEmpty(), "Missing owners do not unload the client image");
        assertEquals(2, view.pending.size());
        var attempts = new ArrayList<Long>();
        view.pending.drain(
                () -> 0,
                1,
                24,
                image -> {
                    attempts.add(image);
                    view.markSent(image, ImageStream.plan(access, after, new ChunkPos(image)));
                    return true;
                });
        assertEquals(Set.of(a, b), new HashSet<>(attempts));
        assertEquals(2, attempts.size());
        assertNotEquals(oldPlan.signature(), view.sent.get(a).signature());
        assertTrue(view.replacements.isEmpty());
        assertTrue(forgotten.isEmpty(), "Ready replacement updates without a preceding forget");
        ImageStream.updateWindow(view, access, after, List.of(b), false, forgotten::add);
        ImageStream.updateWindow(view, access, after, List.of(b), false, forgotten::add);
        assertEquals(List.of(a), forgotten);
        assertEquals(Set.of(b), view.sent.keySet());
    }

    @Test
    void leavingBeforeReplacementIsReadyForgetsPreservedImageExactlyOnce() {
        var access = PlanetAccess.SHARED;
        var before = ObserverFrame.identity(0, 0);
        var after = new ObserverFrame(0, 0, 1, 16, -32);
        long image = ChunkPos.asLong(100000, 100000);
        var view = new ImageStream.View(null);
        view.markSent(image, ImageStream.plan(access, before, new ChunkPos(image)));
        var forgotten = new ArrayList<Long>();
        ImageStream.updateWindow(view, access, after, List.of(image), true, forgotten::add);
        view.pending.drain(() -> 0, 1, 24, key -> false);
        ImageStream.updateWindow(view, access, before, List.of(image), true, forgotten::add);
        assertTrue(forgotten.isEmpty());
        assertEquals(Set.of(image), view.replacements);
        ImageStream.updateWindow(view, access, before, List.of(), false, forgotten::add);
        ImageStream.updateWindow(view, access, before, List.of(), false, forgotten::add);
        assertEquals(List.of(image), forgotten);
        assertTrue(view.replacements.isEmpty());
        assertTrue(view.pending.isEmpty());
        assertTrue(view.sent.isEmpty());
    }

    @Test
    void nearestArrivalBypassesLargeMissingOwnerBacklogWithinTwoAttempts() {
        for (boolean consumeArrivalTurn : List.of(false, true)) {
            var queue = new ChunkSendQueue();
            var wanted = new ArrayList<>(LongStream.rangeClosed(1, 97).boxed().toList());
            queue.retain(wanted, Set.of());
            if (consumeArrivalTurn) {
                wanted.addFirst(1000L);
                queue.retain(wanted, Set.of());
                queue.drain(() -> 2, 1, 1, image -> image == 1000);
                wanted.remove(1000L);
            }
            wanted.addFirst(2000L);
            queue.retain(wanted, Set.of());
            var attempts = new ArrayList<Long>();
            for (int tick = 1; tick <= 2 && !attempts.contains(2000L); tick++) {
                queue.drain(
                        () -> 2,
                        1,
                        24,
                        image -> {
                            attempts.add(image);
                            return image == 2000;
                        });
            }
            assertTrue(attempts.contains(2000L));
            assertTrue(attempts.size() <= 2);
        }
    }

    @Test
    void continuousMovementWithOneExpiredDeadlineAttemptCannotStarveRecoveredOldOwner() {
        var queue = new ChunkSendQueue();
        var wanted = new ArrayList<>(LongStream.rangeClosed(1, 97).boxed().toList());
        queue.retain(wanted, Set.of());
        var sent = new HashSet<Long>();
        var delivered = new HashMap<Long, Integer>();
        for (int tick = 1; tick <= 210; tick++) {
            int now = tick;
            wanted.addFirst(1000L + tick);
            queue.retain(wanted, sent);
            queue.drain(
                    () -> 2,
                    1,
                    24,
                    image -> {
                        if (image < 1000 && (image != 97 || now < 3)) return false;
                        sent.add(image);
                        delivered.put(image, now);
                        return true;
                    });
        }
        assertEquals(1, delivered.get(1001L));
        assertEquals(194, delivered.get(97L), "97 retries get 97 turns despite new work each tick");
        for (long arrival = 1; arrival <= 105; arrival++) {
            assertEquals(
                    (int) (2 * arrival - 1),
                    delivered.get(1000 + arrival),
                    "New arrivals retain FIFO order despite reverse-distance window updates");
        }
        System.out.printf(
                "EVIDENCE followup: 97 missing old owners + ready nearest arrival: <=2 attempts; continuous arrivals, one attempt/tick: recovered old#97=tick %d, first new=tick %d%n",
                delivered.get(97L), delivered.get(1001L));
    }

    @Test
    void failedArrivalsJoinRetryTailAndAreNeverRetriedInTheSameDrain() {
        var queue = queue(1, 2);
        queue.retain(List.of(3L, 4L, 1L, 2L), Set.of());
        var attempts = new ArrayList<Long>();
        queue.drain(
                () -> 0,
                1,
                100,
                image -> {
                    attempts.add(image);
                    return false;
                });
        assertEquals(List.of(3L, 1L, 4L, 2L), attempts);
        attempts.clear();
        queue.drain(
                () -> 0,
                1,
                100,
                image -> {
                    attempts.add(image);
                    return true;
                });
        assertEquals(List.of(3L, 1L, 4L, 2L), attempts);
        assertTrue(queue.isEmpty());
    }

    /** Frozen bounded model of the baseline's iterator/count loop, not a source-text assertion. */
    private static void oldDrain(
            ArrayDeque<Long> queue,
            AtomicLong clock,
            long deadline,
            int limit,
            LongPredicate send) {
        int count = 0, tries = 0;
        var it = queue.iterator();
        while (it.hasNext() && tries < limit && (count == 0 || clock.get() < deadline)) {
            long image = it.next();
            tries++;
            if (!send.test(image)) continue;
            it.remove();
            count++;
        }
    }

    @Test
    void boundedOfflineBeforeAfterEvidence() {
        var old = new ArrayDeque<>(LongStream.rangeClosed(1, 25).boxed().toList());
        var current = new ChunkSendQueue();
        current.retain(new ArrayList<>(old), Set.of());
        var oldReadyTick = new AtomicLong(-1);
        var newReadyTick = new AtomicLong(-1);
        var oldAttempts = new AtomicLong();
        var newAttempts = new AtomicLong();
        for (int tick = 1; tick <= 200; tick++) {
            int now = tick;
            oldDrain(
                    old,
                    new AtomicLong(),
                    1,
                    24,
                    image -> {
                        oldAttempts.incrementAndGet();
                        if (image == 25) oldReadyTick.set(now);
                        return image == 25;
                    });
            current.drain(
                    () -> 0,
                    1,
                    24,
                    image -> {
                        newAttempts.incrementAndGet();
                        if (image == 25) newReadyTick.set(now);
                        return image == 25;
                    });
        }
        assertEquals(-1, oldReadyTick.get());
        assertEquals(2, newReadyTick.get());
        var oldTime = new AtomicLong();
        var newTime = new AtomicLong();
        oldDrain(
                new ArrayDeque<>(LongStream.range(0, 24).boxed().toList()),
                oldTime,
                10,
                24,
                image -> {
                    oldTime.addAndGet(7);
                    return false;
                });
        var failures = new ChunkSendQueue();
        failures.retain(LongStream.range(0, 24).boxed().toList(), Set.of());
        failures.drain(
                newTime::get,
                10,
                24,
                image -> {
                    newTime.addAndGet(7);
                    return false;
                });
        assertEquals(168, oldTime.get());
        assertEquals(14, newTime.get());
        System.out.printf(
                "EVIDENCE queue: bound=200 ticks, ready#25 baseline=not sent, after=tick %d; attempts=%d/%d; all-fail virtual ns=%d/%d (deadline=10, cost=7)\n",
                newReadyTick.get(),
                oldAttempts.get(),
                newAttempts.get(),
                oldTime.get(),
                newTime.get());
    }
}
