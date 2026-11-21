package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

class UnderwaterVoxelTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @Test
    void wetPlantsKeepCutoutsAndTheIndependentWaterSurface() {
        for (var plant :
                List.of(
                        Blocks.KELP.defaultBlockState(),
                        Blocks.KELP_PLANT.defaultBlockState(),
                        Blocks.SEAGRASS.defaultBlockState(),
                        Blocks.TUBE_CORAL_FAN.defaultBlockState(),
                        Blocks.TUBE_CORAL_WALL_FAN
                                .defaultBlockState()
                                .setValue(BlockStateProperties.WATERLOGGED, true))) {
            assertTrue(VoxelMesher.plant(plant), plant.toString());
            var water = Blocks.WATER.defaultBlockState();
            var column =
                    VoxelColumn.compose(
                            List.of(
                                    new VoxelColumn.Run(0, 1, Blocks.SAND.defaultBlockState()),
                                    new VoxelColumn.Run(1, 5, water),
                                    new VoxelColumn.Run(1, 2, plant)));
            assertSame(plant, column.state(1));
            assertSame(water, column.state(4));
            var quads = VoxelMesher.boxes(0, 0, 1, 1, 1, (x, z) -> column);
            assertEquals(
                    4,
                    quads.stream().filter(q -> q.material() == plant && !q.volume()).count(),
                    "Two crossed, double-sided plant faces: " + plant);
            assertEquals(
                    1, quads.stream().filter(q -> q.material() == water && q.axis() == 3).count());
            assertFalse(quads.stream().anyMatch(q -> q.material() == plant && q.volume()));
        }
        for (var plant :
                List.of(
                        Blocks.KELP.defaultBlockState(),
                        Blocks.SEAGRASS.defaultBlockState(),
                        Blocks.TUBE_CORAL_FAN.defaultBlockState())) {
            var column =
                    VoxelColumn.compose(
                            List.of(
                                    new VoxelColumn.Run(124, 125, Blocks.SAND.defaultBlockState()),
                                    new VoxelColumn.Run(125, 128, Blocks.WATER.defaultBlockState()),
                                    new VoxelColumn.Run(128, 129, plant)));
            for (int step : new int[] {1, 2, 4}) {
                var quads = VoxelMesher.boxes(0, 0, step, step, step, (x, z) -> column);
                var caps =
                        quads.stream()
                                .filter(q -> q.material().is(Blocks.WATER) && q.axis() == 3)
                                .toList();
                assertEquals(1, caps.size(), "Top-level aquatic plant at LOD " + step);
                assertTrue(
                        caps.getFirst().points().stream()
                                .allMatch(p -> Math.abs(p.y() - (129 - 1.0 / 9)) < 1e-9));
                assertEquals(
                        step <= 2 ? 4 : 0,
                        quads.stream().filter(q -> q.material() == plant && !q.volume()).count());
            }
        }
        assertFalse(
                VoxelMesher.plant(Blocks.TUBE_CORAL_BLOCK.defaultBlockState()),
                "Solid coral remains a solid seabed object");
    }
}
