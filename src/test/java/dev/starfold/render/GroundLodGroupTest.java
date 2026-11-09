package dev.starfold.render;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GroundLodGroupTest {
    @Test void groupsKeepOrderedSubRangesAndInvalidateWithoutOwningTheBlock() {
        int[] membership = new int[6 * 1024];
        for (long tile = 0; tile < 6 * 16384; tile++) {
            int group = GroundLodGroup.key(tile);
            assertEquals(tile / 16384, group / 1024);
            assertEquals(tile % 128 / 4, group % 32);
            assertEquals(tile / 128 % 128 / 4, group / 32 % 32);
            membership[group]++;
        }
        for (int count : membership) assertEquals(16, count);
        assertNotEquals(GroundLodGroup.key(3), GroundLodGroup.key(4));
        assertNotEquals(GroundLodGroup.key(3 * 128), GroundLodGroup.key(4 * 128));

        var free = new GroundLodArena.Ranges();
        free.grow(64);
        var group = new GroundLodGroup<GroundLodArena.Range>();
        // Out-of-order arrival and unrelated live vertices between the member allocations.
        var high = new GroundLodArena.Range(null, free.allocate(8), 8);
        int unrelated = free.allocate(4);
        var low = new GroundLodArena.Range(null, free.allocate(12), 12);
        var middle = new GroundLodArena.Range(null, free.allocate(4), 4);
        group.update(129, high);
        group.update(0, low);
        group.update(3, middle);
        assertEquals(List.of(0L, 3L, 129L), new ArrayList<>(group.members.keySet()));

        var block = new GroundLodArena.Range(null, free.allocate(24), 24);
        assertEquals(28, block.first());
        int offset = 0;
        var relocated = new ArrayList<GroundLodArena.Range>();
        for (var source : group.members.values()) {
            var range = block.slice(offset, source.vertices());
            assertTrue(range.first() >= block.first()); // No destination overlaps a live source.
            relocated.add(range);
            offset += range.vertices();
            free.free(source.first(), source.vertices());
        }
        assertEquals(block.vertices(), offset);
        assertEquals(List.of(28, 40, 44), relocated.stream().map(GroundLodArena.Range::first).toList());
        assertEquals(block.indices(), relocated.stream().mapToInt(GroundLodArena.Range::indices).sum());
        assertEquals(-1, free.allocate(24)); // The compact block and unrelated tile are still live.
        assertEquals(64 - block.vertices() - 4, free.free.values().stream().mapToInt(Integer::intValue).sum());

        group.layers.put(null, block);
        group.bounds = new AABB(0, -3, 0, 64, 90, 64);
        group.compact = true;
        group.update(3, relocated.get(1)); // Replacement/fade restart uses this same invalidation.
        assertFalse(group.compact);
        assertNull(group.bounds);
        assertTrue(group.layers.isEmpty());
        assertSame(relocated.get(1), group.members.get(3L));
        group.layers.put(null, block);
        group.compact = true;
        group.update(129, null);
        assertFalse(group.compact);
        assertTrue(group.layers.isEmpty());
        assertEquals(List.of(0L, 3L), new ArrayList<>(group.members.keySet()));
        group.layers.put(null, block);
        group.compact = true;
        group.update(128, high);
        assertFalse(group.compact);
        assertTrue(group.layers.isEmpty());
        // Invalidating the alias must not free the block; only the member sub-ranges are owners.
        assertEquals(-1, free.allocate(24));
        for (var range : relocated) free.free(range.first(), range.vertices());
        free.free(unrelated, 4);
        assertEquals(0, free.allocate(64));
        assertTrue(free.free.isEmpty());
    }
}
