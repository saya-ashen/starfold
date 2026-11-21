package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

class GroundLodDemandTest {
    @Test
    void fullCanonicalDomainIsBoundedDeduplicatedAndEventuallyDrained() {
        var demand = new GroundLodDemand();
        for (long key = 0; key < GroundLodDemand.KEYS; key++) {
            demand.put(key, 8);
            demand.put(key, 1);
        }
        assertEquals(98304, demand.size());
        var seen = new HashSet<Long>();
        GroundLodDemand.Entry entry;
        while ((entry = demand.poll()) != null) {
            assertTrue(seen.add(entry.key()), "each canonical tile is delivered once");
            assertEquals(1, entry.target(), "updates replace target without duplicating demand");
        }
        assertEquals(98304, seen.size());
        assertEquals(0, demand.size());
        assertNull(demand.poll());
        assertThrows(IllegalArgumentException.class, () -> demand.put(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> demand.put(98304, 1));
    }

    @Test
    void firstCoverageSpansEveryFaceAndLargeQuadrantBeforeRepeatingARegion() {
        var demand = new GroundLodDemand();
        for (long key = 0; key < GroundLodDemand.KEYS; key++) demand.put(key, 1);
        var quadrants = new HashSet<Long>();
        for (int i = 0; i < 24; i++) {
            long key = demand.poll().key();
            long quadrant = key / 16384 * 4 + (key % 128 / 64) + 2 * (key / 128 % 128 / 64);
            assertTrue(quadrants.add(quadrant), "spread useful work before filling one quadrant");
        }
        assertEquals(24, quadrants.size());
    }

    @Test
    void recurringHotRegionAndTargetUpdatesCannotStarveOlderLocalOrRemoteDemand() {
        var demand = new GroundLodDemand();
        demand.put(0, 8);
        demand.put(1, 8); // Same region, behind the recurring tile.
        demand.put(16384, 8);
        demand.put(98303, 8);
        var seen = new HashSet<Long>();
        for (int i = 0; i < 8; i++) {
            demand.put(0, 1); // Refreshing an existing key must not reorder the region.
            var entry = demand.poll();
            assertNotNull(entry);
            seen.add(entry.key());
            demand.put(entry.key(), 2);
        }
        assertEquals(4, seen.size(), "both regions and each region's older keys get service");
        assertEquals(4, demand.size());
    }

    @Test
    void removalAndResetLeaveNoStaleEntriesOrCursor() {
        var demand = new GroundLodDemand();
        demand.put(98303, 8);
        demand.put(0, 8);
        assertTrue(demand.remove(0));
        assertFalse(demand.remove(0));
        assertFalse(demand.contains(0));
        assertEquals(98303, demand.poll().key());
        demand.put(98303, 1);
        demand.clear();
        assertEquals(0, demand.size());
        assertNull(demand.poll());
        demand.put(16384, 2);
        demand.put(0, 4);
        assertEquals(0, demand.poll().key());
        assertEquals(16384, demand.poll().key());
        assertNull(demand.poll());
    }
}
