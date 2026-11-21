package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class VoxelFluidBoxesTest {
    private static final double EPS = 1e-9;
    private static final int X = 32, Z = 48;
    private static final int[] STEPS = {1, 2, 16};
    private static final int[] SIDES = {0, 1, 4, 5};

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static BlockState water(int level) {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
    }

    private static VoxelColumn column(int bottom, int top, BlockState state) {
        return new VoxelColumn(List.of(new VoxelColumn.Run(bottom, top, state)));
    }

    private static VoxelColumn air() {
        return new VoxelColumn(List.of());
    }

    private static List<VoxelMesher.Quad> boxes(
            VoxelColumn center, VoxelColumn neighbor, int step) {
        return VoxelMesher.boxes(
                X, Z, X + step, Z + step, step, (x, z) -> x == X && z == Z ? center : neighbor);
    }

    private static List<VoxelMesher.Quad> fluidFaces(List<VoxelMesher.Quad> quads, int axis) {
        return quads.stream()
                .filter(q -> q.volume() && q.material().is(Blocks.WATER) && q.axis() == axis)
                .toList();
    }

    private static double minY(VoxelMesher.Quad q) {
        return q.points().stream().mapToDouble(p -> p.y()).min().orElseThrow();
    }

    private static double maxY(VoxelMesher.Quad q) {
        return q.points().stream().mapToDouble(p -> p.y()).max().orElseThrow();
    }

    private static void sideBounds(
            VoxelMesher.Quad q,
            int step,
            double bottom,
            double top,
            double lateralLow,
            double lateralHigh) {
        int axis = q.axis();
        double plane =
                switch (axis) {
                    case 0 -> X;
                    case 1 -> X + step;
                    case 4 -> Z;
                    case 5 -> Z + step;
                    default -> throw new AssertionError("Not a side");
                };
        assertEquals(4, q.points().size());
        assertEquals(bottom, minY(q), EPS);
        assertEquals(top, maxY(q), EPS);
        assertTrue(
                q.points().stream()
                        .allMatch(p -> Math.abs((axis < 2 ? p.x() : p.z()) - plane) < EPS));
        assertEquals(
                lateralLow,
                q.points().stream().mapToDouble(p -> axis < 2 ? p.z() : p.x()).min().orElseThrow(),
                EPS);
        assertEquals(
                lateralHigh,
                q.points().stream().mapToDouble(p -> axis < 2 ? p.z() : p.x()).max().orElseThrow(),
                EPS);
        var a = q.points().get(0);
        var b = q.points().get(1);
        var c = q.points().get(2);
        double normal =
                axis < 2
                        ? (b.y() - a.y()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.y() - a.y())
                        : (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
        assertTrue((axis == 0 || axis == 4 ? -normal : normal) > 0, "Outward winding axis " + axis);
    }

    private static void oneSidePerAxis(
            List<VoxelMesher.Quad> quads, int step, double bottom, double top) {
        for (int axis : SIDES) {
            var sides = fluidFaces(quads, axis);
            assertEquals(1, sides.size(), "axis " + axis);
            double low = axis < 2 ? Z : X;
            sideBounds(sides.getFirst(), step, bottom, top, low, low + step);
        }
    }

    @Test
    void sourceFlowingAndFallingKeepStateAndTheirOwnFreeSurfaceAtEveryStep() {
        int[] levels = {0, 4, 8};
        double[] heights = {8.0 / 9, 4.0 / 9, 8.0 / 9};
        for (int i = 0; i < levels.length; i++) {
            var state = water(levels[i]);
            var input = column(120, 121, state);
            var restored = VoxelColumn.unpack(input.packed(), 0, 1);
            assertEquals(state, restored.state(120));
            assertEquals(
                    i == 2,
                    restored.state(120).getFluidState().getValue(BlockStateProperties.FALLING));
            for (int step : STEPS) {
                var quads = boxes(restored, air(), step);
                assertEquals(5, quads.size());
                var caps = fluidFaces(quads, 3);
                assertEquals(1, caps.size());
                assertEquals(120 + heights[i], minY(caps.getFirst()), EPS);
                assertEquals(120 + heights[i], maxY(caps.getFirst()), EPS);
                oneSidePerAxis(quads, step, 120, 120 + heights[i]);
                assertTrue(quads.stream().allMatch(q -> q.material().equals(state)));
            }
        }
    }

    @Test
    void stateChangesInsideAContinuousColumnHaveNoLoweredSeamsOrInternalCaps() {
        var center =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(120, 124, water(0)),
                                new VoxelColumn.Run(124, 126, water(4)),
                                new VoxelColumn.Run(126, 128, water(8))));
        for (int step : STEPS) {
            var quads = boxes(center, air(), step);
            assertEquals(1, fluidFaces(quads, 3).size());
            assertEquals(127 + 8.0 / 9, minY(fluidFaces(quads, 3).getFirst()), EPS);
            for (int axis : SIDES) {
                var sides =
                        fluidFaces(quads, axis).stream()
                                .sorted(Comparator.comparingDouble(VoxelFluidBoxesTest::minY))
                                .toList();
                assertEquals(3, sides.size());
                double low = axis < 2 ? Z : X;
                sideBounds(sides.get(0), step, 120, 124, low, low + step);
                sideBounds(sides.get(1), step, 124, 126, low, low + step);
                sideBounds(sides.get(2), step, 126, 127 + 8.0 / 9, low, low + step);
            }
            var neighbor =
                    new VoxelColumn(
                            List.of(
                                    new VoxelColumn.Run(120, 124, water(4)),
                                    new VoxelColumn.Run(124, 128, water(0))));
            var surrounded = boxes(center, neighbor, step);
            assertEquals(1, surrounded.size(), "No internal tile-boundary water walls");
        }
    }

    @Test
    void lowerWaterAndSplitStoneLeaveOnlyExplicitExposedBands() {
        var center = column(120, 128, water(0));
        var stoneGap =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(120, 122, Blocks.STONE.defaultBlockState()),
                                new VoxelColumn.Run(125, 128, Blocks.STONE.defaultBlockState())));
        for (int step : STEPS) {
            oneSidePerAxis(
                    boxes(center, column(120, 124, water(0)), step),
                    step,
                    123 + 8.0 / 9,
                    127 + 8.0 / 9);
            oneSidePerAxis(boxes(center, stoneGap, step), step, 122, 125);
            var flowingNeighbor = column(120, 128, water(4));
            oneSidePerAxis(
                    boxes(center, flowingNeighbor, step), step, 127 + 4.0 / 9, 127 + 8.0 / 9);
        }
    }

    @Test
    void opaqueFaceShapesOccludeOnlyThePartTheyActuallyCover() {
        var center = column(120, 121, water(0));
        var bottom =
                column(
                        120,
                        121,
                        Blocks.STONE_SLAB
                                .defaultBlockState()
                                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        var top =
                column(
                        120,
                        121,
                        Blocks.STONE_SLAB
                                .defaultBlockState()
                                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        var stair =
                column(
                        120,
                        121,
                        Blocks.STONE_STAIRS
                                .defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        for (int step : STEPS) {
            oneSidePerAxis(boxes(center, bottom, step), step, 120.5, 120 + 8.0 / 9);
            oneSidePerAxis(boxes(center, top, step), step, 120, 120.5);
            var east = fluidFaces(boxes(center, stair, step), 1);
            assertEquals(1, east.size());
            sideBounds(east.getFirst(), step, 120.5, 120 + 8.0 / 9, Z + step * .5, Z + step);
            var wall = boxes(center, column(120, 121, Blocks.STONE.defaultBlockState()), step);
            assertEquals(1, wall.size());
        }
    }

    @Test
    void tallSlabColumnsKeepEveryGapWithLinearCoverVisitsAtOneAndThirtyTwoRuns() {
        for (int runCount : new int[] {1, 32})
            for (var type : new SlabType[] {SlabType.BOTTOM, SlabType.TOP})
                for (int step : STEPS) {
                    long previousWork = 0;
                    for (int height : new int[] {256, 1024}) {
                        var runs = new ArrayList<VoxelColumn.Run>();
                        for (int r = 0; r < runCount; r++) {
                            var block = r % 2 == 0 ? Blocks.STONE_SLAB : Blocks.ANDESITE_SLAB;
                            runs.add(
                                    new VoxelColumn.Run(
                                            120 + r * height / runCount,
                                            120 + (r + 1) * height / runCount,
                                            block.defaultBlockState()
                                                    .setValue(
                                                            BlockStateProperties.SLAB_TYPE, type)));
                        }
                        var center = column(120, 120 + height, water(0));
                        var neighbor = new VoxelColumn(runs);
                        var work = new VoxelMesher.FluidWork();
                        var quads = measuredBoxes(center, neighbor, step, work);
                        assertEquals(4 * height + 1, quads.size());
                        assertEquals(1, fluidFaces(quads, 3).size());
                        assertEquals(
                                119 + height + 8.0 / 9, minY(fluidFaces(quads, 3).getFirst()), EPS);
                        for (int axis : SIDES) {
                            var sides =
                                    fluidFaces(quads, axis).stream()
                                            .sorted(
                                                    Comparator.comparingDouble(
                                                            VoxelFluidBoxesTest::minY))
                                            .toList();
                            assertEquals(height, sides.size());
                            double low = axis < 2 ? Z : X;
                            for (int y = 0; y < height; y++) {
                                // Explicit slab geometry, independent of occupancy/clipping
                                // helpers.
                                double bottom = 120 + y + (type == SlabType.BOTTOM ? .5 : 0);
                                double top =
                                        type == SlabType.TOP
                                                ? 120 + y + .5
                                                : y == height - 1 ? 120 + y + 8.0 / 9 : 121 + y;
                                sideBounds(sides.get(y), step, bottom, top, low, low + step);
                            }
                        }
                        // Actual boxes path: four edges, each with H edge-gather visits and H
                        // forward scan visits. Output fragments are never revisited. Include sort
                        // comparisons in the scaling assertion, without a wall-clock threshold.
                        assertEquals(8L * height, work.coverVisits);
                        assertTrue(work.sortComparisons > 0);
                        int logHeight = Integer.numberOfTrailingZeros(height);
                        assertTrue(work.sortComparisons <= 4L * height * logHeight);
                        long totalWork = work.coverVisits + work.sortComparisons;
                        if (previousWork > 0)
                            assertTrue(
                                    totalWork <= 6 * previousWork, "4x height must not cost 16x");
                        previousWork = totalWork;
                    }
                }
    }

    @Test
    void tallStairRunsKeepAlternatingLateralGapsAfterIntervalSorting() {
        for (int height : new int[] {256, 1024})
            for (int step : STEPS) {
                int runHeight = height / 32;
                var runs = new ArrayList<VoxelColumn.Run>();
                for (int r = 0; r < 32; r++)
                    runs.add(
                            new VoxelColumn.Run(
                                    120 + r * runHeight,
                                    120 + (r + 1) * runHeight,
                                    Blocks.STONE_STAIRS
                                            .defaultBlockState()
                                            .setValue(
                                                    BlockStateProperties.HORIZONTAL_FACING,
                                                    r % 2 == 0
                                                            ? Direction.NORTH
                                                            : Direction.SOUTH)));
                var work = new VoxelMesher.FluidWork();
                var quads =
                        measuredBoxes(
                                column(120, 120 + height, water(0)),
                                new VoxelColumn(runs),
                                step,
                                work);
                var east =
                        fluidFaces(quads, 1).stream()
                                .sorted(Comparator.comparingDouble(VoxelFluidBoxesTest::minY))
                                .toList();
                assertEquals(height, east.size());
                for (int y = 0; y < height; y++) {
                    double low = y / runHeight % 2 == 0 ? Z + step * .5 : Z;
                    sideBounds(
                            east.get(y),
                            step,
                            120.5 + y,
                            y == height - 1 ? 120 + y + 8.0 / 9 : 121 + y,
                            low,
                            low + step * .5);
                }
                // Two lateral strips at most; repeated layers must not add new strips.
                assertTrue(work.coverVisits > 0 && work.coverVisits <= 32L * height);
            }
    }

    private static List<VoxelMesher.Quad> measuredBoxes(
            VoxelColumn center, VoxelColumn neighbor, int step, VoxelMesher.FluidWork work) {
        return VoxelMesher.boxes(
                X,
                Z,
                X + step,
                Z + step,
                step,
                (x, z) -> x == X && z == Z ? center : neighbor,
                work);
    }

    @Test
    void wetPlantsKeepCutoutsAndFluidWhileTransparentOrDryNeighborsDoNotHideIt() {
        for (int step : STEPS) {
            for (var plant :
                    List.of(
                            Blocks.SEAGRASS.defaultBlockState(),
                            Blocks.KELP.defaultBlockState(),
                            Blocks.TUBE_CORAL_FAN
                                    .defaultBlockState()
                                    .setValue(BlockStateProperties.WATERLOGGED, true))) {
                var wet = column(120, 121, plant);
                var quads = boxes(wet, air(), step);
                oneSidePerAxis(quads, step, 120, 120 + 8.0 / 9);
                assertEquals(1, fluidFaces(quads, 3).size());
                assertEquals(step <= 2 ? 4 : 0, quads.stream().filter(q -> !q.volume()).count());
                assertFalse(quads.stream().anyMatch(q -> q.volume() && q.material().equals(plant)));
                assertEquals(1, boxes(column(120, 121, water(0)), wet, step).size());
            }
            for (var neighbor :
                    List.of(
                            Blocks.SHORT_GRASS.defaultBlockState(),
                            Blocks.GLASS.defaultBlockState(),
                            Blocks.LAVA.defaultBlockState()))
                oneSidePerAxis(
                        boxes(column(120, 121, water(0)), column(120, 121, neighbor), step),
                        step,
                        120,
                        120 + 8.0 / 9);
        }
    }

    @Test
    void aSolidCeilingDoesNotHideTheLoweredSurfaceBelowItsAirGap() {
        var center =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(120, 121, water(4)),
                                new VoxelColumn.Run(121, 122, Blocks.STONE.defaultBlockState())));
        for (int step : STEPS) {
            var caps = fluidFaces(boxes(center, air(), step), 3);
            assertEquals(1, caps.size());
            assertEquals(120 + 4.0 / 9, minY(caps.getFirst()), EPS);
        }
    }
}
