package dev.starfold.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundLodArenaTest {
    @Test void cachedCommandsPreserveCountsBasesAndOffsetsUntilTopologyChanges() {
        try (var commands = new GroundLodArena.Commands()) {
            assertTrue(commands.begin(3, 1));
            commands.add(new GroundLodArena.Range(null, 0, 8));
            commands.add(new GroundLodArena.Range(null, 8, 12));
            commands.add(new GroundLodArena.Range(null, 40, 4));
            commands.prepare();
            assertEquals(2, commands.draws);
            assertEquals(30, commands.counts.get(0));
            assertEquals(6, commands.counts.get(1));
            assertEquals(0, commands.bases.get(0));
            assertEquals(40, commands.bases.get(1));
            assertEquals(0L, commands.offsets.get(1));
            assertFalse(commands.begin(3, 1));
            commands.prepare();
            assertEquals(2, commands.counts.remaining()); // Reuse must not flip an already flipped buffer.
            assertEquals(30, commands.counts.get(0));
            assertTrue(commands.begin(20, 2)); // Compaction / replacement changes offsets and grows storage.
            commands.add(new GroundLodArena.Range(null, 80, 24));
            commands.prepare();
            assertEquals(1, commands.draws);
            assertEquals(36, commands.counts.get(0));
            assertEquals(80, commands.bases.get(0));
            assertEquals(0L, commands.offsets.get(0));
            assertTrue(commands.begin(0, 3)); // Retirement clears every old draw.
            commands.prepare();
            assertEquals(0, commands.draws);
            assertEquals(0, commands.counts.remaining());
        }
    }
    @Test void firstFitReusesAndCoalescesHolesWithoutMovingLiveRanges() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        assertEquals(0, ranges.allocate(8));
        assertEquals(8, ranges.allocate(12));
        assertEquals(20, ranges.allocate(12));
        assertEquals(-1, ranges.allocate(4));
        ranges.free(8, 12);
        assertEquals(8, ranges.allocate(4));
        ranges.free(0, 8);
        ranges.free(8, 4);
        assertEquals(0, ranges.allocate(20));
        ranges.grow(64);
        assertEquals(32, ranges.allocate(16));
        ranges.free(20, 12);
        ranges.free(0, 20);
        ranges.free(32, 16);
        assertEquals(0, ranges.allocate(64));
        assertTrue(ranges.free.isEmpty());
    }
}
