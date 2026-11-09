package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.BodyId;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class BodyIdAllocatorTest {
    @Test
    void countersAreIndependentInAnyOrderAndDirtyOnEveryAllocation() {
        var ids = new BodyIdAllocator();
        assertEquals(1, ids.allocateRegion());
        assertEquals(2, ids.allocateRegion());
        for (int region : new int[] {2, 0, 1}) {
            ids.setDirty(false);
            assertEquals(new BodyId(region, 0), ids.allocate(region));
            assertTrue(ids.isDirty());
        }
        assertEquals(new BodyId(1, 1), ids.allocate(1));
        assertEquals(new BodyId(2, 1), ids.allocate(2));
        assertEquals(new BodyId(0, 1), ids.allocate(0));
        assertThrows(IllegalArgumentException.class, () -> ids.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> ids.allocate(3));
    }

    @Test
    void reloadKeepsUnusedRegionsAndIdsNotPresentInAnyCatalog() {
        var ids = new BodyIdAllocator();
        ids.reserve(PlanetSystem.model().bodies());
        ids.allocateRegion();
        ids.allocateRegion();
        ids.allocate(2);
        ids.allocate(2);
        ids.allocate(0);
        var loaded = BodyIdAllocator.load(ids.save(new CompoundTag(), null), null);
        assertEquals(new BodyId(2, 2), loaded.allocate(2));
        assertEquals(new BodyId(1, 0), loaded.allocate(1));
        assertEquals(new BodyId(0, PlanetSystem.model().bodies().size() + 1), loaded.allocate(0));
        assertEquals(3, loaded.allocateRegion());
    }

    @Test
    void reservationUsesHighestIndexNotCatalogSizeAndNeverRewinds() {
        var ids = new BodyIdAllocator();
        var body = PlanetSystem.model().bodies().getFirst().identified(new BodyId(0, 40));
        ids.reserve(java.util.List.of(body));
        assertEquals(new BodyId(0, 41), ids.allocate(0));
        ids.reserve(java.util.List.of(body));
        assertEquals(new BodyId(0, 42), ids.allocate(0));
    }

    @Test
    void oldMissingAndMalformedCountersAreRefused() {
        var tag = new BodyIdAllocator().save(new CompoundTag(), null);
        for (int format : new int[] {0, 2}) {
            var bad = tag.copy();
            bad.putInt("format", format);
            assertThrows(IllegalArgumentException.class, () -> BodyIdAllocator.load(bad, null));
        }
        for (String field : new String[] {"format", "next_region", "next_indices"}) {
            var bad = tag.copy();
            bad.remove(field);
            assertThrows(IllegalArgumentException.class, () -> BodyIdAllocator.load(bad, null));
        }
        var duplicate = tag.copy();
        duplicate
                .getList("next_indices", 10)
                .add(duplicate.getList("next_indices", 10).get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> BodyIdAllocator.load(duplicate, null));
        var negative = tag.copy();
        negative.getList("next_indices", 10).getCompound(0).putInt("next_index", -1);
        assertThrows(IllegalArgumentException.class, () -> BodyIdAllocator.load(negative, null));
        var wrongType = tag.copy();
        wrongType.getList("next_indices", 10).getCompound(0).putLong("next_index", 0);
        assertThrows(IllegalArgumentException.class, () -> BodyIdAllocator.load(wrongType, null));
    }

    @Test
    void exhaustionRefusesRatherThanWrapping() {
        var tag = new BodyIdAllocator().save(new CompoundTag(), null);
        tag.getList("next_indices", 10).getCompound(0).putInt("next_index", Integer.MAX_VALUE);
        tag.putInt("next_region", Integer.MAX_VALUE);
        var ids = BodyIdAllocator.load(tag, null);
        assertThrows(IllegalStateException.class, () -> ids.allocate(0));
        assertThrows(IllegalStateException.class, ids::allocateRegion);
    }
}
