package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.chart.PlanetShape;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline producer contracts; no world, render frame, GL context, or game launch. */
class GroundQuadtreeDiagnosticsTest {
    private final ArrayList<long[]> frames = new ArrayList<>();
    private final Map<Field, Object> originals = new LinkedHashMap<>();
    private GroundMeshSession meshes;
    private long previousGeneration;

    @AfterEach
    void reset() throws Exception {
        for (var e : originals.entrySet()) e.getKey().set(null, e.getValue());
        if (meshes != null) meshes.generation = previousGeneration;
        FarViewDiagnostics.stop();
        RenderWorkDiagnostics.register(null);
    }

    private void capture() {
        RenderWorkDiagnostics.register(v -> frames.add(v.clone()));
        RenderWorkDiagnostics.beginFrame();
    }

    @Test
    void nodeIdentitiesDoNotAliasTileIdentitiesOrTruncateLastFace() {
        capture();
        GroundLodLifecycleDiagnostics.publication(7, 0, 4, 0, 1, false, false);
        GroundLodLifecycleDiagnostics.nodePublication(7, 0);
        GroundLodLifecycleDiagnostics.nodePublication(130943, 0);
        GroundLodLifecycleDiagnostics.nodePublication(130943, 4);
        GroundLodLifecycleDiagnostics.nodePublication(-1, 0);
        GroundLodLifecycleDiagnostics.nodePublication(130944, 0);
        RenderWorkDiagnostics.endFrame();
        assertEquals(3, frames.getFirst()[FAR_PUBLICATION_FIRST.ordinal()]);
        assertEquals(1, frames.getFirst()[FAR_PUBLICATION_REPLACEMENT.ordinal()]);
        assertEquals(1, frames.getFirst()[FAR_PUBLICATION_REPEAT_KEY.ordinal()]);
        assertEquals(0, frames.getFirst()[FAR_PUBLICATION_SAME_LOCAL_INPUTS.ordinal()]);
        capture();
        GroundLodLifecycleDiagnostics.nodePublication(130943, 0);
        RenderWorkDiagnostics.endFrame();
        assertEquals(0, frames.getLast()[FAR_PUBLICATION_REPEAT_KEY.ordinal()]);
    }

    @Test
    void unknownInputReasonsNeverBecomeSurfaceMipOrEqualityClaims() {
        capture();
        GroundLodLifecycleDiagnostics.nodeRequest(130943, 0, 64, true);
        GroundLodLifecycleDiagnostics.nodeRequest(130942, 64, 16, false);
        GroundLodLifecycleDiagnostics.nodeRequest(-1, 0, 1, true);
        GroundLodLifecycleDiagnostics.nodePublication(130943, 64);
        RenderWorkDiagnostics.endFrame();
        assertEquals(1, frames.getFirst()[FAR_REQUEST_FIRST.ordinal()]);
        assertEquals(1, frames.getFirst()[FAR_REQUEST_STEP.ordinal()]);
        assertEquals(1, frames.getFirst()[FAR_REQUEST_DIRTY.ordinal()]);
        assertEquals(0, frames.getFirst()[FAR_REQUEST_SURFACE.ordinal()]);
        assertEquals(0, frames.getFirst()[FAR_REQUEST_MIP.ordinal()]);
        assertEquals(0, frames.getFirst()[FAR_PUBLICATION_SAME_LOCAL_INPUTS.ordinal()]);
    }

