package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

class VoxelMesherQuadtreeTest {
    @BeforeAll
    static void registry() {
        PlanetTerrainTest.registry();
    }

    @Test
    void deformationCellsUseGlobalGridAndNeverCreateInternalWalls() {
        var solid =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState())));
        var quads = VoxelMesher.boxes(8, 8, 40, 40, 8, (x, z) -> solid, 16, 114);
        assertEquals(9, quads.stream().filter(q -> q.axis() == 3).count());
        for (var quad : quads) {
            for (int axis : new int[] {0, 2}) {
                double low =
                        quad.points().stream()
                                .mapToDouble(p -> axis == 0 ? p.x() : p.z())
                                .min()
                                .orElseThrow();
                double high =
                        quad.points().stream()
                                .mapToDouble(p -> axis == 0 ? p.x() : p.z())
                                .max()
                                .orElseThrow();
                if (low != high) assertEquals(Math.floor(low / 16), Math.ceil(high / 16) - 1);
            }
            if (quad.axis() < 2)
                assertTrue(quad.points().stream().allMatch(p -> p.x() == 8 || p.x() == 40));
            if (quad.axis() >= 4)
                assertTrue(quad.points().stream().allMatch(p -> p.z() == 8 || p.z() == 40));
        }
    }

    @Test
    void onlyLowestSolidBoundaryExtendsAndInteriorStillSubtractsRunIntervals() {
        var column =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState()),
                                new VoxelColumn.Run(
                                        140, 142, Blocks.OAK_PLANKS.defaultBlockState())));
        var neighbour =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(124, 127, Blocks.STONE.defaultBlockState())));
        var quads =
                VoxelMesher.boxes(0, 0, 16, 16, 8, (x, z) -> x == 8 ? neighbour : column, 16, 100);
        var interior =
                quads.stream()
                        .filter(
                                q ->
                                        q.axis() == 1
                                                && q.material().is(Blocks.STONE)
                                                && q.points().stream().allMatch(p -> p.x() == 8))
                        .toList();
        assertEquals(2, interior.size());
        assertTrue(interior.stream().anyMatch(q -> minY(q) == 120 && maxY(q) == 124));
        assertTrue(interior.stream().anyMatch(q -> minY(q) == 127 && maxY(q) == 130));
        var outer =
                quads.stream()
                        .filter(q -> q.axis() == 0 && q.points().stream().allMatch(p -> p.x() == 0))
                        .toList();
        assertTrue(outer.stream().anyMatch(q -> q.material().is(Blocks.STONE) && minY(q) == 100));
        assertTrue(
                outer.stream()
                        .filter(q -> q.material().is(Blocks.OAK_PLANKS))
                        .allMatch(q -> minY(q) == 140));
        assertTrue(
                quads.stream()
                        .filter(q -> q.axis() != 2 && q.axis() != 3)
                        .noneMatch(q -> minY(q) < 130 && maxY(q) > 140));
    }

    @Test
    void conservativeFloorCanOnlyLowerTheExistingSolidBoundary() {
        var solid =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState())));
        var legacy = VoxelMesher.boxes(0, 0, 16, 16, 2, (x, z) -> solid);
        var higher = VoxelMesher.boxes(0, 0, 16, 16, 2, (x, z) -> solid, 16, 125);
        assertEquals(legacy, higher);
        assertEquals(
                120,
                higher.stream().mapToDouble(VoxelMesherQuadtreeTest::minY).min().orElseThrow());
    }

    private static double minY(VoxelMesher.Quad quad) {
        return quad.points().stream().mapToDouble(p -> p.y()).min().orElseThrow();
    }

    private static double maxY(VoxelMesher.Quad quad) {
        return quad.points().stream().mapToDouble(p -> p.y()).max().orElseThrow();
    }
}
