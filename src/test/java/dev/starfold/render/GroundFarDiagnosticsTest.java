package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.render.GroundFarDiagnostics.Origin;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.lod.VoxelColumns;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GroundFarDiagnosticsTest {
    private static final double EPSILON = 1e-8;
    private static final int X = 32, Z = 48, BOTTOM = 120, TOP = 128;
    private static final double FLUID_TOP = TOP - 1.0 / 9;

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static VoxelColumn column(int bottom, int top, BlockState block) {
        return new VoxelColumn(List.of(new VoxelColumn.Run(bottom, top, block)));
    }

    private static VoxelColumn water() {
        return column(BOTTOM, TOP, Blocks.WATER.defaultBlockState());
    }

    private static VoxelColumn air() {
        return new VoxelColumn(List.of());
    }

    private static List<VoxelMesher.Quad> boxes(VoxelColumn center, VoxelColumn neighbor) {
        return VoxelMesher.boxes(
                X, Z, X + 1, Z + 1, 1, (x, z) -> x == X && z == Z ? center : neighbor);
    }

    private static double plane(int axis) {
        return switch (axis) {
            case 0 -> X;
            case 1 -> X + 1;
            case 4 -> Z;
            case 5 -> Z + 1;
            default -> throw new IllegalArgumentException("Horizontal axis required");
        };
    }

    private static double lateral(int axis) {
        return axis < 2 ? Z : X;
    }

    private static List<GroundFarDiagnostics.FluidFinding> findings(
            VoxelColumn center,
            VoxelColumn neighbor,
            Origin origin,
            int axis,
            List<VoxelMesher.Quad> generated) {
        return GroundFarDiagnostics.missingFluidSides(
                center,
                neighbor,
                origin,
                axis,
                plane(axis),
                lateral(axis),
                lateral(axis) + 1,
                generated);
    }

    private static GroundFarDiagnostics.FluidFinding onlyInterval(
            List<GroundFarDiagnostics.FluidFinding> findings,
            Origin origin,
            int axis,
            double bottom,
            double top) {
        assertEquals(1, findings.size(), findings.toString());
        var finding = findings.getFirst();
        assertEquals(origin, finding.origin());
        assertEquals(axis, finding.axis());
        assertEquals(bottom, finding.bottom(), EPSILON);
        assertEquals(top, finding.top(), EPSILON);
        assertTrue(finding.material().contains("water"), finding.material());
        assertFalse(finding.neighborKind().isBlank());
        assertFalse(finding.reason().isBlank());
        return finding;
    }

    @Test
    void repairedBoxesCoverAirSidesAndRemovingThemReportsAbsoluteIntervals() {
        var center = water();
        var generated = boxes(center, air());
        assertFalse(generated.isEmpty());
        assertEquals(5, generated.size());
        var topsOnly = generated.stream().filter(q -> q.axis() == 3).toList();
        for (int axis : new int[] {0, 1, 4, 5}) {
            assertTrue(findings(center, air(), Origin.DISPLAY_RUNS, axis, generated).isEmpty());
            onlyInterval(
                    findings(center, air(), Origin.DISPLAY_RUNS, axis, topsOnly),
                    Origin.DISPLAY_RUNS,
                    axis,
                    BOTTOM,
                    FLUID_TOP);
        }
    }

    @Test
    void equalHeightSurroundedWaterNeedsNoSideFinding() {
        var center = water();
        var generated = boxes(center, center);
        for (int axis : new int[] {0, 1, 4, 5})
            assertTrue(
                    findings(center, center, Origin.DISPLAY_RUNS, axis, generated).isEmpty(),
                    "same-height water occludes axis " + axis);
    }

    @Test
    void lowerWaterNeighborExposesOnlyAboveItsFluidLevel() {
        var center = water();
        var neighbor = column(BOTTOM, 124, Blocks.WATER.defaultBlockState());
        var generated = boxes(center, neighbor);
        for (int axis : new int[] {0, 1, 4, 5}) {
            assertTrue(findings(center, neighbor, Origin.DISPLAY_RUNS, axis, generated).isEmpty());
            onlyInterval(
                    findings(center, neighbor, Origin.DISPLAY_RUNS, axis, List.of()),
                    Origin.DISPLAY_RUNS,
                    axis,
                    124 - 1.0 / 9,
                    FLUID_TOP);
        }
    }

    @Test
    void solidNeighborWallOccludesTheEntireFluidSide() {
        var center = water();
        var neighbor = column(BOTTOM - 4, TOP + 4, Blocks.STONE.defaultBlockState());
        var generated = boxes(center, neighbor);
        for (int axis : new int[] {0, 1, 4, 5})
            assertTrue(findings(center, neighbor, Origin.DISPLAY_RUNS, axis, generated).isEmpty());
    }

    @Test
    void separatedSolidNeighborRunsLeaveOnlyTheirAirGapMissing() {
        var center = water();
        var stone = Blocks.STONE.defaultBlockState();
        var neighbor =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(BOTTOM, 122, stone),
                                new VoxelColumn.Run(125, TOP, stone)));
        assertTrue(
                findings(center, neighbor, Origin.DISPLAY_RUNS, 1, boxes(center, neighbor))
                        .isEmpty());
        onlyInterval(
                findings(center, neighbor, Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                122,
                125);
    }

    private static VoxelMesher.Quad side(
            BlockState material,
            int axis,
            double plane,
            double low,
            double high,
            double bottom,
            double top) {
        List<Point> points =
                axis < 2
                        ? List.of(
                                new Point(plane, bottom, low),
                                new Point(plane, bottom, high),
                                new Point(plane, top, high),
                                new Point(plane, top, low))
                        : List.of(
                                new Point(low, bottom, plane),
                                new Point(high, bottom, plane),
                                new Point(high, top, plane),
                                new Point(low, top, plane));
        // Match the outward winding used by raw boxes for every horizontal orientation.
        if (axis == 1 || axis == 4) points = points.reversed();
        return new VoxelMesher.Quad(material, axis, points);
    }

    @Test
    void completeSyntheticSidesSuppressFindingsOnEveryHorizontalAxis() {
        var center = water();
        for (int axis : new int[] {0, 1, 4, 5}) {
            var generated = new ArrayList<VoxelMesher.Quad>();
            generated.add(
                    side(
                            Blocks.WATER.defaultBlockState(),
                            axis,
                            plane(axis),
                            lateral(axis),
                            lateral(axis) + 1,
                            BOTTOM,
                            FLUID_TOP));
            assertTrue(
                    findings(center, air(), Origin.DISPLAY_RUNS, axis, generated).isEmpty(),
                    "complete side covers axis " + axis);
        }
    }

    @Test
    void partialVerticalSideCoverageReportsOnlyTheMissingInterval() {
        var center = water();
        var generated =
                List.of(side(Blocks.WATER.defaultBlockState(), 1, plane(1), Z, Z + 1, BOTTOM, 124));
        onlyInterval(
                findings(center, air(), Origin.DISPLAY_RUNS, 1, generated),
                Origin.DISPLAY_RUNS,
                1,
                124,
                FLUID_TOP);
    }

    @Test
    void adjacentPartialQuadsTogetherCoverACompleteSide() {
        var center = water();
        var material = Blocks.WATER.defaultBlockState();
        var generated =
                List.of(
                        side(material, 1, plane(1), Z, Z + .5, BOTTOM, FLUID_TOP),
                        side(material, 1, plane(1), Z + .5, Z + 1, BOTTOM, FLUID_TOP));
        assertTrue(findings(center, air(), Origin.DISPLAY_RUNS, 1, generated).isEmpty());
    }

    @Test
    void reductionCanHideAnOriginalLowerNeighborAndKeepsItsProvenance() {
        var originalNeighbor = column(BOTTOM, 124, Blocks.WATER.defaultBlockState());
        var simplifiedNeighbor = VoxelColumn.reduce(List.of(originalNeighbor, water()), 2);
        var original =
                GroundFarDiagnostics.snapshotColumn(
                        originalNeighbor, Origin.DISPLAY_RUNS, 2, X, Z, 41);
        var simplified =
                GroundFarDiagnostics.snapshotColumn(
                        simplifiedNeighbor, Origin.SIMPLIFIED_RUNS, 2, X, Z, 42);
        assertEquals(124, original.runs().getLast().top());
        assertEquals(TOP, simplified.runs().getLast().top());
        assertNotEquals(original.origin(), simplified.origin());
        onlyInterval(
                findings(water(), originalNeighbor, Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                124 - 1.0 / 9,
                FLUID_TOP);
        assertTrue(
                findings(
                                water(),
                                simplifiedNeighbor,
                                Origin.SIMPLIFIED_RUNS,
                                1,
                                boxes(water(), simplifiedNeighbor))
                        .isEmpty());
    }

    @Test
    void unrelatedMaterialPlaneOrientationOrLateralSpanCannotHideMissingSide() {
        var center = water();
        var water = Blocks.WATER.defaultBlockState();
        var unrelated =
                List.of(
                        side(
                                Blocks.LAVA.defaultBlockState(),
                                1,
                                plane(1),
                                Z,
                                Z + 1,
                                BOTTOM,
                                FLUID_TOP),
                        side(water, 1, plane(1) + 1, Z, Z + 1, BOTTOM, FLUID_TOP),
                        side(water, 0, plane(1), Z, Z + 1, BOTTOM, FLUID_TOP),
                        side(water, 1, plane(1), Z + 1, Z + 2, BOTTOM, FLUID_TOP),
                        side(water, 1, plane(1), Z, Z + .5, BOTTOM, FLUID_TOP));
        for (var quad : unrelated)
            onlyInterval(
                    findings(center, air(), Origin.DISPLAY_RUNS, 1, List.of(quad)),
                    Origin.DISPLAY_RUNS,
                    1,
                    BOTTOM,
                    FLUID_TOP);
    }

    @Test
    void simplifiedFindingsKeepTheirOriginAndOriginalVoxelsAreRejected() {
        var center = water();
        List<VoxelMesher.Quad> generated = List.of();
        onlyInterval(
                findings(center, air(), Origin.SIMPLIFIED_RUNS, 1, generated),
                Origin.SIMPLIFIED_RUNS,
                1,
                BOTTOM,
                FLUID_TOP);
        assertThrows(
                IllegalArgumentException.class,
                () -> findings(center, air(), Origin.ORIGINAL_VOXELS, 1, generated));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GroundFarDiagnostics.snapshotColumn(
                                center, Origin.ORIGINAL_VOXELS, 2, X, Z, 17));
    }

    @Test
    void adjacentDifferentWaterStatesDoNotInventAnInternalNinthBlockGap() {
        var neighbor =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(
                                        120,
                                        124,
                                        Blocks.WATER
                                                .defaultBlockState()
                                                .setValue(LiquidBlock.LEVEL, 4)),
                                new VoxelColumn.Run(124, 128, Blocks.WATER.defaultBlockState())));
        // The old oracle incorrectly lowered the first run to 123+8/9 (or to 123+4/9
        // when using state height without continuity). All water below 127+8/9 is occupied.
        for (int axis : new int[] {0, 1, 4, 5})
            assertTrue(findings(water(), neighbor, Origin.DISPLAY_RUNS, axis, List.of()).isEmpty());
    }

    @Test
    void realAirBreakBetweenSameFluidRunsStillHasAnExposedGap() {
        var neighbor =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(120, 124, Blocks.WATER.defaultBlockState()),
                                new VoxelColumn.Run(125, 128, Blocks.WATER.defaultBlockState())));
        onlyInterval(
                findings(water(), neighbor, Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                123 + 8.0 / 9,
                125);
        assertTrue(
                findings(water(), neighbor, Origin.DISPLAY_RUNS, 1, boxes(water(), neighbor))
                        .isEmpty());
    }

    @Test
    void representativeEdgesUseTheFullStepWidthOnAllFourAxes() {
        for (int step : new int[] {1, 2, 16}) {
            var generated =
                    VoxelMesher.boxes(
                            X,
                            Z,
                            X + step,
                            Z + step,
                            step,
                            (x, z) -> x == X && z == Z ? water() : air());
            for (int axis : new int[] {0, 1, 4, 5}) {
                double plane = axis == 0 ? X : axis == 1 ? X + step : axis == 4 ? Z : Z + step;
                double low = lateral(axis);
                var missing =
                        GroundFarDiagnostics.fluidSideReport(
                                water(),
                                air(),
                                Origin.DISPLAY_RUNS,
                                axis,
                                plane,
                                low,
                                low + step,
                                List.of());
                var gap =
                        onlyInterval(
                                missing.findings(), Origin.DISPLAY_RUNS, axis, 120, 127 + 8.0 / 9);
                assertEquals(plane, gap.plane());
                assertEquals(low, gap.lateralLow());
                assertEquals(low + step, gap.lateralHigh());
                assertTrue(
                        GroundFarDiagnostics.fluidSideReport(
                                        water(),
                                        air(),
                                        Origin.DISPLAY_RUNS,
                                        axis,
                                        plane,
                                        low,
                                        low + step,
                                        generated)
                                .findings()
                                .isEmpty());
            }
        }
    }

    @Test
    void wetPlantsUseWaterMaterialAndDryPlantsDoNotOcclude() {
        var wet = column(120, 128, Blocks.SEAGRASS.defaultBlockState());
        var dry = column(120, 128, Blocks.SHORT_GRASS.defaultBlockState());
        for (int axis : new int[] {0, 1, 4, 5}) {
            onlyInterval(
                    findings(wet, dry, Origin.DISPLAY_RUNS, axis, List.of()),
                    Origin.DISPLAY_RUNS,
                    axis,
                    120,
                    127 + 8.0 / 9);
            assertTrue(findings(wet, dry, Origin.DISPLAY_RUNS, axis, boxes(wet, dry)).isEmpty());
            assertTrue(findings(water(), wet, Origin.DISPLAY_RUNS, axis, List.of()).isEmpty());
        }
    }

    @Test
    void stateHeightsAndPartialOpaqueShapesHaveLiteralExpectedIntervals() {
        var source = column(120, 121, Blocks.WATER.defaultBlockState());
        var flowing =
                column(120, 121, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4));
        var falling =
                column(120, 121, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8));
        onlyInterval(
                findings(flowing, air(), Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                120,
                120 + 4.0 / 9);
        onlyInterval(
                findings(falling, air(), Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                120,
                120 + 8.0 / 9);
        var slab =
                column(
                        120,
                        121,
                        Blocks.STONE_SLAB
                                .defaultBlockState()
                                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        onlyInterval(
                findings(source, slab, Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                120.5,
                120 + 8.0 / 9);
        var lava = column(120, 121, Blocks.LAVA.defaultBlockState());
        onlyInterval(
                findings(source, lava, Origin.DISPLAY_RUNS, 1, List.of()),
                Origin.DISPLAY_RUNS,
                1,
                120,
                120 + 8.0 / 9);
    }

    @Test
    void missingLateralLocationAndBadWindingAreRetained() {
        for (int axis : new int[] {0, 1, 4, 5}) {
            double low = lateral(axis);
            var half =
                    side(
                            Blocks.WATER.defaultBlockState(),
                            axis,
                            plane(axis),
                            low,
                            low + .5,
                            BOTTOM,
                            FLUID_TOP);
            var gap =
                    onlyInterval(
                            findings(water(), air(), Origin.DISPLAY_RUNS, axis, List.of(half)),
                            Origin.DISPLAY_RUNS,
                            axis,
                            BOTTOM,
                            FLUID_TOP);
            assertEquals(plane(axis), gap.plane());
            assertEquals(low + .5, gap.lateralLow());
            assertEquals(low + 1, gap.lateralHigh());
            var full =
                    side(
                            Blocks.WATER.defaultBlockState(),
                            axis,
                            plane(axis),
                            low,
                            low + 1,
                            BOTTOM,
                            FLUID_TOP);
            var reversed = new VoxelMesher.Quad(full.material(), axis, full.points().reversed());
            onlyInterval(
                    findings(water(), air(), Origin.DISPLAY_RUNS, axis, List.of(reversed)),
                    Origin.DISPLAY_RUNS,
                    axis,
                    BOTTOM,
                    FLUID_TOP);
        }
    }

    @Test
    void boundedReportCountsOmittedLateralGapsWithoutIncreasingSampleBudget() {
        var stripes = new ArrayList<VoxelMesher.Quad>();
        // 65 disjoint covered strips leave exactly 66 uncovered strips on one side.
        for (int i = 0; i < 65; i++)
            stripes.add(
                    side(
                            Blocks.WATER.defaultBlockState(),
                            1,
                            plane(1),
                            Z + (2 * i + 1) / 131.0,
                            Z + (2 * i + 2) / 131.0,
                            BOTTOM,
                            FLUID_TOP));
        var report =
                GroundFarDiagnostics.fluidSideReport(
                        water(), air(), Origin.DISPLAY_RUNS, 1, plane(1), Z, Z + 1, stripes);
        assertEquals(64, report.findings().size());
        assertEquals(2, report.omitted());
        assertTrue(report.truncated());
        assertThrows(UnsupportedOperationException.class, () -> report.findings().clear());
        var complete =
                GroundFarDiagnostics.fluidSideReport(
                        water(),
                        air(),
                        Origin.DISPLAY_RUNS,
                        1,
                        plane(1),
                        Z,
                        Z + 1,
                        boxes(water(), air()));
        assertEquals(0, complete.omitted());
        assertFalse(complete.truncated());
        assertTrue(complete.findings().isEmpty());
    }

    @Test
    void displayAndSimplifiedColumnSnapshotsRetainMetadataAndImmutableRuns() {
        var center = water();
        for (var origin : List.of(Origin.DISPLAY_RUNS, Origin.SIMPLIFIED_RUNS)) {
            var snapshot = GroundFarDiagnostics.snapshotColumn(center, origin, 2, X, Z, 17);
            assertEquals(origin, snapshot.origin());
            assertEquals(2, snapshot.face());
            assertEquals(X, snapshot.x());
            assertEquals(Z, snapshot.z());
            assertEquals(17, snapshot.revision());
            assertEquals(1, snapshot.runs().size());
            var run = snapshot.runs().getFirst();
            assertEquals(BOTTOM, run.bottom());
            assertEquals(TOP, run.top());
            assertTrue(run.block().contains("water"));
            assertTrue(run.fluid().contains("water"));
            assertFalse(run.air());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.runs().clear());
        }
    }

    private static VoxelColumns captured(int height) {
        var capture = new VoxelColumns.Capture(BOTTOM, BOTTOM + height);
        capture.step(
                (x, y, z) ->
                        (y - BOTTOM) % 2 == 0
                                ? Blocks.WATER.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(),
                VoxelColumns.COLUMNS * height);
        assertTrue(capture.done());
        return capture.result().orElseThrow();
    }

    @Test
    void rawSnapshotRetainsOriginalOriginAirAndImmutableColumnLists() {
        var snapshot = GroundFarDiagnostics.snapshotVoxels(captured(2), 2, X, Z, 23);
        assertEquals(Origin.ORIGINAL_VOXELS, snapshot.origin());
        assertEquals(2, snapshot.face());
        assertEquals(X, snapshot.x());
        assertEquals(Z, snapshot.z());
        assertEquals(23, snapshot.revision());
        assertEquals(512, snapshot.totalRuns());
        assertEquals(256, snapshot.columns().size());
        assertFalse(snapshot.truncated());
        for (var column : snapshot.columns()) {
            assertEquals(Origin.ORIGINAL_VOXELS, column.origin());
            assertEquals(2, column.face());
            assertEquals(23, column.revision());
            assertTrue(column.x() >= X && column.x() < X + 16);
            assertTrue(column.z() >= Z && column.z() < Z + 16);
            assertEquals(2, column.runs().size());
            assertEquals(BOTTOM, column.runs().getFirst().bottom());
            assertEquals(BOTTOM + 1, column.runs().getFirst().top());
            assertFalse(column.runs().getFirst().air());
            assertTrue(column.runs().getLast().air());
            assertTrue(column.runs().getLast().block().contains("air"));
            assertThrows(UnsupportedOperationException.class, () -> column.runs().clear());
        }
        assertThrows(UnsupportedOperationException.class, () -> snapshot.columns().clear());
    }

    @Test
    void rawSnapshotTruncatesAtTheRunBudget() {
        var snapshot = GroundFarDiagnostics.snapshotVoxels(captured(5), 2, X, Z, 23);
        assertTrue(snapshot.truncated());
        int stored = snapshot.columns().stream().mapToInt(c -> c.runs().size()).sum();
        assertEquals(1024, stored);
        assertTrue(snapshot.columns().size() <= VoxelColumns.COLUMNS);
    }

    @Test
    void snapshotEntryPointsRejectNullCapturedDataAndOrigin() {
        assertThrows(
                RuntimeException.class,
                () -> GroundFarDiagnostics.snapshotVoxels(null, 2, X, Z, 23));
        assertThrows(
                RuntimeException.class,
                () -> GroundFarDiagnostics.snapshotColumn(null, Origin.DISPLAY_RUNS, 2, X, Z, 23));
        assertThrows(
                RuntimeException.class,
                () -> GroundFarDiagnostics.snapshotColumn(water(), null, 2, X, Z, 23));
    }
}
