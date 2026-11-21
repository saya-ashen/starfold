package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;

import org.junit.jupiter.api.Test;

class SableCollisionBoundsTest {
    @Test
    void expandedRangesOutsideWorldSectionsVisitNoColumns() {
        for (int[] y : new int[][] {{20, 24}, {-8, -5}, {10000, 10001}}) {
            var bounds = new BoundingBox3i(100, y[0], -5, 102, y[1], -3);
            assertSame(bounds, SableTickets.collisionBounds(bounds, -4, 24));
            assertTrue(bounds.minX() > bounds.maxX());
            assertEquals(y[0], bounds.minY());
            assertEquals(y[1], bounds.maxY());
            int columns = 0;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++)
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) columns++;
            assertEquals(0, columns);
        }
    }

    @Test
    void anyValidCollisionSectionPreservesAllOriginalBounds() {
        // These are already expanded/predicted ranges, not the unexpanded hull's height.
        for (int[] y : new int[][] {{19, 24}, {-8, -4}, {-8, 24}, {-4, 19}, {0, 0}}) {
            var bounds = new BoundingBox3i(100, y[0], -5, 102, y[1], -3);
            assertSame(bounds, SableTickets.collisionBounds(bounds, -4, 24));
            assertEquals(100, bounds.minX());
            assertEquals(102, bounds.maxX());
            assertEquals(-5, bounds.minZ());
            assertEquals(-3, bounds.maxZ());
            assertEquals(y[0], bounds.minY());
            assertEquals(y[1], bounds.maxY());
        }
    }

    @Test
    void guardAgreesWithUpstreamSectionIndexRuleAcrossDifferentWorldHeights() {
        for (int base : new int[] {-32, -4, 0, 8})
            for (int count : new int[] {1, 16, 24, 128})
                for (int low = base - 3; low <= base + count + 3; low++)
                    for (int high = low; high <= low + 3; high++) {
                        boolean upstream = false;
                        for (int section = low; section <= high; section++) {
                            int index = section - base;
                            upstream |= index >= 0 && index < count;
                        }
                        var bounds = new BoundingBox3i(100, low, -5, 102, high, -3);
                        SableTickets.collisionBounds(bounds, base, count);
                        assertEquals(upstream, bounds.minX() <= bounds.maxX());
                    }
    }
}
