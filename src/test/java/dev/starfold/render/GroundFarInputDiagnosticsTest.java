package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.lod.VoxelColumns;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class GroundFarInputDiagnosticsTest {
    private static final int PLANET = 7, FACE = 2, X = 32, Z = 48;

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @BeforeEach
    void start() {
        FarViewDiagnostics.stop();
        FarViewDiagnostics.observeContext(PLANET, 1, false, "fixture");
    }

    @AfterEach
    void stop() {
        FarViewDiagnostics.stop();
    }

    private static long watch() {
        return FarViewDiagnostics.watch(PLANET, FACE, X, Z, 0).epoch();
    }

    private static GroundFarMesh.Surface surface(int count) {
        var runs = new ArrayList<VoxelColumn.Run>();
        for (int i = 0; i < count; i++)
            runs.add(new VoxelColumn.Run(i * 2, i * 2 + 1, Blocks.WATER.defaultBlockState()));
        return GroundFarMesh.Surface.voxel(new VoxelColumn(runs));
    }

    private static Map<String, Object> only() {
        var records = FarViewDiagnostics.snapshot().records();
        assertEquals(1, records.size());
        return records.getFirst().fields();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> columns(Map<String, Object> fields) {
        return (List<Map<String, Object>>) fields.get("columns");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(Map<String, Object> column) {
        return (List<Map<String, Object>>) column.get("runs");
    }

    @Test
    void disabledAndEnabledPreserveCallCountAndSurfaceAndColumnIdentity() {
        var result = surface(1);
        int[] calls = {0};
        GroundFarMesh.Field delegate =
                (f, x, z, s) -> {
                    calls[0]++;
                    return result;
                };
        var disabled = GroundFarInputDiagnostics.field(0, PLANET, FACE, X, Z, 1, delegate);
        assertSame(delegate, disabled);
        assertSame(result, disabled.cell(Face.WEST, X, Z, 1));
        GroundFarInputDiagnostics.complete(disabled);
        assertEquals(1, calls[0]);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
        var enabled = GroundFarInputDiagnostics.field(watch(), PLANET, FACE, X, Z, 1, delegate);
        assertEquals(1, calls[0]);
        var actual = enabled.cell(Face.WEST, X, Z, 1);
        assertSame(result, actual);
        assertSame(result.column(), actual.column());
        GroundFarInputDiagnostics.complete(enabled);
        GroundFarInputDiagnostics.complete(enabled);
        assertEquals(2, calls[0]);
        assertEquals("DISPLAY_OR_SIMPLIFIED_RUNS", only().get("origin"));
    }

    @Test
    void rejectsOutsideWatchWrongFacePlanetAndOldEpochWithoutTouchingImage() {
        long epoch = watch();
        GroundFarMesh.Field delegate = (f, x, z, s) -> fail("unexpected delegate call");
        assertSame(
                delegate,
                GroundFarInputDiagnostics.field(epoch, PLANET, FACE, X + 16, Z, 1, delegate));
        assertSame(
                delegate,
                GroundFarInputDiagnostics.field(epoch, PLANET + 1, FACE, X, Z, 1, delegate));
        assertSame(
                delegate,
                GroundFarInputDiagnostics.field(epoch, PLANET, FACE + 1, X, Z, 1, delegate));
        GroundFarInputDiagnostics.original(0, PLANET, FACE, X, Z, null);
        GroundFarInputDiagnostics.original(epoch, PLANET, FACE, X + 16, Z, null);
        watch();
        assertSame(
                delegate, GroundFarInputDiagnostics.field(epoch, PLANET, FACE, X, Z, 1, delegate));
        GroundFarInputDiagnostics.original(epoch, PLANET, FACE, X, Z, null);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }

    @Test
    void coarseFootprintIntersectsWatchAndCapturesOnlyMatchingFaceAndSamples() {
        var result = surface(1);
        int[] calls = {0};
        var field =
                GroundFarInputDiagnostics.field(
                        watch(),
                        PLANET,
                        FACE,
                        0,
                        0,
                        64,
                        (f, x, z, s) -> {
                            calls[0]++;
                            return result;
                        });
        assertSame(result, field.cell(Face.WEST, 0, 0, 64));
        field.cell(Face.EAST, X, Z, 1);
        field.cell(Face.WEST, X - 1, Z, 1); // exclusive edge does not intersect
        field.cell(Face.WEST, X + 16, Z, 1);
        GroundFarInputDiagnostics.complete(field);
        assertEquals(4, calls[0]);
        var fields = only();
        assertEquals(1L, fields.get("totalColumns"));
        assertEquals(64, columns(fields).getFirst().get("step"));
        assertEquals(0, columns(fields).getFirst().get("x"));
    }

    @Test
    void chartPatchCapturesWatchFarFromPatchOrigin() {
        var result = surface(1);
        var field =
                GroundFarInputDiagnostics.field(
                        watch(), PLANET, FACE, 0, 0, 16, 256, (f, x, z, step) -> result);
        field.cell(Face.WEST, X, Z, 16);
        GroundFarInputDiagnostics.complete(field);
        assertEquals(1L, only().get("totalColumns"));
        assertEquals(X, columns(only()).getFirst().get("x"));
    }

    @Test
    void lateWatchAndResetDiscardPendingCapture() {
        long epoch = watch();
        var field =
                GroundFarInputDiagnostics.field(
                        epoch, PLANET, FACE, X, Z, 1, (f, x, z, s) -> surface(1));
        field.cell(Face.WEST, X, Z, 1);
        watch();
        GroundFarInputDiagnostics.complete(field);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
        var reset =
                GroundFarInputDiagnostics.field(
                        FarViewDiagnostics.epoch(PLANET),
                        PLANET,
                        FACE,
                        X,
                        Z,
                        1,
                        (f, x, z, s) -> surface(1));
        FarViewDiagnostics.observeContext(PLANET, 2, true, "reset");
        reset.cell(Face.WEST, X, Z, 1);
        GroundFarInputDiagnostics.complete(reset);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }

    @Test
    void displayEvidenceHasBoundedPrefixTotalsAndAcceptedJson() {
        var result = surface(8);
        var field =
                GroundFarInputDiagnostics.field(
                        watch(), PLANET, FACE, X, Z, 1, (f, x, z, s) -> result);
        for (int i = 0; i < 100; i++) field.cell(Face.WEST, X + i % 16, Z, 1);
        GroundFarInputDiagnostics.complete(field);
        var fields = only();
        assertEquals(100L, fields.get("totalColumns"));
        assertEquals(800L, fields.get("totalRuns"));
        assertEquals(64, fields.get("sampledRunCount"));
        assertEquals(736L, fields.get("truncatedRuns"));
        assertTrue(columns(fields).size() <= 16);
        assertEquals(true, fields.get("truncated"));
        assertTrue(new Gson().toJson(fields).contains("DISPLAY_OR_SIMPLIFIED_RUNS"));
        assertThrows(UnsupportedOperationException.class, () -> columns(fields).clear());
    }

    @Test
    void columnCapAppliesEvenWhenRunBudgetIsNotExhaustedAndCancelFlushes() {
        var result = surface(1);
        var field =
                GroundFarInputDiagnostics.field(
                        watch(), PLANET, FACE, X, Z, 1, (f, x, z, s) -> result);
        try {
            for (int i = 0; i < 20; i++) field.cell(Face.WEST, X + i % 16, Z + i / 16, 1);
            throw new java.util.concurrent.CancellationException();
        } catch (java.util.concurrent.CancellationException expected) {
            // The caller's existing cancellation path still reaches finally.
        } finally {
            GroundFarInputDiagnostics.complete(field);
        }
        var fields = only();
        assertEquals(16, columns(fields).size());
        assertEquals(16, fields.get("sampledRunCount"));
        assertEquals(4L, fields.get("truncatedColumns"));
        assertSame(result, field.cell(Face.WEST, X, Z, 1));
        GroundFarInputDiagnostics.complete(field);
        assertEquals(20L, only().get("totalColumns"));
    }

    @Test
    void originalCopiesCapturedAirWaterAndRevisionWithGlobalRunCap() {
        long epoch = watch();
        int floor = GroundChunkSnapshot.MIN_Y, ceiling = floor + GroundChunkSnapshot.HEIGHT;
        var capture = new VoxelColumns.Capture(floor, ceiling);
        capture.step(
                (x, y, z) ->
                        y < floor + 8
                                ? ((y - floor) % 2 == 0
                                        ? Blocks.WATER.defaultBlockState()
                                        : Blocks.STONE.defaultBlockState())
                                : Blocks.AIR.defaultBlockState(),
                256 * (ceiling - floor));
        var voxels = capture.result().orElseThrow();
        int[] heights = new int[256], states = new int[256];
        Arrays.fill(heights, floor + 7);
        Arrays.fill(states, Block.getId(Blocks.STONE.defaultBlockState()));
        var image = new GroundSurface(23, heights, states, voxels);
        GroundFarInputDiagnostics.original(epoch, PLANET, FACE, X, Z, image);
        var fields = only();
        assertEquals("ORIGINAL_VOXELS", fields.get("origin"));
        assertEquals(23L, fields.get("revision"));
        assertEquals(256L, fields.get("totalColumns"));
        assertEquals(2304L, fields.get("totalRuns"));
        assertEquals(64, fields.get("sampledRunCount"));
        assertTrue(columns(fields).size() <= 16);
        var first = runs(columns(fields).getFirst());
        assertEquals(floor, first.getFirst().get("bottom"));
        assertEquals(floor + 1, first.getFirst().get("top"));
        assertTrue(first.getFirst().get("fluid").toString().contains("water"));
        assertEquals(true, first.getLast().get("air"));
        assertTrue(new Gson().toJson(fields).contains("ORIGINAL_VOXELS"));
        FarViewDiagnostics.stop();
        epoch = watch();
        GroundFarInputDiagnostics.original(
                epoch, PLANET, FACE, X, Z, new GroundSurface(24, heights, states));
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }
}
