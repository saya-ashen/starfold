package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.*;

class GroundInvalidationsTest {
    @Test
    void smallPlanetUsesItsOwnEdgeWithinTheFixedStorageGrid() {
        var shape = new PlanetShape(416);
        long edge = GroundTileKeys.key(shape, 0, 415, 32);
        long outside = GroundTileKeys.key(0, 1024, 32);
        assertTrue(GroundTileKeys.owned(shape, edge));
        assertFalse(GroundTileKeys.owned(shape, outside));
        assertTrue(GroundTileKeys.owned(PlanetShape.STANDARD, outside));
        assertThrows(
                IllegalArgumentException.class, () -> GroundStreaming.neighbours(shape, outside));
        for (long neighbour : GroundStreaming.neighbours(shape, edge))
            assertTrue(GroundTileKeys.owned(shape, neighbour));
    }

    @Test
    void deltaRetainsInteriorAndSeamEditsWithoutSnapshotImages() {
        var pending = new GroundInvalidations();
        long key = 0;
        var shape = PlanetShape.STANDARD;
        pending.edit(shape, key, new BlockPos(8, 128, 8));
        pending.edit(shape, key, new BlockPos(8, 129, 8));
        assertEquals(List.of(key), pending.drain());
        assertTrue(pending.drain().isEmpty());
        pending.edit(shape, key, new BlockPos(0, 128, 0));
        var neighbours = GroundStreaming.neighbours(shape, key);
        assertEquals(Set.of(key, neighbours[0], neighbours[2]), Set.copyOf(pending.drain()));
        pending.surface(shape, key);
        assertEquals(5, pending.drain().size());
        pending.edit(shape, key, new BlockPos(15, 128, 15));
        pending.clear();
        assertTrue(pending.drain().isEmpty(), "logout must discard old invalidations");
    }
}