    @Test
    void completedPayloadBytesRemainCpuOccupancyAndOldSessionReleasesStayIsolated() {
        var oldSession = new GroundLodLifecycleDiagnostics.ReadyBytes();
        var nextSession = new GroundLodLifecycleDiagnostics.ReadyBytes();
        oldSession.add(true, 4096);
        oldSession.add(false, 2048);
        nextSession.add(false, 512);
        capture();
        for (int i = 0; i < 3; i++) {
            RenderWorkDiagnostics.gauge(FAR_LOD_READY_COVER_BYTES, oldSession.cover());
            RenderWorkDiagnostics.gauge(FAR_LOD_READY_RESIDENT_BYTES, oldSession.detail());
        }
        oldSession.add(true, -4096);
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_COVER_BYTES, oldSession.cover());
        RenderWorkDiagnostics.endFrame();
        assertEquals(0, frames.getFirst()[FAR_LOD_READY_COVER_BYTES.ordinal()]);
        assertEquals(2048, frames.getFirst()[FAR_LOD_READY_RESIDENT_BYTES.ordinal()]);
        assertEquals(512, nextSession.detail());
        assertEquals(0, nextSession.cover());
        oldSession.add(false, -2048);
        assertEquals(512, nextSession.detail());
    }

    @Test
    void failuresAreCountedRecordedAndRateLimitedEvenWhenManyJobsFail() throws Exception {
        setupTree();
        set("jobFailures", 0L);
        set("failureLogs", 0);
        set("lastFailureLog", 0L);
        set("failedNodes", null);
        Field treeField = GroundRenderer.class.getDeclaredField("tree");
        treeField.setAccessible(true);
        var tree = (GroundQuadtree) treeField.get(null);
        int node = GroundQuadtree.root(0);
        var ticket =
                new GroundQuadtreeScheduler.Ticket(
                        node, tree.desiredStep(node), tree.version(node), 23);
        var watch = FarViewDiagnostics.watch(7, 0, 16, 16, 0);
        for (int i = 0; i < 12; i++)
            call(
                    "jobFailure",
                    new Class<?>[] {
                        GroundQuadtreeScheduler.Ticket.class, long.class, Throwable.class
                    },
                    ticket,
                    watch.epoch(),
                    new IllegalStateException("synthetic-offline-failure"));
        Field count = GroundRenderer.class.getDeclaredField("jobFailures");
        count.setAccessible(true);
        assertEquals(12L, count.get(null));
        Field logs = GroundRenderer.class.getDeclaredField("failureLogs");
        logs.setAccessible(true);
        assertEquals(1, logs.get(null));
        assertEquals(12, FarViewDiagnostics.snapshot().records().size());
        assertTrue(
                FarViewDiagnostics.snapshot().events().stream()
                        .allMatch(
                                e ->
                                        e.kind() == FarViewDiagnostics.Kind.SKIP
                                                && e.reason().contains("worker-failed")));
        Field failed = GroundRenderer.class.getDeclaredField("failedNodes");
        failed.setAccessible(true);
        assertTrue(((BitSet) failed.get(null)).get(node));
    }

    @Test
    void regionInputsExposeUnknownsAndNeverAllocateAMaskTexture() throws Exception {
        setupTree();
        var watch = FarViewDiagnostics.watch(7, 0, 16, 16, 0);
        var fields = GroundRenderer.diagnosticDrawInputs(watch);
        assertEquals(-1, fields.get("selectedNode"));
        assertEquals(-1L, ((Number) fields.get("selectedVersion")).longValue());
        assertEquals("unknown", fields.get("mipIdentity"));
        assertEquals("unknown", fields.get("arenaFreeBytes"));
        assertEquals("unknown: no read-only owner accessor", fields.get("maskTexture"));
        assertEquals("unknown", fields.get("pixelVisibility"));
    }

    private void set(String name, Object value) throws Exception {
        Field f = GroundRenderer.class.getDeclaredField(name);
        f.setAccessible(true);
        originals.putIfAbsent(f, f.get(null));
        f.set(null, value);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GroundRenderer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private void setupTree() throws Exception {
        set("tree", new GroundQuadtree(new PlanetShape(256), -256, 512));
        set("cut", new GroundQuadtree.Cut());
        set("planet", 7);
        var field = GroundRenderer.class.getDeclaredField("meshes");
        field.setAccessible(true);
        meshes = (GroundMeshSession) field.get(null);
        previousGeneration = meshes.generation;
        meshes.generation = 23L;
        FarViewDiagnostics.observeContext(7, 23, false, "test");
    }

    @Test
    void selectedDescendantIsIndependentOfRootAndDesiredPath() throws Exception {
        setupTree();
        Field t = GroundRenderer.class.getDeclaredField("tree");
        t.setAccessible(true);
        GroundQuadtree tree = (GroundQuadtree) t.get(null);
        int root = GroundQuadtree.root(0), child = GroundQuadtree.child(root, 3);
        tree.target(root, 64, true);
        tree.target(child, 16, true);
        Field c = GroundRenderer.class.getDeclaredField("cut");
        c.setAccessible(true);
        var cut = (GroundQuadtree.Cut) c.get(null);
        cut.pure[0] = child;
        cut.pureCount = 1;
        Class<?>[] tile = {int.class, int.class, int.class};
        assertEquals(child, call("selectedNode", tile, 0, 160, 160));
        assertEquals(-1, call("selectedNode", tile, 0, 0, 0));
        int desired = (int) call("desiredNode", tile, 0, 160, 160);
        assertEquals(2, GroundQuadtree.depth(desired));
        assertNotEquals(child, desired);
        assertNotEquals(root, desired);
    }

    @Test
    void absentBoundaryTilesHaveNoDesiredNodeEvenWhenTheirRootExists() throws Exception {
        setupTree();
        set("tree", new GroundQuadtree(new PlanetShape(272), -256, 512));
        Class<?>[] tile = {int.class, int.class, int.class};
        assertTrue((int) call("desiredNode", tile, 0, 256, 256) >= 0);
        assertEquals(-1, call("desiredNode", tile, 0, 272, 256));
        assertEquals(-1, call("desiredNode", tile, 0, 512, 256));
    }

    @Test
    void nativeMaskedTilesDoNotProduceFarDrawEvents() throws Exception {
        setupTree();
        var watch = FarViewDiagnostics.watch(7, 0, 16, 16, 1);
        Field f = GroundRenderer.class.getDeclaredField("nativeTiles");
        f.setAccessible(true);
        BitSet nativeTiles = (BitSet) f.get(null);
        BitSet saved = (BitSet) nativeTiles.clone();
        try {
            nativeTiles.clear();
            nativeTiles.set(GroundQuadtree.tile(0, 1, 1));
            call(
                    "nodeEvent",
                    new Class<?>[] {
                        long.class,
                        int.class,
                        int.class,
                        FarViewDiagnostics.Kind.class,
                        String.class
                    },
                    watch.epoch(),
                    GroundQuadtree.root(0),
                    64,
                    FarViewDiagnostics.Kind.DRAW,
                    "submitted");
            var events = FarViewDiagnostics.snapshot().events();
            assertEquals(8, events.size());
            assertTrue(events.stream().allMatch(e -> e.kind() == FarViewDiagnostics.Kind.DRAW));
            assertTrue(events.stream().noneMatch(e -> e.x() == 16 && e.z() == 16));
        } finally {
            nativeTiles.clear();
            nativeTiles.or(saved);
        }
    }

    @Test
    void neighborBuildEvidenceIsRecordedWithoutPretendingToContainWatchCenter() throws Exception {
        setupTree();
        var watch = FarViewDiagnostics.watch(7, 0, 16, 16, 1);
        int leaf = GroundQuadtree.nodeId(0, 4, 0, 0);
        var ticket = new GroundQuadtreeScheduler.Ticket(leaf, 1, 9, 23);
        call(
                "nodeRecord",
                new Class<?>[] {
                    long.class,
                    GroundQuadtreeScheduler.Ticket.class,
                    String.class,
                    long.class,
                    long.class
                },
                watch.epoch(),
                ticket,
                "quadtree-mesh",
                123L,
                48L);
        var records = FarViewDiagnostics.snapshot().records();
        assertEquals(1, records.size());
        assertEquals(0, records.getFirst().x());
        assertEquals(0, records.getFirst().z());
        assertEquals(leaf, records.getFirst().fields().get("node"));
        assertEquals(123L, records.getFirst().fields().get("cpuNanos"));
        FarViewDiagnostics.watch(7, 0, 16, 16, 1);
        call(
                "nodeRecord",
                new Class<?>[] {
                    long.class,
                    GroundQuadtreeScheduler.Ticket.class,
                    String.class,
                    long.class,
                    long.class
                },
                watch.epoch(),
                ticket,
                "quadtree-mesh",
                999L,
                48L);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }
}
