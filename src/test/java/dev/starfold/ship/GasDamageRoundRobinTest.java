package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

class GasDamageRoundRobinTest {
    @Test
    void serviceAndWriteCapsDoNotStarveStableCohortsOrExactMultipleSizes() {
        for (int count : new int[] {1, 2, 15, 16, 17, 32, 64, 257}) {
            var order = new GasDamageRoundRobin();
            var cooldown = new GasDamageCooldown();
            var serviced = new HashSet<Integer>();
            var damaged = new HashSet<Integer>();
            for (int tick = 0; tick < count * 4 + 100; tick++) {
                var batch = order.begin(count);
                assertTrue(batch.size() <= 16);
                var unique = new HashSet<Integer>();
                int last = -1, writes = 0;
                for (int visit = 0; visit < batch.size(); visit++) {
                    int index = batch.index(visit);
                    assertTrue(unique.add(index));
                    serviced.add(index);
                    var id = new UUID(0, index);
                    if (cooldown.ready(id, tick, 20)) {
                        cooldown.succeeded(id, tick, 20);
                        writes++;
                        last = visit;
                        damaged.add(index);
                    }
                }
                assertTrue(writes <= 2);
                order.finish(batch, last);
            }
            assertEquals(count, serviced.size());
            assertEquals(
                    count,
                    damaged.size(),
                    "every due hull must eventually receive its slot: " + count);
        }
    }

    @Test
    void adversarialPermutationCannotHideOneUuidInTheUnvisitedSlotEveryTick() {
        var original = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 17; i++) original.add(new UUID(0, i));
        UUID victim = original.getFirst();
        var order = new GasDamageRoundRobin();
        var cooldown = new GasDamageCooldown();
        var serviced = new HashSet<UUID>();
        var damaged = new HashSet<UUID>();
        for (int tick = 0; tick < 168; tick++) {
            var batch = order.begin(original.size());
            var callerOrder = new java.util.ArrayList<>(original);
            java.util.Collections.rotate(callerOrder, tick);
            int missed = (batch.start() + batch.size()) % batch.cohort();
            java.util.Collections.swap(callerOrder, callerOrder.indexOf(victim), missed);
            var cohort =
                    GasDamageRoundRobin.ordered(
                            callerOrder, java.util.function.Function.identity());
            int last = -1;
            for (int visit = 0; visit < batch.size(); visit++) {
                UUID id = cohort.get(batch.index(visit));
                serviced.add(id);
                if (cooldown.ready(id, tick, 20)) {
                    cooldown.succeeded(id, tick, 20);
                    damaged.add(id);
                    last = visit;
                }
            }
            order.finish(batch, last);
        }
        assertTrue(serviced.contains(victim), "permuted input must not hide this UUID forever");
        assertTrue(damaged.contains(victim));
        assertEquals(new HashSet<>(original), serviced);
        assertEquals(new HashSet<>(original), damaged);
    }

    @Test
    void normalizedCohortRejectsDuplicateIdentityInsteadOfDoubleServicingAHull() {
        var one = new UUID(1, 2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GasDamageRoundRobin.ordered(
                                java.util.List.of(one, one),
                                java.util.function.Function.identity()));
        assertThrows(
                IllegalArgumentException.class,
                () -> GasDamageRoundRobin.ordered(java.util.List.of(one), ignored -> null));
        var copy =
                GasDamageRoundRobin.ordered(
                        java.util.List.of(one), java.util.function.Function.identity());
        assertThrows(UnsupportedOperationException.class, copy::clear);
    }

    @Test
    void noWriteStillAdvancesScanAndLateWriteDoesNotRepeatItsCohortPrefix() {
        var order = new GasDamageRoundRobin();
        var batch = order.begin(32);
        assertEquals(0, batch.index(0));
        order.finish(batch, -1);
        batch = order.begin(32);
        assertEquals(16, batch.index(0));
        order.finish(batch, 15);
        batch = order.begin(32);
        assertEquals(0, batch.index(0));
        order.finish(batch, 0);
        batch = order.begin(32);
        assertEquals(1, batch.index(0));
        order.finish(batch, -1);
        var empty = order.begin(0);
        assertEquals(0, empty.size());
        order.finish(empty, -1);
        var changed = order.begin(1);
        assertEquals(0, changed.index(0));
        order.finish(changed, -1);
    }

    @Test
    void completingAStaleBatchOrAnOutOfRangeVisitIsRejected() {
        var order = new GasDamageRoundRobin();
        var batch = order.begin(16);
        assertThrows(IllegalStateException.class, () -> order.begin(16));
        assertThrows(IllegalArgumentException.class, () -> batch.index(-1));
        assertThrows(IllegalArgumentException.class, () -> batch.index(16));
        assertThrows(IllegalArgumentException.class, () -> order.finish(batch, 16));
        order.finish(batch, -1);
        var fresh = order.begin(16);
        assertThrows(IllegalArgumentException.class, () -> order.finish(batch, -1));
        order.finish(fresh, -1);
    }
}
