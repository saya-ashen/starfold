package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;
import static dev.starfold.render.GroundMeshVertices.*;
import static dev.starfold.render.GroundQuadtreeDiagnostics.containsTile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.api.FarViewDiagnostics.*;
import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.Anomaly;
import dev.starfold.space.RenderFrame;
import dev.starfold.storage.ChunkKeys;
import dev.starfold.worldgen.lod.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.util.*;

/** Single quadtree pipeline. Workers own CPU payloads; this class owns render-thread state. */
public final class GroundRenderer {
    private static final int[] gpuQueries = new int[4];
    private static final boolean[] gpuPending = new boolean[4];
    private static int gpuCursor;

    /** Never block the render thread waiting for a probe query. */
    private static int beginGpuProbe() {
        if (!probeTiming) return -1;
        int index = gpuCursor++ & 3;
        if (gpuQueries[index] == 0) gpuQueries[index] = GL15.glGenQueries();
        if (gpuPending[index]) {
            if (GL15.glGetQueryObjecti(gpuQueries[index], GL15.GL_QUERY_RESULT_AVAILABLE) == 0)
                return -1;
            probeGpuNanos += GL33.glGetQueryObjectui64(gpuQueries[index], GL15.GL_QUERY_RESULT);
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, gpuQueries[index]);
        gpuPending[index] = true;
        return index;
    }

    static final int LOD_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 3, 1, 6);
    static final int MAX_PENDING_UPLOADS = Math.max(16, 4 * LOD_THREADS);
    public static boolean probeOff, probeTiming;
    public static long probeCpuNanos, probeGpuNanos, probeMultiNanos, probeSkyNanos, probePrepNanos;
    public static int probeDraws, probeTiles, probeSubDraws, probeGroups, probeCompactGroups;
    private static double lodFov = 70;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static int planet = -1;
    private static PlanetShape shape;
    private static GroundSpace.Config mask;
    private static boolean selectionActive;
    private static long frameWork, demandObservedFrame = -1;
    private static int demandAfterAdmission;
    private static int uploads;
    private static Set<Long> window = Set.of();
    private static final GroundNativeCoverage.FinalWindow finalNativeCoverage =
            new GroundNativeCoverage.FinalWindow();
    private static final int[] realCorners = new int[6];
    private static final MaskMesh[] masks = new MaskMesh[12];
    private static final boolean[] maskAttempted = new boolean[12];
    private static final GroundMeshSession meshes = new GroundMeshSession(LOD_THREADS);
    private static GroundQuadtree tree;
    private static final GroundMeshCache meshCache = new GroundMeshCache(256L << 20);
    private static final GroundPackedCache packedCache = new GroundPackedCache(256L << 20);
    private static GroundQuadtreeCuller.Geometry cullGeometry = new GroundQuadtreeCuller.Geometry();
    private static GroundQuadtree.Cut cut;
    private record SubmittedNode(int handle, Bundle bundle, boolean masked, int minY, int maxY) {}

    private static final class Selection {
        GroundQuadtree.Cut cut;
        long treeRevision = -1, viewEpoch = -1, maskRevision = -1;
        final BitSet nativeTiles = new BitSet(98304);
        final SubmittedNode[] submitted = new SubmittedNode[GroundQuadtree.NODE_COUNT];
        final BitSet selected = new BitSet(GroundQuadtree.NODE_COUNT);
        final BitSet removed = new BitSet(GroundQuadtree.NODE_COUNT);
        final GroundFaceArenas.Slot[] maskSlots = new GroundFaceArenas.Slot[masks.length];
        final boolean translucent;

        Selection(boolean translucent) {
            this.translucent = translucent;
        }

        void updateDraws(boolean viewChanged) {
            removed.clear();
            removed.or(selected);
            for (int i = 0; i < cut.pureCount; i++) updateDraw(cut.pure[i], false, viewChanged);
            for (int i = 0; i < cut.maskedCount; i++) updateDraw(cut.masked[i], true, viewChanged);
            for (int node = removed.nextSetBit(0); node >= 0; node = removed.nextSetBit(node + 1)) {
                removeDraw(submitted[node]);
                submitted[node] = null;
                selected.clear(node);
            }
        }

        private void updateDraw(int node, boolean masked, boolean viewChanged) {
            int handle = tree.meshHandle(node);
            int minY = tree.targetMinY(node), maxY = tree.targetMaxY(node);
            var previous = submitted[node];
            boolean sameSlots = previous != null && previous.handle == handle && previous.masked == masked;
            if (sameSlots && !viewChanged && previous.minY == minY && previous.maxY == maxY) {
                removed.clear(node);
                return;
            }
            var bundle = previous != null && previous.handle == handle ? previous.bundle : meshes.bundles.get(handle);
            if (bundle == null) return;
            if (!sameSlots && previous != null) removeDraw(previous);
            if (!bundle.slots.isEmpty()) {
                double distance = distance(node);
                int order = GroundQuadtree.drawOrder(node);
                for (var slot : bundle.slots) {
                    if ((slot.layer == GroundFaceArenas.LAYERS - 1) != translucent) continue;
                    if (meshes.arenas.putDraw(slot, distance, masked, order))
                        RenderWorkDiagnostics.add(FAR_CHART_RANGES_BUILT, 1);
                }
            }
            if (!sameSlots || previous.minY != minY || previous.maxY != maxY)
                submitted[node] = new SubmittedNode(handle, bundle, masked, minY, maxY);
            selected.set(node);
            removed.clear(node);
        }

        private void removeDraw(SubmittedNode previous) {
            for (var slot : previous.bundle.slots)
                if ((slot.layer == GroundFaceArenas.LAYERS - 1) == translucent)
                    meshes.arenas.removeDraw(slot, previous.masked);
        }

        void updateMasks() {
            for (int i = 0; i < masks.length; i++) {
                var mask = masks[i];
                var next = mask == null || mask.compact == null ? null : mask.compact.slot();
                if (next == maskSlots[i]) continue;
                if (maskSlots[i] != null) meshes.arenas.removeDraw(maskSlots[i], false);
                if (next != null) meshes.arenas.putDraw(next, 0, false, GroundQuadtree.NODE_COUNT + i);
                maskSlots[i] = next;
            }
        }
    }
    private static final Selection[] selections = {new Selection(false), new Selection(true)};
    private static long maskRevision;
    private static volatile GroundQuadtreeCuller[] cullers;
    private static GroundQuadtreeCuller[] anchorCullers;
    private static boolean cullWindow, surfaceCullWindow, movementCullWindow;
    private static final int MIN_TERRAIN_Y = GroundSurface.LOWEST - 128 - 4;
    private static final int MAX_TERRAIN_Y = GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT - 128;
    private static final GroundQuadtreeCuller.Evaluation[] nodeViews =
            new GroundQuadtreeCuller.Evaluation[GroundQuadtree.NODE_COUNT];
    private static final GroundQuadtreeCuller.Bounds[] nodeBounds =
            new GroundQuadtreeCuller.Bounds[GroundQuadtree.NODE_COUNT];
    private static final long[] nodeViewEpoch = new long[GroundQuadtree.NODE_COUNT];
    private static final int[] nodeViewMinY = new int[GroundQuadtree.NODE_COUNT];
    private static final int[] nodeViewMaxY = new int[GroundQuadtree.NODE_COUNT];
    private static long cullEpoch, exactViewEpoch;
    private static long targetsEpoch = -1;
    private static int targetsHeight;
    private static double targetsFov;
    private static final BitSet targetsNear = new BitSet(98304);
    private static boolean noDemand, sawDemand;
    private static long recoveryView;
    private static long recoveryCheckedView = Long.MIN_VALUE, recoveryCheckedRevision = Long.MIN_VALUE;
    private static float recoveryYaw, recoveryPitch;
    private static net.minecraft.world.phys.Vec3 recoveryPosition;

    static final class NodeMesh implements AutoCloseable {
        final GroundQuadtreeMesher.Built built;
        final GroundLodPacking.Geometry geometry;
        final GroundPackedCache.Key packedKey;
        final GroundPackedCache.Entry cachedPacked;
        final GroundLodPacking.Result restored;
        final java.util.concurrent.atomic.AtomicLong packingOwner;
        final Map<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run> plantSamples;
        final GroundMeshContext.Sky sky;
        final long buildNanos, watchEpoch, overlayVersion;
        final GroundLodLifecycleDiagnostics.ReadyBytes owner;
        final boolean root;
        private boolean closed;

        NodeMesh(
                GroundQuadtreeMesher.Built built,
                GroundLodPacking.Geometry geometry,
                GroundPackedCache.Key packedKey,
                GroundPackedCache.Entry cachedPacked,
                java.util.concurrent.atomic.AtomicLong packingOwner,
                Map<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run> plantSamples,
                GroundMeshContext.Sky sky,
                long buildNanos,
                long watchEpoch,
                long overlayVersion,
                GroundLodLifecycleDiagnostics.ReadyBytes owner,
                boolean root) {
            this.built = built;
            this.geometry = geometry;
            this.packedKey = packedKey;
            this.cachedPacked = cachedPacked;
            this.restored = cachedPacked == null ? null : packedCache.restore(cachedPacked, packingOwner);
            this.packingOwner = packingOwner;
            if (geometry != null) packingOwner.addAndGet(geometry.bytes());
            this.plantSamples = plantSamples;
            this.sky = sky;
            this.buildNanos = buildNanos;
            this.watchEpoch = watchEpoch;
            this.overlayVersion = overlayVersion;
            this.owner = owner;
            this.root = root;
            owner.add(root, built.bytes());
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.add(root, -built.bytes());
            if (geometry != null) packingOwner.addAndGet(-geometry.bytes());
            if (restored != null) restored.close();
        }
    }

    record Bundle(
            GroundQuadtreeScheduler.Ticket ticket,
            long overlayVersion,
            List<GroundFaceArenas.Slot> slots)
            implements AutoCloseable {
        public void close() {
            slots.forEach(GroundFaceArenas.Slot::close);
        }

        long bytes() {
            return slots.stream().mapToLong(GroundFaceArenas.Slot::bytes).sum();
        }
    }

    private static void cancelObsoleteBuilds() {
        meshes.buildPermits.forEach((ticket, permit) -> {
            if (!current(ticket)) permit.set(false);
        });
    }
    private static BitSet failedNodes;
    private static long jobFailures, lastFailureLog;
    private static int failureLogs;
    private static boolean translucentPass, selectingCut;

    private static final class FailureLog {
        static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundRenderer.class);
    }

    private static final BitSet nativeTiles = new BitSet(98304), nearTiles = new BitSet(98304);
    private static final BitSet dirtyTiles = new BitSet(98304);

    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap sliceLights =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final Map<BlockState, ModelFace[]> modelFaces = new IdentityHashMap<>();

    static final class Pending implements AutoCloseable {
        final GroundQuadtreeScheduler.Ticket ticket;
        final NodeMesh mesh;
        final List<GroundFaceArenas.Slot> slots = new ArrayList<>();
        Iterator<Map.Entry<Integer, java.nio.ByteBuffer>> layers;
        GroundFaceArenas.Upload upload;
        int resolveCursor;
        boolean storageChecked;
        int[] layerVertices;
        final GroundLodBuildTrace trace;
        final GroundMeshContext context;
        final GroundLodPacking packing;
        GroundLodPacking.Result packed;
        boolean submitted;
        final GroundPackedCache.LightRefresh lightRefresh = new GroundPackedCache.LightRefresh();

        private void cachePacked(GroundLodPacking.Result result) {
            if (mesh.packedKey != null && context.current())
                packedCache.put(mesh.packedKey, GroundPackedCache.capture(result, mesh.geometry, packing.appearances, mesh.sky));
        }

        boolean resolve(GroundUploadBudget budget, long captureDeadline) {
            if (mesh.cachedPacked != null) {
                long light = RenderWorkDiagnostics.start();
                boolean refreshed;
                try {
                    refreshed = lightRefresh.step(mesh.cachedPacked, packed, budget,
                            GroundRenderer::loadedLightColumn, GroundRenderer::loadedSurfaceLight);
                } finally { RenderWorkDiagnostics.finish(FAR_LOD_LIGHT_NS, light); }
                if (!refreshed || !remap(budget)) return false;
                layers = packed.layers.entrySet().iterator();
                return true;
            }
            if (resolveCursor < mesh.built.bytes()) {
                budget.limit(captureDeadline);
                sliceLights.clear();
                sliceLights.defaultReturnValue(-1);
                int face = GroundQuadtree.face(ticket.node());
                try {
                    while (resolveCursor < mesh.built.bytes() && budget.cpuAvailable()) {
                        int q = resolveCursor / 48;
                        var quad = mesh.geometry.quads().get(q);
                        packing.appearances[q] = captureAppearance(face, quad, context);
                        for (int corner = 0; corner < 4; corner++) {
                            var pos = quad.lights().get(corner);
                            long key = pos.asLong();
                            int light = sliceLights.get(key);
                            if (light == -1) {
                                light = surfaceLight(face, pos, mesh.sky);
                                sliceLights.put(key, light);
                            }
                            packing.lights[q * 4 + corner] = light;
                        }
                        resolveCursor += 48;
                    }
                } finally {
                    budget.limit(budget.deadline());
                }
            }
            if (resolveCursor != mesh.built.bytes()) return false;
            if (!submitted) {
                var task = packing;
                var capturedTicket = ticket;
                submitted = meshes.scheduler.finish(() -> {
                    var event = GroundLodBuildTrace.start(capturedTicket, "packing-worker");
                    try {
                        task.run();
                    } finally {
                        String outcome = task.outcome();
                        GroundLodBuildTrace.finish(event, outcome,
                                outcome.equals("finished") ? task.geometry.built().bytes() : 0);
                    }
                });
                return false;
            }
            if (!packing.done()) return false;
            if (packed == null) packed = packing.take();
            if (!remap(budget)) return false;
            layers = packed.layers.entrySet().iterator();
            return true;
        }

        private boolean remap(GroundUploadBudget budget) {
            long started = RenderWorkDiagnostics.start();
            try { return packed.remap(budget, GroundCompact::registerMaterial); }
            finally { RenderWorkDiagnostics.finish(FAR_LOD_REMAP_NS, started); }
        }

        Pending(GroundQuadtreeScheduler.Ticket ticket, NodeMesh mesh) {
            this.ticket = ticket;
            this.mesh = mesh;
            this.context = GroundMeshContext.capture(mesh.plantSamples);
            this.packing = mesh.cachedPacked == null
                    ? new GroundLodPacking(mesh.geometry, mesh.packingOwner, this::cachePacked) : null;
            this.packed = mesh.restored;
            trace = GroundLodBuildTrace.start(ticket, "staging");
            if (trace != null) trace.outcome = "abandoned";
        }

        public void close() {
            GroundLodBuildTrace.finish(trace, trace == null ? "" : trace.outcome, mesh.built.bytes());
            if (upload != null) upload.close();
            slots.forEach(GroundFaceArenas.Slot::close);
            if (packing != null) packing.close();
            if (packed != null) packed.close();
            mesh.close();
        }
    }

    private static RenderType materialLayer(net.minecraft.world.level.block.state.BlockState state) {
        var fluid = state.getFluidState();
        var handler = fluid.isEmpty() ? null
                : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid.getType());
        return handler == null || handler == net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.DEFAULT
                ? ItemBlockRenderTypes.getChunkRenderType(state) : ItemBlockRenderTypes.getRenderLayer(fluid);
    }

    private static boolean current(GroundQuadtreeScheduler.Ticket t) {
        return tree != null
                && t.session() == meshes.generation
                && tree.valid(t.node())
                && tree.version(t.node()) == t.version()
                && tree.desiredStep(t.node()) == t.step();
    }

    private static void release(int handle) {
        var bundle = meshes.bundles.remove(handle);
        if (bundle != null) {
            nodeEvent(diagnosticEpoch(), bundle.ticket, Kind.RETIRE, "released-generation");
            bundle.close();
        }
    }

    private static GroundQuadtreeCuller.Bounds bounds(int n) {
        var previous = nodeBounds[n];
        if (previous != null && previous.minY() == tree.targetMinY(n)
                && previous.maxY() == tree.targetMaxY(n)) return previous;
        return nodeBounds[n] = new GroundQuadtreeCuller.Bounds(
                GroundQuadtree.x(n),
                tree.targetMinY(n),
                GroundQuadtree.z(n),
                Math.min(shape.size(), GroundQuadtree.x(n) + GroundQuadtree.size(n)),
                tree.targetMaxY(n),
                Math.min(shape.size(), GroundQuadtree.z(n) + GroundQuadtree.size(n)));
    }

    private static GroundQuadtreeCuller.Evaluation nodeView(int n) {
        var currentCullers = cullers;
        if (currentCullers == null) return GroundQuadtreeCuller.Evaluation.UNCERTAIN;
        boolean cache = RenderSystem.isOnRenderThread();
        int minY = tree.targetMinY(n), maxY = tree.targetMaxY(n);
        int face = GroundQuadtree.face(n);
        boolean window = cache && cullWindow && (surfaceCullWindow || movementCullWindow
                || anchorCullers[face].orbitalWindowSource(minY, maxY));
        long epoch = window ? cullEpoch : -exactViewEpoch;
        if (cache && nodeViewEpoch[n] == epoch
                && nodeViewMinY[n] == minY && nodeViewMaxY[n] == maxY) return nodeViews[n];
        var result = window && movementCullWindow
                ? anchorCullers[face].evaluateMovementWindow(CubeSurfaceTopology.Face.VALUES[face], bounds(n))
                : window && surfaceCullWindow
                ? anchorCullers[face].evaluateSurfaceWindow(CubeSurfaceTopology.Face.VALUES[face], bounds(n))
                : window ? anchorCullers[face].evaluateOrbitalWindow(CubeSurfaceTopology.Face.VALUES[face], bounds(n))
                : currentCullers[face].evaluate(CubeSurfaceTopology.Face.VALUES[face], bounds(n));
        if (cache) {
            nodeViews[n] = result;
            nodeViewEpoch[n] = epoch;
            nodeViewMinY[n] = minY;
            nodeViewMaxY[n] = maxY;
        }
        return result;
    }

    private static boolean reusableCutWindow() {
        if (!cullWindow) return false;
        if (surfaceCullWindow || movementCullWindow) return true;
        for (int patch = 0; patch < GroundQuadtree.ROOT_COUNT; patch++) {
            int root = GroundQuadtree.root(patch);
            if (tree.valid(root) && !anchorCullers[GroundQuadtree.face(root)]
                    .orbitalWindowSource(tree.targetMinY(root), tree.targetMaxY(root))) return false;
        }
        return true;
    }

    private static boolean visible(int n) {
        boolean visible = nodeView(n).visible();
        if (RenderWorkDiagnostics.active()) {
            RenderWorkDiagnostics.add(FAR_CULL_GROUP_TESTS, 1);
            if (selectingCut) {
                RenderWorkDiagnostics.add(FAR_CHART_NODES_VISITED, 1);
                if (GroundQuadtree.depth(n) == 4)
                    RenderWorkDiagnostics.add(FAR_CHART_LEAVES_VISITED, 1);
                if (!visible) RenderWorkDiagnostics.add(FAR_CHART_NODES_REJECTED, 1);
            }
            if (!visible) RenderWorkDiagnostics.add(FAR_CULL_GROUP_REJECTS, 1);
        }
        if (!visible && FarViewDiagnostics.active())
            nodeEvent(diagnosticEpoch(), n, tree.desiredStep(n), Kind.CULL, "node-view-rejected");
        return visible;
    }

    private static double distance(int n) {
        return nodeView(n).distance();
    }

    private static boolean near(int n) {
        int base = GroundQuadtree.face(n) * 16384,
                x = GroundQuadtree.x(n) / 16,
                z = GroundQuadtree.z(n) / 16,
                size = GroundQuadtree.size(n) / 16;
        for (int row = z; row < z + size; row++) {
            int start = base + row * 128 + x, found = nearTiles.nextSetBit(start);
            if (found >= start && found < start + size) return true;
        }
        return false;
    }

    private static Matrix4f walkingBaseProjection, walkingRenderedProjection;

    public static void walkingProjection(Matrix4f base, Matrix4f rendered) {
        walkingBaseProjection = base == null || !ReversedDepth.ACTIVE ? base : ReversedDepth.projection(base);
        walkingRenderedProjection = rendered == null || !ReversedDepth.ACTIVE ? rendered : ReversedDepth.projection(rendered);
    }

    public static boolean boundedWalkingPose(org.joml.Matrix4fc pose) {
        return GroundQuadtreeCuller.boundedWalkingPose(pose);
    }

    private static void updateCullers() {
        var previousCullers = cullers;
        boolean sameView = previousCullers != null;
        var nextCullers = new GroundQuadtreeCuller[6];
        var gap = GroundClient.gap();
        var orbit = GroundClient.orbit();
        var active = GroundClient.renderFrame;
        var projection = RenderSystem.getProjectionMatrix();
        boolean boundedBob = orbit.eye.y() <= dev.starfold.chart.GroundOrbit.START
                && walkingRenderedProjection != null && walkingRenderedProjection.equals(projection);
        if (boundedBob) projection = walkingBaseProjection;
        var lens = dev.starfold.chart.GroundSpaceOptics.lens(projection, GroundClient.eye().y());
        for (int f = 0; f < 6; f++) {
            var tile = gap.chart.tile(CubeSurfaceTopology.Face.VALUES[f]);
            var zero = new BentStripGeometry.Point(0, 0, 0);
            var flat = tile == null ? zero : tile.point(zero);
            var u =
                    tile == null
                            ? new BentStripGeometry.Point(1, 0, 0)
                            : tile.point(new BentStripGeometry.Point(1, 0, 0)).sub(flat);
            var v =
                    tile == null
                            ? new BentStripGeometry.Point(0, 0, 1)
                            : tile.point(new BentStripGeometry.Point(0, 0, 1)).sub(flat);
            nextCullers[f] =
                    new GroundQuadtreeCuller(
                            new GroundQuadtreeCuller.Frame(
                                    gap,
                                    orbit,
                                    GroundClient.origin(f).sub(GroundClient.origin()),
                                    flat,
                                    u,
                                    v,
                                    active.viewOffset,
                                    point(active.X),
                                    point(active.Y),
                                    point(active.Z),
                                    lens, projection, true), cullGeometry, boundedBob);
            sameView &= previousCullers != null && nextCullers[f].sameFrame(previousCullers[f]);
        }
        if (!sameView) exactViewEpoch++;
        boolean reuseWindow = anchorCullers != null;
        for (int f = 0; f < 6; f++)
            reuseWindow &= anchorCullers != null && (nextCullers[f].withinMovementWindow(anchorCullers[f])
                    || nextCullers[f].withinSurfaceWindow(anchorCullers[f])
                    || nextCullers[f].withinOrbitalWindow(anchorCullers[f]));
        if (!reuseWindow) {
            anchorCullers = nextCullers;
            if (!sameView) cullEpoch++;
        }
        movementCullWindow = surfaceCullWindow = cullWindow = true;
        for (int f = 0; f < 6; f++) {
            boolean movement = nextCullers[f].withinMovementWindow(anchorCullers[f]);
            boolean surface = nextCullers[f].withinSurfaceWindow(anchorCullers[f]);
            movementCullWindow &= movement;
            surfaceCullWindow &= surface;
            cullWindow &= movement || surface || nextCullers[f].withinOrbitalWindow(anchorCullers[f]);
        }
        cullers = nextCullers;
    }

    private static BentStripGeometry.Point point(org.joml.Vector3f p) {
        return new BentStripGeometry.Point(p.x, p.y, p.z);
    }

    private static void invalidate(long key) {
        int f = (int) (key / 16384), x = (int) (key % 128) * 16, z = (int) (key / 128 % 128) * 16;
        // Include the four edge halos, canonicalized across cube seams.
        for (int dz = -16; dz <= 16; dz += 16)
            for (int dx = -16; dx <= 16; dx += 16) {
                var cell =
                        shape.topology()
                                .canonical(
                                        new CubeSurfaceTopology.Cell(
                                                CubeSurfaceTopology.Face.VALUES[f],
                                                x + dx,
                                                0,
                                                z + dz))
                                .cell();
                dirtyTiles.set((int) tileKey(cell.face().ordinal(), cell.u(), cell.v()));
            }
    }

    private static void prepareSurfaceUpdates() {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.FAR);
        long prepare = RenderWorkDiagnostics.start(),
                update = RenderWorkDiagnostics.start(),
                chartPrepare = RenderWorkDiagnostics.start();
        RenderWorkDiagnostics.add(FAR_PREPARE_CALLS, 1);
        try {
            prepareSurfaceUpdatesImpl();
        } finally {
            diagnosticOccupancy();
            RenderWorkDiagnostics.finish(FAR_LOD_UPDATE_NS, update);
            RenderWorkDiagnostics.finish(FAR_PREPARE_NS, prepare);
            RenderWorkDiagnostics.finish(FAR_CHART_PREPARE_NS, chartPrepare);
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.FAR);
        }
    }

    private static void prepareSurfaceUpdatesImpl() {
        if (tree == null
                || GroundViewClient.terrain() == null
                || !GroundViewClient.coverageReady()) {
            RenderWorkDiagnostics.add(FAR_LOD_BLOCK_INPUT, 1);
            return;
        }
        demandObservedFrame = -1;
        frameWork++;
        selectionActive = true;
        meshes.inputs.focus(GroundClient.eye());
        dirtyTiles.clear();
        for (long key : GroundViewClient.drainInvalidated()) invalidate(key);
        if (!dirtyTiles.isEmpty()) tree.invalidateTiles(dirtyTiles, MIN_TERRAIN_Y, MAX_TERRAIN_Y);
        var baseChanges = meshes.inputs.pollBase();
        for (int p = baseChanges.nextSetBit(0); p >= 0; p = baseChanges.nextSetBit(p + 1))
            tree.invalidate(
                    GroundViewPolicy.face(p),
                    GroundViewPolicy.x(p),
                    GroundViewPolicy.z(p),
                    GroundViewPolicy.x(p) + 256,
                    GroundViewPolicy.z(p) + 256,
                    MIN_TERRAIN_Y,
                    MAX_TERRAIN_Y);
        updateCullers();
        nearTiles.clear();
        var pose = GroundClient.pose();
        for (long key :
                GroundViewPolicy.details(
                        shape,
                        new PlanetShape.Pose(pose.face(), pose.point()),
                        GroundFlightClient.controlled())) nearTiles.set((int) key);
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (recoveryPosition == null
                || Math.abs(net.minecraft.util.Mth.wrapDegrees(camera.getYRot() - recoveryYaw)) >= 30
                || Math.abs(camera.getXRot() - recoveryPitch) >= 20
                || camera.getPosition().distanceToSqr(recoveryPosition) >= 32 * 32) {
            recoveryView++;
            recoveryYaw = camera.getYRot();
            recoveryPitch = camera.getXRot();
            recoveryPosition = camera.getPosition();
        }
        // Read the previous demand result before target refresh clears it in ordinary ground views.
        int recoveryGroups = GroundViewPolicy.recoveryGroups(noDemand, meshes.pendingUploads.size(), meshes.scheduler.jobs(), MAX_PENDING_UPLOADS);
        int height = GroundAntiAliasing.detailHeight();
        long targetView = reusableCutWindow() ? cullEpoch : -exactViewEpoch;
        boolean reuseTargets = targetsEpoch == targetView && targetsHeight == height
                && targetsFov == lodFov && targetsNear.equals(nearTiles);
        if (!reuseTargets) {
            tree.requestTargetRefresh();
            targetsEpoch = targetView;
            targetsHeight = height;
            targetsFov = lodFov;
            targetsNear.clear();
            targetsNear.or(nearTiles);
        }
        if (tree.hasTargetUpdates()) {
            noDemand = false;
            long refresh = RenderWorkDiagnostics.start();
            try {
                tree.updateChangedTargets(height, Math.toRadians(lodFov), GroundRenderer::distance,
                        GroundRenderer::visible, GroundRenderer::near, 1_000_000);
            } finally {
                RenderWorkDiagnostics.finish(FAR_LOD_TARGET_REFRESH_NS, refresh);
            }
        }
        // Work completion admits the next batch immediately; idle polling only notices arena-only space changes.
        if (recoveryGroups > 0 && (recoveryCheckedView != recoveryView
                || recoveryCheckedRevision != tree.cutRevision() || frameWork % 30 == 0)) {
            recoveryCheckedView = recoveryView;
            recoveryCheckedRevision = tree.cutRevision();
            long cold = tree.offscreenDetailBytes();
            long room = Math.min(Math.max(0, GroundViewPolicy.DETAIL_BYTES - tree.detailBytes()) + cold,
                    meshes.arenas.reusableBytes() + cold);
            long recovery = RenderWorkDiagnostics.start();
            int recovered;
            try { recovered = tree.recoverPressure(recoveryView, room, recoveryGroups); }
            finally { RenderWorkDiagnostics.finish(FAR_LOD_RECOVERY_NS, recovery); }
            RenderWorkDiagnostics.add(FAR_LOD_RECOVERY_CHECKS, 1);
            RenderWorkDiagnostics.add(FAR_LOD_RECOVERED_GROUPS, recovered);
            if (recovered > 0) noDemand = false;
        }
        if (noDemand) {
            RenderWorkDiagnostics.add(FAR_CACHE_REUSES, 1);
            return;
        }
        cancelObsoleteBuilds();
        int workerSlots = Math.max(0, meshes.scheduler.lookupSlots());
        int stagingSlots = Math.max(0, MAX_PENDING_UPLOADS - meshes.pendingUploads.size());
        int available = Math.min(workerSlots, stagingSlots);
        if (available == 0) {
            RenderWorkDiagnostics.add(
                    meshes.pendingUploads.size() >= MAX_PENDING_UPLOADS
                            ? FAR_LOD_BLOCK_UPLOAD
                            : FAR_LOD_BLOCK_WORKER_QUEUE,
                    1);
            return;
        }
        long discovery = RenderWorkDiagnostics.start();
        record Demand(int node, double priority) {}
        var demand = new ArrayList<Demand>();
        var uploading = new HashSet<Integer>();
        for (var p : meshes.pendingUploads) uploading.add(p.ticket.node());
        sawDemand = false;
        tree.visitDemand(
                GroundRenderer::visible,
                n -> {
                    sawDemand = true;
                    if (meshes.scheduler.pending(n) || uploading.contains(n)) return;
                    int f = GroundQuadtree.face(n);
                    double dot = 0;
                    var view = nodeView(n).view();
                    if (view != null) {
                        double vx = (view.minX() + view.maxX()) / 2,
                                vy = (view.minY() + view.maxY()) / 2,
                                vz = (view.minZ() + view.maxZ()) / 2;
                        dot = -vz / Math.max(1, Math.sqrt(vx * vx + vy * vy + vz * vz));
                    }
                    int siblings = 0, parent = GroundQuadtree.parent(n);
                    if (parent >= 0)
                        for (int i = 0; i < 4; i++)
                            if (tree.meshHandle(GroundQuadtree.child(parent, i)) >= 0) siblings++;
                    int previousStep = tree.builtStep(n);
                    for (int ancestor = GroundQuadtree.parent(n);
                            previousStep == 0 && ancestor >= 0;
                            ancestor = GroundQuadtree.parent(ancestor))
                        previousStep = tree.builtStep(ancestor);
                    double gain =
                            GroundQuadtree.sse(
                                    Math.max(1, previousStep),
                                    distance(n),
                                    GroundAntiAliasing.detailHeight(),
                                    Math.toRadians(lodFov))
                                    - GroundQuadtree.sse(
                                            tree.desiredStep(n),
                                            distance(n),
                                            GroundAntiAliasing.detailHeight(),
                                            Math.toRadians(lodFov));
                    demand.add(
                            new Demand(
                                    n,
                                    GroundQuadtreeScheduler.priority(
                                            Math.max(0, gain),
                                            dot,
                                            siblings,
                                            GroundQuadtree.depth(n) == 0
                                                    && tree.meshHandle(n) < 0)));
                });
        noDemand = !sawDemand && !tree.hasTargetUpdates();
        demand.sort(Comparator.comparingDouble(Demand::priority).reversed());
        RenderWorkDiagnostics.finish(FAR_LOD_DISCOVER_NS, discovery);
        RenderWorkDiagnostics.add(FAR_LOD_DISCOVERED, demand.size());

        long admission = RenderWorkDiagnostics.start();
        var requests = new ArrayList<GroundQuadtreeScheduler.Request<NodeMesh>>();
        final var owner = tree;
        final var sourceShape = shape;
        final var sourceTerrain = GroundViewClient.terrain();
        final var meshDirectory = GroundMeshCache.directory(Minecraft.getInstance().gameDirectory.toPath(),
                dev.starfold.worldgen.surface.SurfaceKey.of(sourceTerrain), GroundBaseDisk.registryStamp());
        final var packedAppearance = GroundPackedAppearance.capture();
        final var payloadOwner = meshes.readyBytes;
        final var packingOwner = meshes.packingBytes;
        // Snapshot immutable columns only for work that can enter the bounded executor now.
        for (int i = 0; i < Math.min(available, demand.size()); i++) {
            var candidate = demand.get(i);
            int n = candidate.node(),
                    step = tree.desiredStep(n),
                    f = GroundQuadtree.face(n),
                    x = GroundQuadtree.x(n),
                    z = GroundQuadtree.z(n),
                    size = GroundQuadtree.size(n);
            var ticket = new GroundQuadtreeScheduler.Ticket(n, step, tree.version(n), meshes.generation);
            int patch = f * 64 + z / 256 * 8 + x / 256;
            var setupTrace = GroundLodBuildTrace.start(ticket, "input-snapshot");
            var field =
                    meshes.inputs.field(
                            shape,
                            GroundViewClient.terrain(),
                            GroundViewClient.overlays(),
                            GroundViewClient.overlayVersion(),
                            patch);
            GroundLodBuildTrace.finish(setupTrace, "captured", 0);
            // Do not feed an already skirted mesh back into the next skirt floor.
            int neighborFloor = GroundSurface.LOWEST;
            long watchEpoch = diagnosticEpoch(), overlayVersion = GroundViewClient.overlayVersion();
            boolean measureBuild = RenderWorkDiagnostics.active() || watchEpoch != 0;
            var permit = new java.util.concurrent.atomic.AtomicBoolean(true);
            meshes.buildPermits.put(ticket, permit);
            requests.add(
                    new GroundQuadtreeScheduler.Request<>(
                            ticket,
                            candidate.priority(),
                            // Worker admission reads session identity only. Mutable node versions
                            // and frame visibility are checked on the render thread at publication.
                            () -> permit.get() && tree == owner && ticket.session() == meshes.generation,
                            () -> {
                                var meshTrace = GroundLodBuildTrace.start(ticket, "mesh");
                                try {
                                long buildStarted = measureBuild ? System.nanoTime() : 0;
                                String cacheInputs = field.cacheInputs(step);
                                var cacheKey = cacheInputs == null ? null
                                        : new GroundMeshCache.Key(meshDirectory, n, step, cacheInputs);
                                String lookupInputs = cacheInputs == null ? field.cacheLookupInputs(step) : cacheInputs;
                                var lookupKey = lookupInputs == null ? null
                                        : new GroundMeshCache.Key(meshDirectory, n, step, lookupInputs);
                                String appearance = lookupKey == null || packedAppearance == null ? null : packedAppearance.fingerprint();
                                var packedKey = appearance == null ? null : new GroundPackedCache.Key(lookupKey, appearance);
                                var built = meshCache.get(cacheKey, () ->
                                        GroundQuadtreeMesher.build(
                                                CubeSurfaceTopology.Face.VALUES[f],
                                                x,
                                                z,
                                                size,
                                                step,
                                                neighborFloor,
                                                sourceShape.size(),
                                                (u, v) -> {
                                                    if (!permit.get()) throw new java.util.concurrent.CancellationException();
                                                    long inputStart = meshTrace == null ? 0 : System.nanoTime();
                                                    try {
                                                    var c =
                                                            sourceShape
                                                                    .topology()
                                                                    .canonical(
                                                                            new CubeSurfaceTopology
                                                                                    .Cell(
                                                                                    CubeSurfaceTopology
                                                                                            .Face
                                                                                            .VALUES[
                                                                                            f],
                                                                                    u,
                                                                                    0,
                                                                                    v))
                                                                    .cell();
                                                    return field.cell(c.face(), c.u(), c.v(), step)
                                                            .column();
                                                    } finally {
                                                        if (meshTrace != null) {
                                                            meshTrace.inputNanos += System.nanoTime() - inputStart;
                                                            meshTrace.columnCalls++;
                                                        }
                                                    }
                                                }));
                                if (!permit.get()) throw new java.util.concurrent.CancellationException();
                                if (meshTrace != null) meshTrace.bytes = built.bytes();
                                var plantTrace = GroundLodBuildTrace.start(ticket, "plant-input");
                                Map<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run> samples;
                                try {
                                    samples = GroundMeshContext.preparePlants(built, sourceTerrain, permit::get);
                                } finally {
                                    GroundLodBuildTrace.finish(plantTrace, "prepared", 0);
                                }
                                var skyTrace = GroundLodBuildTrace.start(ticket, "sky-input");
                                GroundMeshContext.Sky sky;
                                try {
                                    sky = GroundMeshContext.prepareSky(built, sourceTerrain, permit::get);
                                } finally {
                                    GroundLodBuildTrace.finish(skyTrace, "prepared", 0);
                                }
                                var geometry = GroundLodPacking.prepare(built, permit::get);
                                return new NodeMesh(
                                        built,
                                        geometry,
                                        cacheKey == null ? null : packedKey,
                                        null,
                                        packingOwner,
                                        samples,
                                        sky,
                                        measureBuild ? System.nanoTime() - buildStarted : -1,
                                        watchEpoch,
                                        overlayVersion,
                                        payloadOwner,
                                        GroundQuadtree.depth(n) == 0);
                                } finally {
                                    GroundLodBuildTrace.finish(meshTrace,
                                            meshTrace != null && meshTrace.bytes > 0 ? "built" : "empty-or-failed",
                                            meshTrace == null ? 0 : meshTrace.bytes);
                                }
                            },
                            () -> {
                                long started = measureBuild ? System.nanoTime() : 0;
                                String lookupInputs = field.cacheLookupInputs(step);
                                var key = lookupInputs == null ? null : new GroundMeshCache.Key(
                                        meshDirectory, n, step, lookupInputs);
                                String appearance = key == null || packedAppearance == null
                                        ? null : packedAppearance.fingerprint();
                                var packedKey = appearance == null ? null
                                        : new GroundPackedCache.Key(key, appearance);
                                var trace = GroundLodBuildTrace.start(ticket, "packed-read");
                                try {
                                    var cached = packedCache.get(packedKey);
                                    if (cached == null) return null;
                                    if (!permit.get()) throw new java.util.concurrent.CancellationException();
                                    if (trace != null) trace.bytes = cached.built().bytes();
                                    return new NodeMesh(cached.built(), null, packedKey, cached,
                                            packingOwner, Map.of(), null,
                                            measureBuild ? System.nanoTime() - started : -1,
                                            watchEpoch, overlayVersion, payloadOwner, GroundQuadtree.depth(n) == 0);
                                } finally {
                                    GroundLodBuildTrace.finish(trace,
                                            trace != null && trace.bytes > 0 ? "hit" : "miss-or-cancelled",
                                            trace == null ? 0 : trace.bytes);
                                }
                            }));
        }
        meshes.scheduler.schedule(requests);
        int admitted = 0;
        for (var request : requests) {
            var ticket = request.ticket();
            int n = ticket.node();
            if (!meshes.scheduler.pending(n)) continue;
            admitted++;
            for (int slot = 0; slot < meshes.inFlightNodes.length; slot++)
                if (meshes.inFlightNodes[slot] == 0) {
                    meshes.inFlightNodes[slot] = n + 1;
                    break;
                }
            GroundLodLifecycleDiagnostics.nodeRequest(
                    n, tree.builtStep(n), ticket.step(), tree.dirty(n));
            RenderWorkDiagnostics.add(FAR_LOD_ADMITTED, 1);
            if (failedNodes != null && failedNodes.get(n)) {
                failedNodes.clear(n);
                RenderWorkDiagnostics.add(FAR_LOD_RETRIED_FAILURE, 1);
            }
            long epoch = diagnosticEpoch();
            if (epoch != 0) {
                meshes.watchedRequests.put(n, epoch);
                nodeEvent(epoch, ticket, Kind.REQUEST, "scheduler-admitted");
            }
        }
        demandAfterAdmission = demand.size() - admitted;
        demandObservedFrame = frameWork;
        RenderWorkDiagnostics.gauge(FAR_LOD_PENDING_DEMAND, demandAfterAdmission);
        RenderWorkDiagnostics.finish(FAR_LOD_ADMIT_NS, admission);
    }

    private static void refreshNativeDraws(boolean translucent) {
        translucentPass = translucent;
        window = GroundNativeCoverage.visible(translucent);
        nativeTiles.clear();
        for (long key : window) if (key >= 0 && key < 98304) nativeTiles.set((int) key);
        meshes.arenas.updateNativeMask(nativeTiles);
        if (shape != null) closureCoverage();
    }

    private static void completeNativeDraw(boolean translucent) {
        finalNativeCoverage.complete(translucent, window);
        if (translucent && tree != null) {
            observeDiagnostics();
            traceConstruction();
        }
    }

    private static void updateMeshes() {
        long complete = RenderWorkDiagnostics.start();
        try {
            updateMeshesImpl();
        } finally {
            diagnosticOccupancy();
            RenderWorkDiagnostics.finish(FAR_LOD_COMPLETE_NS, complete);
        }
    }

    private static void updateMeshesImpl() {
        cancelObsoleteBuilds();
        GroundQuadtreeScheduler.Completed<NodeMesh> done;
        while ((done = meshes.scheduler.poll()) != null) {
            meshes.buildPermits.remove(done.ticket());
            for (int i = 0; i < meshes.inFlightNodes.length; i++)
                if (meshes.inFlightNodes[i] == done.ticket().node() + 1) meshes.inFlightNodes[i] = 0;
            Long watched =
                    meshes.watchedRequests.isEmpty() ? null : meshes.watchedRequests.remove(done.ticket().node());
            long epoch = watched == null ? 0 : watched;
            if (done.failure() != null) {
                jobFailure(done.ticket(), epoch, done.failure());
                if (done.mesh() != null) done.mesh().close();
                continue;
            }
            if (done.mesh() == null) {
                nodeEvent(epoch, done.ticket(), Kind.CANCEL, "worker-no-payload");
                continue;
            }
            if (!current(done.ticket())) {
                nodeEvent(
                        done.mesh().watchEpoch,
                        done.ticket(),
                        Kind.STALE,
                        "completed-ticket-stale");
                done.mesh().close();
                continue;
            }
            nodeRecord(
                    done.mesh().watchEpoch,
                    done.ticket(),
                    "quadtree-mesh",
                    done.mesh().buildNanos,
                    done.mesh().built.bytes());
            meshes.pendingUploads.add(new Pending(done.ticket(), done.mesh()));
        }
        long backlog = meshes.arenas.pendingCopyBytes();
        for (var p : meshes.pendingUploads) backlog += p.mesh.built.bytes();
        long started = System.nanoTime();
        var budget = new GroundUploadBudget(started + GroundViewPolicy.FRAME_BUDGET_NS, backlog);
        // Leave room in the same frame for already prepared uploads and packed-cache hits.
        long captureDeadline = started + GroundViewPolicy.FRAME_BUDGET_NS / 2;
        meshes.arenas.progressGrowth(budget);
        long maskWork = RenderWorkDiagnostics.start();
        try { prepareMasks(budget); }
        finally { RenderWorkDiagnostics.finish(FAR_LOD_MASK_NS, maskWork); }
        for (var it = meshes.pendingUploads.iterator(); it.hasNext() && budget.cpuAvailable(); ) {
            var p = it.next();
            if (!current(p.ticket) || !p.context.current()) {
                nodeEvent(p.mesh.watchEpoch, p.ticket, Kind.STALE, "upload-ticket-stale");
                it.remove();
                p.close();
                continue;
            }
            if (!p.storageChecked) {
                if (p.layerVertices == null) {
                    p.layerVertices = new int[GroundFaceArenas.LAYERS];
                    if (p.mesh.cachedPacked != null) {
                        for (var layer : p.mesh.cachedPacked.layers())
                            p.layerVertices[layer.index()] = layer.vertices().remaining() / 12;
                    } else {
                        p.mesh.geometry.verticesByState().forEach((state, count) -> {
                            int layer = GroundFaceArenas.layerIndex(materialLayer(state));
                            p.layerVertices[layer] += count;
                        });
                    }
                }
                int face = GroundQuadtree.face(p.ticket.node());
                var admission = meshes.arenas.admission(face, p.layerVertices);
                if (admission == GroundLodArena.AllocationStatus.REJECTED) {
                    RenderWorkDiagnostics.add(FAR_LOD_ADMISSION_REJECTED, 1);
                    long target = Math.max(0, tree.detailBytes() - Math.max(p.mesh.built.bytes(), 1024L * 1024L));
                    long freed = tree.evictOffscreenToBudget(target, frameWork, face, GroundRenderer::release);
                    if (!current(p.ticket)) {
                        nodeEvent(p.mesh.watchEpoch, p.ticket, Kind.STALE, "upload-ticket-stale");
                        it.remove();
                        p.close();
                        continue;
                    }
                    if (freed > 0) {
                        admission = meshes.arenas.admission(face, p.layerVertices);
                        if (admission != GroundLodArena.AllocationStatus.REJECTED)
                            RenderWorkDiagnostics.add(FAR_LOD_ADMISSION_RECLAIMED, 1);
                    }
                }
                if (admission == GroundLodArena.AllocationStatus.WAIT) {
                    RenderWorkDiagnostics.add(FAR_LOD_BLOCK_ARENA_WAIT, 1);
                    continue;
                }
                if (admission == GroundLodArena.AllocationStatus.REJECTED) {
                    RenderWorkDiagnostics.add(FAR_LOD_BLOCK_MEMORY, 1);
                    if (p.trace != null) p.trace.outcome = "gpu-admission-rejected";
                    it.remove();
                    p.close();
                    tree.deferNode(p.ticket.node(), p.mesh.built.bytes());
                    cancelObsoleteBuilds();
                    continue;
                }
                p.storageChecked = true;
            }
            if (p.layers == null) {
                long packing = p.mesh.watchEpoch == 0 ? 0 : System.nanoTime();
                long packingStart = p.trace == null ? 0 : System.nanoTime();
                boolean packed = false;
                RuntimeException packingFailure = null;
                GroundLodBuildTrace.packing = p.trace;
                try {
                    packed = p.resolve(budget, captureDeadline);
                } catch (RuntimeException failure) {
                    packingFailure = failure;
                } finally {
                    GroundLodBuildTrace.packing = null;
                    if (p.trace != null) {
                        p.trace.packingNanos += System.nanoTime() - packingStart;
                        p.trace.slices++;
                    }
                }
                if (packingFailure != null) {
                    jobFailure(p.ticket, p.mesh.watchEpoch, packingFailure);
                    if (p.trace != null) p.trace.outcome = "packing-failed";
                    it.remove();
                    p.close();
                    continue;
                }
                nodeRecord(
                        p.mesh.watchEpoch,
                        p.ticket,
                        "quadtree-packing",
                        packing == 0 ? -1 : System.nanoTime() - packing,
                        p.resolveCursor);
                if (!packed) continue;
            }
            boolean blocked = false;
            while (p.upload != null || p.layers.hasNext()) {
                long transferStart = p.trace == null ? 0 : System.nanoTime();
                if (p.upload == null) {
                    var e = p.layers.next();
                    p.upload =
                            meshes.arenas.beginUpload(
                                    GroundQuadtree.face(p.ticket.node()),
                                    GroundFaceArenas.layer(e.getKey()),
                                    e.getValue(), p.packed.requiredMaterials());
                }
                int beforeUpload = p.upload.uploadedBytes();
                long uploadStarted = p.mesh.watchEpoch == 0 ? 0 : System.nanoTime();
                long uploadWork = RenderWorkDiagnostics.start();
                boolean uploaded;
                try { uploaded = p.upload.step(budget); }
                finally { RenderWorkDiagnostics.finish(FAR_LOD_UPLOAD_STEP_NS, uploadWork); }
                if (p.trace != null) p.trace.uploadNanos += System.nanoTime() - transferStart;
                int uploadedBytes = p.upload.uploadedBytes() - beforeUpload;
                if (uploadedBytes != 0) {
                    nodeEvent(p.mesh.watchEpoch, p.ticket, Kind.UPLOAD, "upload-slice-submitted");
                    nodeRecord(
                            p.mesh.watchEpoch,
                            p.ticket,
                            "quadtree-upload",
                            uploadStarted == 0 ? -1 : System.nanoTime() - uploadStarted,
                            uploadedBytes);
                }
                if (!uploaded) {
                    blocked = true;
                    break;
                }
                p.slots.add(p.upload.finish());
                p.upload = null;
            }
            if (blocked) {
                if (p.upload.allocationRejected()) {
                    RenderWorkDiagnostics.add(FAR_LOD_BLOCK_MEMORY, 1);
                    nodeEvent(p.mesh.watchEpoch, p.ticket, Kind.CANCEL, "gpu-allocation-rejected");
                    if (p.trace != null) p.trace.outcome = "gpu-allocation-rejected";
                    it.remove();
                    p.close();
                    tree.evictOffscreenToBudget(
                            Math.max(0, tree.detailBytes() - Math.max(p.mesh.built.bytes(), 1024L * 1024L)),
                            frameWork,
                            GroundQuadtree.face(p.ticket.node()),
                            GroundRenderer::release);
                    tree.deferNode(p.ticket.node(), p.mesh.built.bytes());
                    continue;
                }
                RenderWorkDiagnostics.add(FAR_LOD_BLOCK_UPLOAD, 1);
                // A growing pool must not block ready uploads into independent pools.
                continue;
            }
            int oldStep = tree.builtStep(p.ticket.node());
            int handle = meshes.nextHandle++;
            var bundle = new Bundle(p.ticket, p.mesh.overlayVersion, List.copyOf(p.slots));
            meshes.bundles.put(handle, bundle);
            var b = p.mesh.built.bounds();
            boolean published =
                    tree.publish(
                            p.ticket.node(),
                            p.ticket.step(),
                            p.ticket.version(),
                            handle,
                            bundle.bytes(),
                            (int) Math.floor(b.minY()),
                            (int) Math.ceil(b.maxY()),
                            GroundRenderer::release);
            if (!published) {
                nodeEvent(p.mesh.watchEpoch, p.ticket, Kind.STALE, "publication-rejected");
                // This generation was never resident: do not report retirement.
                meshes.bundles.remove(handle).close();
            } else {
                uploads++;
                GroundLodLifecycleDiagnostics.nodePublication(p.ticket.node(), oldStep);
                nodeEvent(p.mesh.watchEpoch, p.ticket, Kind.PUBLICATION, "node-published");
            }
            if (p.trace != null) p.trace.outcome = published ? "published" : "publication-stale";
            p.slots.clear();
            it.remove();
            p.close();
        }
        if (!meshes.pendingUploads.isEmpty() && !budget.cpuAvailable())
            RenderWorkDiagnostics.add(FAR_LOD_BLOCK_DEADLINE, 1);
        RenderWorkDiagnostics.add(FAR_LOD_TRANSFER_BYTES, budget.consumedBytes());
        RenderWorkDiagnostics.add(FAR_LOD_TRANSFER_NS, budget.transferNanos());
        tree.evictToBudget(GroundViewPolicy.DETAIL_BYTES, frameWork, 120, GroundRenderer::release);
    }

    private record ModelFace(TextureAtlasSprite sprite, boolean hasQuad, int tintIndex) {}

    private static ModelFace modelFace(Minecraft mc, BlockState state, Direction direction) {
        var faces = modelFaces.get(state);
        if (faces != null && faces[direction.ordinal()] != null) return faces[direction.ordinal()];
        var model = mc.getBlockRenderer().getBlockModel(state);
        var quads = model.getQuads(state, direction, net.minecraft.util.RandomSource.create(0));
        if (quads.isEmpty() && VoxelMesher.plant(state))
            quads = model.getQuads(state, null, net.minecraft.util.RandomSource.create(0));
        var first = quads.isEmpty() ? null : quads.getFirst();
        var result = new ModelFace(first == null ? model.getParticleIcon() : first.getSprite(),
                first != null, first != null && first.isTinted() ? first.getTintIndex() : -1);
        if (state.getFluidState().isEmpty()
                && model.getClass() == net.minecraft.client.resources.model.SimpleBakedModel.class) {
            if (faces == null) {
                faces = new ModelFace[6];
                modelFaces.put(state, faces);
            }
            faces[direction.ordinal()] = result;
        }
        return result;
    }

    private static GroundLodPacking.Appearance captureAppearance(
            int face, GroundLodPacking.Quad quad, GroundMeshContext context) {
        var mc = Minecraft.getInstance();
        var m = quad.material();
        var state = m.block();
        var fluid = state.getFluidState();
        var direction =
                Direction.getNearest((float) m.normalX(), (float) m.normalY(), (float) m.normalZ());
        var storage = context.storage(quad.appearancePosition());
        var handler =
                fluid.isEmpty()
                        ? null
                        : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                                .of(fluid.getType());
        if (handler
                == net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                        .DEFAULT) handler = null;
        var layer = materialLayer(state);
        var modelFace = handler == null ? modelFace(mc, state, direction) : null;
        var sprite =
                handler == null
                        ? modelFace.sprite()
                        : mc.getTextureAtlas(
                                        net.minecraft.client.renderer.texture.TextureAtlas
                                                .LOCATION_BLOCKS)
                                .apply(handler.getStillTexture(fluid, level, storage));
        int color =
                handler != null
                        ? handler.getTintColor(fluid, level, storage)
                        : modelFace.tintIndex() != -1
                                ? mc.getBlockColors().getColor(state, context.tint(), storage, modelFace.tintIndex())
                                : 0xffffff;
        int albedo = handler == null && modelFace.hasQuad()
                ? GroundAlbedo.color(sprite, color)
                : GroundAlbedo.color(state, storage, context.tint(), direction);
        if (m.rgb() == -3)
            color = GroundMeshVertices.plantTint(color, albedo, face, quad.points(), context);
        int calibrationAlbedo = m.rgb() >= 0 && direction != Direction.UP
                ? GroundAlbedo.color(state, storage, context.tint()) : albedo;
        int bounds =
                GroundFarTexture.upper(sprite.getU1())
                        | GroundFarTexture.upper(sprite.getV1()) << 16;
        int surfaceClass = dev.starfold.api.SurfaceClass.of(state, context.tint(), storage);
        float encodedV = sprite.getV0() + 2 * surfaceClass;
        return new GroundLodPacking.Appearance(sprite.getU0(), encodedV, bounds, color, albedo,
                calibrationAlbedo, GroundFaceArenas.layerIndex(layer), layer == RenderType.translucent());
    }

    /** Same signed-normalized byte encoding as BufferBuilder.setNormal(albedo * 2 - 1). */
    static int packedAlbedoNormal(int rgb) {
        return GroundLodPacking.packedAlbedoNormal(rgb);
    }

    private static void draw(RenderFrame c, boolean translucent) {
        if (tree == null || !selectionReady()) return;
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.FAR);
        try {
        long started = System.nanoTime();
        boolean loadingCover = PlanetRemoteRenderer.coversPartialPlanet();
        if (!translucent) updateMeshes();
        if (loadingCover) return;
        long collect = RenderWorkDiagnostics.start();
        long viewEpoch = reusableCutWindow() ? cullEpoch : -exactViewEpoch;
        var selection = selections[translucent ? 1 : 0];
        meshes.arenas.selectPass(translucent);
        cut = selection.cut;
        if (cut == null || selection.treeRevision != tree.cutRevision() || selection.viewEpoch != viewEpoch
                || !selection.nativeTiles.equals(nativeTiles)) {
        if (cut == null) selection.cut = cut = new GroundQuadtree.Cut();
        long select = RenderWorkDiagnostics.start();
        selectingCut = true;
        try {
            tree.selectCut(nativeTiles, GroundRenderer::visible, frameWork, cut, viewEpoch);
        } finally {
            selectingCut = false;
            RenderWorkDiagnostics.finish(FAR_CHART_SELECT_NS, select);
        }
        RenderWorkDiagnostics.add(FAR_CHART_SELECT_CALLS, 1);
        RenderWorkDiagnostics.add(FAR_CHART_SELECT_REBUILDS, 1);
        selection.updateDraws(selection.viewEpoch != viewEpoch);
        selection.treeRevision = tree.cutRevision();
        selection.viewEpoch = viewEpoch;
        selection.nativeTiles.clear();
        selection.nativeTiles.or(nativeTiles);
        } else {
            tree.markUsed(cut, frameWork);
            RenderWorkDiagnostics.add(FAR_CACHE_REUSES, 1);
        }
        if (!translucent && selection.maskRevision != maskRevision) {
            selection.updateMasks();
            selection.maskRevision = maskRevision;
        }
        RenderWorkDiagnostics.finish(FAR_COLLECT_NS, collect);
        probeTiles += cut.pureCount + cut.maskedCount;
        if (probeTiming) probePrepNanos += System.nanoTime() - started;
        long drawWork = RenderWorkDiagnostics.start();
        int gpuProbe = beginGpuProbe();
        GroundFarDrawDiagnostics.pass(translucent);
        var pass = new RenderPassState(true, true, false);
        var binding = new CoverageBinding();
        int[] textures = new int[3];
        for (int i = 0; i < 3; i++) textures[i] = RenderSystem.getShaderTexture(i);
        int active = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        try (var filtering = GroundTextureFilter.open(); var edges = GroundEdgeFilter.open(translucent)) {
            // Mods extend chunkBufferLayers with custom shaders; those are not compact terrain.
            for (var layer : List.of(
                    RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(),
                    RenderType.translucent(), RenderType.tripwire())) {
                boolean late = layer == RenderType.translucent() || layer == RenderType.tripwire();
                if (late != translucent) continue;
                var transforms =
                        new GroundLayerTransforms(
                                f ->
                                        GroundClient.place(
                                                new Matrix4f(RenderSystem.getModelViewMatrix())
                                                        .mul(c.matrixStack().last().pose()),
                                                f,
                                                c.camera().getPosition()));
                long chartDraw = RenderWorkDiagnostics.start();
                int draws =
                        meshes.arenas.drawLayer(
                                layer,
                                (f, l, masked, shader) -> {
                                    GroundClient.source(f, true, true);
                                    shader.setDefaultUniforms(
                                            VertexFormat.Mode.QUADS,
                                            transforms.copy(f),
                                            RenderSystem.getProjectionMatrix(),
                                            Minecraft.getInstance().getWindow());
                                    for (int unit = 0; unit < 12; unit++)
                                        shader.setSampler(
                                                "Sampler" + unit,
                                                RenderSystem.getShaderTexture(unit));
                                    if (diagnosticBatchContainsWatch(f, l, masked))
                                        GroundFarDrawDiagnostics.begin(
                                                shader,
                                                f,
                                                l,
                                                GroundFarDrawDiagnostics.Source.CHART,
                                                masked ? "quadtree-masked" : "quadtree-pure");
                                });
                RenderWorkDiagnostics.finish(FAR_CHART_DRAW_NS, chartDraw);
                if (draws > 0) {
                    diagnosticSubmittedLayer(layer);
                    if (layer == RenderType.solid() && RenderWorkDiagnostics.active()) {
                        long maskIndices = 0;
                        for (var maskMesh : masks)
                            if (maskMesh != null && maskMesh.compact != null)
                                maskIndices += maskMesh.compact.indices();
                        // Arena submission already counted these once; only move their category.
                        RenderWorkDiagnostics.add(FAR_INDICES_CHART, -maskIndices);
                        RenderWorkDiagnostics.add(FAR_INDICES_MASK, maskIndices);
                    }
                }
                probeGroups += draws;
                probeCompactGroups += draws;
                GroundFarDrawDiagnostics.end();
                layer.setupRenderState();
                try {
                    for (int f = 0; f < 6; f++) {
                        if (!GroundViewPolicy.exists(shape, f * 64)) continue;
                        drawMask(masks[f], f, layer, transforms);
                        drawMask(masks[f + 6], f, layer, transforms);
                    }
                } finally {
                    layer.clearRenderState();
                }
            }
        } finally {
            if (gpuProbe >= 0) GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            GroundFarDrawDiagnostics.end();
            GroundClient.sourceEnd();
            binding.close();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(active);
            for (int i = 0; i < 3; i++) RenderSystem.setShaderTexture(i, textures[i]);
            pass.close();
            RenderWorkDiagnostics.finish(FAR_DRAW_NS, drawWork);
        }
        diagnosticOccupancy();
        if (probeTiming) probeCpuNanos += System.nanoTime() - started;
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.FAR);
        }
    }

    private static void drawMask(
            MaskMesh mesh, int face, RenderType layer, GroundLayerTransforms transforms) {
        if (mesh == null) return;
        var gpu = mesh.layers.get(layer);
        if (gpu == null) return;
        var shader = RenderSystem.getShader();
        if (shader == null) return;
        GroundClient.source(face, true, false);
        gpu.bind();
        try {
            gpu.drawWithShader(transforms.copy(face), RenderSystem.getProjectionMatrix(), shader);
            probeDraws++;
            long indices = mesh.indices.get(layer);
            RenderWorkDiagnostics.add(FAR_DRAW_CALLS, 1);
            RenderWorkDiagnostics.add(FAR_INDICES_SUBMITTED, indices);
            RenderWorkDiagnostics.add(FAR_VERTICES_SUBMITTED, indices / 6 * 4);
            RenderWorkDiagnostics.add(FAR_INDICES_MASK, indices);
            RenderWorkDiagnostics.add(FAR_RANGES_VISIBLE, 1);
            RenderWorkDiagnostics.add(
                    switch (face) {
                        case 0 -> FAR_INDICES_FACE_0;
                        case 1 -> FAR_INDICES_FACE_1;
                        case 2 -> FAR_INDICES_FACE_2;
                        case 3 -> FAR_INDICES_FACE_3;
                        case 4 -> FAR_INDICES_FACE_4;
                        default -> FAR_INDICES_FACE_5;
                    },
                    indices);
            RenderWorkDiagnostics.add(FAR_INDICES_STEP_OTHER, indices);
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static long lastConstructionSnapshot;

    private static void traceConstruction() {
        if (!GroundLodBuildTrace.enabled()) return;
        long now = System.nanoTime();
        if (now - lastConstructionSnapshot < 1_000_000_000L) return;
        lastConstructionSnapshot = now;
        var e = GroundLodBuildTrace.start(null, "snapshot");
        if (e == null) return;
        e.session = meshes.generation;
        e.roots = probeParentTiles() / 256;
        e.jobs = meshes.scheduler.jobs();
        e.completed = meshes.scheduler.completed();
        e.staging = meshes.pendingUploads.size();
        e.uploads = uploads;
        e.gpuBytes = meshes.arenas.bytes();
        e.detailBytes = tree.detailBytes();
        e.selected = cut == null ? 0 : cut.pureCount + cut.maskedCount;
        e.altitude = GroundClient.pose().point().y();
        tree.visitDemand(GroundRenderer::visible, n -> e.demand++);
        for (int p = 0; p < GroundQuadtree.ROOT_COUNT; p++) {
            int n = GroundQuadtree.root(p);
            if (tree.valid(n) && visible(n) && tree.meshHandle(n) < 0) e.missingRoots++;
        }
        GroundLodBuildTrace.finish(e, "sample", 0);
    }

    public static int probeParentTiles() {
        if (tree == null) return -1;
        int count = 0;
        for (int p = 0; p < 384; p++)
            if (GroundViewPolicy.exists(shape, p) && tree.patchReady(p)) count += 256;
        return count;
    }

    public static int probeParents() {
        if (tree == null) return 0;
        int count = 0;
        for (int f = 0; f < 6; f++) {
            boolean ready = true;
            for (int p = f * 64; p < f * 64 + 64; p++)
                if (GroundViewPolicy.exists(shape, p) && !tree.patchReady(p)) {
                    ready = false;
                    break;
                }
            if (ready) count++;
        }
        return count;
    }

    public static String performance() {
        return "Quadtree roots "
                + probeParentTiles()
                + " tiles | jobs "
                + meshes.scheduler.jobs()
                + " | uploads "
                + uploads
                + " | failures "
                + jobFailures
                + " | cut "
                + (cut == null ? 0 : cut.pureCount + cut.maskedCount);
    }

    public static String memory() {
        return String.format(
                Locale.ROOT,
                "Quadtree GPU %.1f MiB | detail %.1f MiB | staging %d",
                meshes.arenas.bytes() / 1048576.,
                tree == null ? 0 : tree.detailBytes() / 1048576.,
                meshes.pendingUploads.size());
    }

    static long diagnosticEpoch() {
        return FarViewDiagnostics.epoch(planet);
    }

    static Versions diagnosticVersions(long key, int step) {
        var surface = GroundViewClient.surface(key);
        // There is no per-node mip identity in this pipeline. A target token is not a mip.
        return new Versions(
                surface == null ? -1 : surface.revision(),
                GroundViewClient.overlayVersion(),
                -1,
                frameWork,
                -1,
                -1);
    }

    private static int selectedNode(int face, int x, int z) {
        if (cut == null) return -1;
        for (int i = 0; i < cut.pureCount; i++)
            if (containsTile(cut.pure[i], face, x, z)) return cut.pure[i];
        for (int i = 0; i < cut.maskedCount; i++)
            if (containsTile(cut.masked[i], face, x, z)) return cut.masked[i];
        return -1;
    }

    private static int desiredNode(int face, int x, int z) {
        int node = GroundQuadtree.root(GroundViewPolicy.patch(tileKey(face, x, z)));
        if (!tree.valid(node) || !tree.valid(GroundQuadtree.nodeId(face, 4, x / 16, z / 16)))
            return -1;
        while (GroundQuadtree.depth(node) < 4 && tree.wantsSplit(node)) {
            int half = GroundQuadtree.size(node) / 2;
            int quadrant =
                    (x >= GroundQuadtree.x(node) + half ? 1 : 0)
                            | (z >= GroundQuadtree.z(node) + half ? 2 : 0);
            node = GroundQuadtree.child(node, quadrant);
            if (!tree.valid(node)) return -1;
        }
        return node;
    }

    private static Pending pendingAt(int face, int x, int z) {
        Pending found = null;
        for (var p : meshes.pendingUploads)
            if (containsTile(p.ticket.node(), face, x, z)
                    && (found == null
                            || GroundQuadtree.depth(p.ticket.node())
                                    > GroundQuadtree.depth(found.ticket.node()))) found = p;
        return found;
    }

    static Map<String, Object> diagnosticDrawInputs(FarViewDiagnostics.Watch w) {
        var fields = new LinkedHashMap<String, Object>();
        long key = tileKey(w.face(), w.x(), w.z());
        int selected = selectedNode(w.face(), w.x(), w.z());
        int desired = tree == null ? -1 : desiredNode(w.face(), w.x(), w.z());
        var pending = pendingAt(w.face(), w.x(), w.z());
        var selectedBundle = selected < 0 ? null : meshes.bundles.get(tree.meshHandle(selected));
        fields.put("nativeWindow", window.contains(key));
        fields.put("nativePass", translucentPass ? "translucent" : "opaque");
        fields.put("rootReady", tree != null && tree.patchReady(GroundViewPolicy.patch(key)));
        fields.put("selectedNode", selected);
        fields.put("selectedDepth", selected < 0 ? -1 : GroundQuadtree.depth(selected));
        fields.put("selectedStep", selected < 0 ? -1 : tree.builtStep(selected));
        fields.put("desiredNode", desired);
        fields.put("desiredStep", desired < 0 ? -1 : tree.desiredStep(desired));
        fields.put("pendingNode", pending == null ? -1 : pending.ticket.node());
        fields.put("pendingSlots", pending == null ? 0 : pending.slots.size());
        fields.put("pendingStep", pending == null ? -1 : pending.ticket.step());
        fields.put("pendingVersion", pending == null ? -1 : pending.ticket.version());
        fields.put(
                "selectedVersion", selectedBundle == null ? -1 : selectedBundle.ticket.version());
        fields.put("desiredVersion", desired < 0 ? -1 : tree.version(desired));
        fields.put(
                "selectedOverlayVersion",
                selectedBundle == null ? -1 : selectedBundle.overlayVersion);
        fields.put(
                "selectedCurrent",
                selectedBundle == null
                        ? "unknown: no selected bundle"
                        : current(selectedBundle.ticket));
        fields.put(
                "pendingAllocation",
                pending == null || pending.upload == null
                        ? "none"
                        : "unknown: no read-only allocation accessor");
        fields.put("currentOverlayVersion", GroundViewClient.overlayVersion());
        fields.put("nativeMasked", selected >= 0 && nativeTiles.get((int) key));
        fields.put(
                "pendingUploadBytes",
                pending == null || pending.upload == null ? 0 : pending.upload.uploadedBytes());
        fields.put(
                "pendingUploadRemainingBytes",
                pending == null || pending.upload == null ? 0 : pending.upload.remainingBytes());
        var pendingSlots = new ArrayList<Map<String, Object>>();
        if (pending != null)
            for (var slot : pending.slots)
                pendingSlots.add(
                        Map.of(
                                "face",
                                slot.face,
                                "layer",
                                slot.layer,
                                "vertices",
                                slot.vertices(),
                                "bytes",
                                slot.bytes()));
        fields.put("pendingSlotDetails", pendingSlots);
        var coveringJobs = new ArrayList<Integer>();
        for (int encoded : meshes.inFlightNodes)
            if (encoded != 0 && containsTile(encoded - 1, w.face(), w.x(), w.z()))
                coveringJobs.add(encoded - 1);
        fields.put("coveringJobNodes", coveringJobs);
        fields.put(
                "pendingDemand",
                demandObservedFrame == frameWork
                        ? demandAfterAdmission
                        : "unknown: discovery skipped");
        fields.put("pendingDemandScope", "after admission; undiscovered demand excluded");
        // nativeMaskTexture() lazily allocates GL storage; diagnostics must remain read-only.
        fields.put("maskTexture", "unknown: no read-only owner accessor");
        fields.put("pureNodes", cut == null ? 0 : cut.pureCount);
        fields.put("maskedNodes", cut == null ? 0 : cut.maskedCount);
        fields.put("session", meshes.generation);
        fields.put("readyDetailBytes", meshes.readyBytes.detail());
        fields.put("readyCoverBytes", meshes.readyBytes.cover());
        fields.put("residentDetailBytes", tree == null ? 0 : tree.detailBytes());
        fields.put(
                "residentCoverBytes", tree == null ? 0 : tree.residentBytes() - tree.detailBytes());
        fields.put("allocatedArenaBytes", meshes.arenas.bytes());
        fields.put("jobFailures", jobFailures);
        fields.put("mipIdentity", "unknown");
        fields.put("meshSurfaceRevision", "unknown: multi-tile snapshot");
        fields.put("workerRetainedBytes", "unknown");
        fields.put("arenaFreeBytes", "unknown");
        fields.put("arenaGrowingBytes", "unknown");
        fields.put("localInputEquality", "unknown");
        fields.put("pixelVisibility", "unknown");
        return fields;
    }

    /** Called only after a successful layer submission; tripwire shares the translucent batch. */
    private static void diagnosticSubmittedLayer(RenderType layer) {
        if (!RenderWorkDiagnostics.active() && !FarViewDiagnostics.active()) return;
        for (int pass = 0; pass < 2; pass++) {
            int count = pass == 0 ? cut.pureCount : cut.maskedCount;
            int[] nodes = pass == 0 ? cut.pure : cut.masked;
            for (int i = 0; i < count; i++) {
                var bundle = meshes.bundles.get(tree.meshHandle(nodes[i]));
                if (bundle == null) continue;
                boolean submitted = false;
                for (var slot : bundle.slots) {
                    if (slot.layer != GroundFaceArenas.layerIndex(layer)) continue;
                    submitted = true;
                    long indices = (long) slot.vertices() / 4 * 6;
                    RenderWorkDiagnostics.add(FAR_RANGES_VISIBLE, 1);
                    RenderWorkDiagnostics.add(FAR_INDICES_CHART, indices);
                    GroundQuadtreeDiagnostics.recordIndexDimensions(slot.face, bundle.ticket.step(), indices);
                }
                if (submitted)
                    nodeEvent(
                            diagnosticEpoch(),
                            bundle.ticket,
                            Kind.DRAW,
                            translucentPass
                                    ? "translucent-layer-submitted"
                                    : "opaque-layer-submitted");
            }
        }
    }

    /** A face batch is evidence for this watch only if its selected node has this layer. */
    private static boolean diagnosticBatchContainsWatch(
            int face, RenderType layer, boolean masked) {
        long epoch = diagnosticEpoch();
        if (epoch == 0) return false;
        var w = FarViewDiagnostics.currentWatch(epoch);
        if (w == null || w.face() != face || nativeTiles.get((int) tileKey(face, w.x(), w.z())))
            return false;
        int node = selectedNode(face, w.x(), w.z());
        if (node < 0) return false;
        boolean inMasked = false;
        for (int i = 0; i < cut.maskedCount; i++) if (cut.masked[i] == node) inMasked = true;
        if (inMasked != masked) return false;
        var bundle = meshes.bundles.get(tree.meshHandle(node));
        if (bundle == null) return false;
        for (var slot : bundle.slots)
            if (slot.layer == GroundFaceArenas.layerIndex(layer)) return true;
        return false;
    }

    private static void nodeEvent(
            long epoch, GroundQuadtreeScheduler.Ticket ticket, Kind kind, String reason) {
        if (epoch == 0 || ticket.session() != meshes.generation) return;
        nodeEvent(
                epoch,
                ticket.node(),
                ticket.step(),
                kind,
                reason + ";targetVersion=" + ticket.version() + ";session=" + ticket.session());
    }

    private static void nodeEvent(long epoch, int node, int step, Kind kind, String reason) {
        if (epoch == 0) return;
        GroundQuadtreeDiagnostics.nodeEvent(
                epoch, node, step, kind, reason, frameWork, translucentPass, nativeTiles);
    }

    private static void nodeRecord(
            long epoch,
            GroundQuadtreeScheduler.Ticket ticket,
            String stage,
            long nanos,
            long bytes) {
        if (epoch == 0 || ticket.session() != meshes.generation) return;
        GroundQuadtreeDiagnostics.nodeRecord(epoch, ticket, stage, nanos, bytes, planet);
    }

    private static void jobFailure(
            GroundQuadtreeScheduler.Ticket ticket, long epoch, Throwable failure) {
        jobFailures++;
        if (current(ticket)) {
            if (failedNodes == null) failedNodes = new BitSet(GroundQuadtree.NODE_COUNT);
            failedNodes.set(ticket.node());
        }
        nodeEvent(epoch, ticket, Kind.SKIP, "worker-failed");
        if (epoch != 0) {
            var w = FarViewDiagnostics.currentWatch(epoch);
            int x =
                    w == null
                            ? -1
                            : Math.max(
                                    GroundQuadtree.x(ticket.node()),
                                    Math.max(0, w.x() - 16 * w.radius()));
            int z =
                    w == null
                            ? -1
                            : Math.max(
                                    GroundQuadtree.z(ticket.node()),
                                    Math.max(0, w.z() - 16 * w.radius()));
            if (w != null
                    && x <= w.x() + 16 * w.radius()
                    && z <= w.z() + 16 * w.radius()
                    && containsTile(ticket.node(), w.face(), x, z)) {
                String message = failure.toString();
                FarViewDiagnostics.record(
                        epoch,
                        planet,
                        w.face(),
                        x,
                        z,
                        "quadtree-job-failure",
                        Map.of(
                                "node",
                                ticket.node(),
                                "step",
                                ticket.step(),
                                "targetVersion",
                                ticket.version(),
                                "failureCount",
                                jobFailures,
                                "error",
                                message.substring(0, Math.min(1024, message.length()))));
            }
        }
        long now = System.nanoTime();
        // At most eight stack traces per renderer session, separated by at least five seconds.
        if (failureLogs < 8 && (failureLogs == 0 || now - lastFailureLog >= 5_000_000_000L)) {
            failureLogs++;
            lastFailureLog = now;
            FailureLog.LOG.warn(
                    "Quadtree mesh failure #{} (node={}, step={}, version={}, session={}); "
                            + "further failures are counted; logs limited to 8/session and 1/5s",
                    jobFailures,
                    ticket.node(),
                    ticket.step(),
                    ticket.version(),
                    ticket.session(),
                    failure);
        }
    }

    private static void diagnosticOccupancy() {
        if (!RenderWorkDiagnostics.active()) return;
        RenderWorkDiagnostics.gauge(FAR_LOD_READERS_ACTIVE, meshes.scheduler.readersActive());
        RenderWorkDiagnostics.gauge(FAR_LOD_READERS_QUEUED, meshes.scheduler.readersQueued());
        RenderWorkDiagnostics.gauge(FAR_LOD_STAGING, meshes.pendingUploads.size());
        RenderWorkDiagnostics.gauge(FAR_LOD_GPU_BYTES, meshes.arenas.bytes());
        long[] cacheStages = packedCache.stages();
        RenderWorkDiagnostics.gauge(FAR_LOD_CACHE_LOOKUPS_TOTAL, cacheStages[0]);
        RenderWorkDiagnostics.gauge(FAR_LOD_CACHE_LOOKUP_TOTAL_NANOS, cacheStages[1]);
        RenderWorkDiagnostics.gauge(FAR_LOD_CACHE_DECODE_TOTAL_NANOS, cacheStages[2]);
        RenderWorkDiagnostics.gauge(FAR_LOD_CACHE_TOUCH_TOTAL_NANOS, cacheStages[3]);
        RenderWorkDiagnostics.gauge(FAR_LOD_CACHE_RESTORE_TOTAL_NANOS, cacheStages[4]);
        long readyDetail = meshes.readyBytes.detail(), readyCover = meshes.readyBytes.cover();
        long retained = meshes.inputs.fineBytes() + meshes.inputs.coarseBytes() + readyDetail + readyCover;
        int first = 0, rebuild = 0;
        for (int encoded : meshes.inFlightNodes) {
            if (encoded == 0) continue;
            if (tree != null && tree.meshHandle(encoded - 1) >= 0) rebuild++;
            else first++;
        }
        retained += meshes.packingBytes.get();
        for (var p : meshes.pendingUploads) {
            if (tree != null && tree.meshHandle(p.ticket.node()) >= 0) rebuild++;
            else first++;
        }
        RenderWorkDiagnostics.gauge(FAR_LOD_PENDING_FIRST, first);
        RenderWorkDiagnostics.gauge(FAR_LOD_PENDING_REBUILD, rebuild);
        RenderWorkDiagnostics.gauge(FAR_LOD_JOBS, meshes.scheduler.jobs());
        // Preserve ready-byte semantics: completed CPU payloads awaiting upload/publication.
        // Unified descendants map to resident/detail work, roots to mandatory cover work.
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_RESIDENT_BYTES, readyDetail);
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_COVER_BYTES, readyCover);
        RenderWorkDiagnostics.gauge(FAR_LOD_ALLOCATED_BYTES, meshes.arenas.bytes());
        RenderWorkDiagnostics.gauge(FAR_LOD_RETAINED_CPU_BYTES, retained);
        // Free/growing VBO breakdown and worker payload sizes are not exposed by these owners.
    }

    private static void observeDiagnostics() {
        long epoch = diagnosticEpoch();
        var w = FarViewDiagnostics.currentWatch(epoch);
        if (w == null) return;
        var tiles = new ArrayList<TileState>();
        boolean sample = FarViewDiagnostics.sampleDue(epoch);
        var nodeStates = sample ? new ArrayList<Map<String, Object>>() : null;
        var finalWindow = finalNativeWindow();
        for (int z = Math.max(0, w.z() - w.radius() * 16);
                z <= Math.min(2032, w.z() + w.radius() * 16);
                z += 16)
            for (int x = Math.max(0, w.x() - w.radius() * 16);
                    x <= Math.min(2032, w.x() + w.radius() * 16);
                    x += 16) {
                long key = tileKey(w.face(), x, z);
                int selected = selectedNode(w.face(), x, z), desired = desiredNode(w.face(), x, z);
                boolean nativeDrawn = finalWindow.contains(key);
                boolean present = selected >= 0 && meshes.bundles.containsKey(tree.meshHandle(selected));
                int selectedStep = selected < 0 ? 0 : tree.builtStep(selected);
                int jobs = 0;
                boolean ancestorPresent = false;
                for (int encoded : meshes.inFlightNodes)
                    if (encoded != 0 && containsTile(encoded - 1, w.face(), x, z)) jobs++;
                int anchor = selected < 0 ? desired : selected;
                for (int n = anchor < 0 ? -1 : GroundQuadtree.parent(anchor);
                        n >= 0;
                        n = GroundQuadtree.parent(n))
                    if (tree.meshHandle(n) >= 0) ancestorPresent = true;
                var pending = pendingAt(w.face(), x, z);
                if (sample) {
                    var state =
                            diagnosticDrawInputs(
                                    new FarViewDiagnostics.Watch(
                                            w.planet(), w.face(), x, z, 0, epoch));
                    state.put("x", x);
                    state.put("z", z);
                    state.put("coveringJobs", jobs);
                    nodeStates.add(state);
                }
                // No fade/previous generation exists: publish replaces synchronously after upload.
                tiles.add(
                        new TileState(
                                w.face(),
                                x,
                                z,
                                nativeDrawn,
                                desired < 0 ? -1 : tree.desiredStep(desired),
                                selectedStep,
                                present,
                                present,
                                false,
                                false,
                                false,
                                jobs != 0 || pending != null,
                                jobs,
                                desired < 0 ? -1 : tree.desiredStep(desired),
                                nativeDrawn ? 0 : selectedStep,
                                present,
                                ancestorPresent,
                                pending != null && !pending.slots.isEmpty(),
                                diagnosticVersions(key, selectedStep),
                                -1,
                                -1));
            }
        FarViewDiagnostics.observeRegion(
                new RegionState(
                        System.nanoTime(),
                        epoch,
                        planet,
                        GroundClient.face(),
                        selectionReady(),
                        GroundViewClient.coverageReady(),
                        remoteReplacementReady(),
                        meshes.pendingUploads.size(),
                        meshes.scheduler.jobs(),
                        tiles));
        if (sample)
            FarViewDiagnostics.record(
                    epoch,
                    planet,
                    w.face(),
                    w.x(),
                    w.z(),
                    "quadtree-region",
                    Map.of(
                            "nativePass",
                            "translucent",
                            "tiles",
                            nodeStates,
                            "pixelVisibility",
                            "unknown"));
    }

    static long meshSession() {
        return meshes.generation;
    }

    static Set<Long> finalNativeWindow() {
        return finalNativeCoverage.snapshot();
    }

    public static boolean selectionReady() {
        return selectionActive && tree != null;
    }

    public static int coverageTexture() {
        return meshes.arenas.nativeMaskTexture();
    }

    public static boolean remotePatchReady(int patch) {
        return selectionReady() && tree.patchReady(patch);
    }

    static boolean visiblePlanetReady() {
        if (!selectionReady()) return false;
        for (int patch = 0; patch < GroundQuadtree.ROOT_COUNT; patch++) {
            int root = GroundQuadtree.root(patch);
            if (tree.valid(root) && !tree.patchReady(patch) && nodeView(root).visible()) return false;
        }
        return true;
    }

    public static boolean remoteReplacementReady() {
        if (!selectionReady()) return false;
        for (int p = 0; p < GroundViewPolicy.PATCH_COUNT; p++)
            if (GroundViewPolicy.exists(shape, p) && !remotePatchReady(p)) return false;
        return true;
    }

    public static void clear() {
        recoveryPosition = null;
        recoveryCheckedView = recoveryCheckedRevision = Long.MIN_VALUE;
        GroundMeshContext.clearBiomeCache();
        meshes.invalidate();
        for (int i = 0; i < gpuQueries.length; i++) {
            if (gpuQueries[i] != 0) GL15.glDeleteQueries(gpuQueries[i]);
            gpuQueries[i] = 0;
            gpuPending[i] = false;
        }
        gpuCursor = 0;
        meshes.clear(diagnosticEpoch(), frameWork, nativeTiles);
        demandObservedFrame = -1;
        if (failedNodes != null) failedNodes.clear();
        failureLogs = 0;
        tree = null;
        cut = null;
        for (int i = 0; i < selections.length; i++) selections[i] = new Selection(i == 1);
        cullers = null;
        anchorCullers = null;
        targetsEpoch = Long.MIN_VALUE;
        cullWindow = surfaceCullWindow = movementCullWindow = false;
        Arrays.fill(nodeBounds, null);
        Arrays.fill(nodeViews, null);
        cullGeometry = new GroundQuadtreeCuller.Geometry();
        closeMasks();
        GroundExterior.clear();
        GroundCompact.clear();
        selectionActive = false;
        finalNativeCoverage.reset();
        window = Set.of();
        Arrays.fill(realCorners, 0);
        level = null;
        shape = null;
        planet = -1;
        mask = null;
        FarViewDiagnostics.observeContext(-1, meshes.generation, true, "renderer-clear");
    }

    private static void closeMasks() {
        for (int i = 0; i < masks.length; i++) {
            if (masks[i] != null) masks[i].close();
            masks[i] = null;
            maskAttempted[i] = false;
        }
    }

    private static void ensureState() {
        var mc = Minecraft.getInstance();
        var observed = GroundClient.shape();
        int id = GroundClient.planet();
        var config = GroundFlightServer.mask(mc.level);
        if (level == mc.level && planet == id && observed.equals(shape) && config.equals(mask))
            return;
        clear();
        level = mc.level;
        planet = id;
        shape = observed;
        mask = config;
        tree = new GroundQuadtree(shape, MIN_TERRAIN_Y, MAX_TERRAIN_Y);
        FarViewDiagnostics.observeContext(planet, meshes.generation, false, "quadtree-identity");
    }

    private static long tileKey(int face, int x, int z) {
        return ChunkKeys.faceLocal(face, x / 16, z / 16);
    }

    private static void prepareMasks(GroundUploadBudget budget) {
        for (int i = 0; i < masks.length && budget.cpuAvailable(); i++) {
            int face = i % 6;
            if (!GroundViewPolicy.exists(shape, face * 64)) continue;
            if (!maskAttempted[i]) {
                masks[i] = buildMask(face, i >= 6);
                maskAttempted[i] = true;
                maskRevision++;
            }
            var mesh = masks[i];
            if (mesh != null && mesh.compact != null) {
                int before = mesh.compact.indices();
                mesh.compact.prepare(meshes.arenas, face, budget);
                if (before != mesh.compact.indices()) maskRevision++;
            }
        }
    }

    private record MaskMesh(Map<RenderType, VertexBuffer> layers, Map<RenderType, Integer> indices,
                            GroundMaskMesh compact) implements AutoCloseable {
        public void close() {
            if (compact != null) compact.close();
            layers.values().forEach(VertexBuffer::close);
        }
    }

    private static final class Builders extends GroundMeshVertices.Buffers {
        MaskMesh upload(int ignored) {
            var result = new LinkedHashMap<RenderType, VertexBuffer>();
            var indices = new LinkedHashMap<RenderType, Integer>();
            for (var e : buffers.entrySet()) {
                var data = e.getValue().build();
                if (data == null) continue;
                if (buffers.size() == 1 && e.getKey() == RenderType.solid()
                        && GroundMaskMesh.fits(data))
                    return new MaskMesh(Map.of(), Map.of(), new GroundMaskMesh(data));
                indices.put(e.getKey(), data.drawState().indexCount());
                var gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
                gpu.bind();
                gpu.upload(data);
                VertexBuffer.unbind();
                result.put(e.getKey(), gpu);
            }
            return result.isEmpty() ? null : new MaskMesh(result, indices, null);
        }
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        GroundExterior.initialize(modBus);
        GroundCompact.initialize(modBus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) -> {
                    // Vanilla's FOV interpolation starts at zero before its first client tick.
                    if (event.usedConfiguredFov() && event.getFOV() > 0) {
                        lodFov = event.getFOV();
                        GroundMeshScheduler.lodFov = lodFov;
                    }
                });
        // Selection and connectors become visible before vanilla terrain draws, not mid-frame.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_SKY) return;
                    if (probeOff || !GroundClient.ready() || !RendererGuard.pipelineSupported()) {
                        finalNativeCoverage.reset();
                        return;
                    }
                    long start = System.nanoTime();
                    ensureState();
                    prepareSurfaceUpdates();
                    if (probeTiming) probeSkyNanos += System.nanoTime() - start;
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                                    != net.neoforged.neoforge.client.event.RenderLevelStageEvent
                                            .Stage.AFTER_ENTITIES
                            || probeOff) return;
                    var c = new RenderFrame(cpEvent);
                    if (!GroundClient.ready()
                            || !RendererGuard.pipelineSupported()
                            || c.matrixStack() == null) return;
                    try {
                        refreshNativeDraws(false);
                        draw(c, false);
                        completeNativeDraw(false);
                    } finally {
                        GroundEdgeFilter.endNative();
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                                    != net.neoforged.neoforge.client.event.RenderLevelStageEvent
                                            .Stage.AFTER_PARTICLES
                            || probeOff) return;
                    var c = new RenderFrame(cpEvent);
                    if (GroundClient.ready()
                            && RendererGuard.pipelineSupported()
                            && c.matrixStack() != null) {
                        refreshNativeDraws(true);
                        draw(c, true);
                        completeNativeDraw(true);
                    } else {
                        finalNativeCoverage.reset();
                    }
                });
        ClientPlayLifecycle();
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block event) ->
                        event.register(
                                (state, world, pos, tint) -> Anomaly.GLOW_TINT,
                                Anomaly.CORE_BLOCK));
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) ->
                        event.registerReloadListener(
                                new net.minecraft.server.packs.resources
                                        .ResourceManagerReloadListener() {
                                    @Override
                                    public void onResourceManagerReload(ResourceManager manager) {
                                        GroundTextureFilter.invalidateResources();
                                        modelFaces.clear();
                                        clear();
                                        GroundMeshResources.endReload();
                                    }
                                }));
    }

    private static void ClientPlayLifecycle() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.GameShuttingDownEvent cpEvent) -> {
                    clear();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (level != null && level != mc.level) clear();
                });
    }

    public static int realCorners(int face) {
        return realCorners[face];
    }

    /** Only retire a drawn square when its real volume has actually reached a visible near tile. */
    private static void closureCoverage() {
        for (int face = 0; face < 6; face++) {
            int bits = 0, size = shape.size();
            for (int low = 0; low < 2; low++)
                for (int far = 0; far < 2; far++) {
                    int x = low == 0 ? 0 : size - 1, z = far == 0 ? 0 : size - 1;
                    long key = tileKey(face, x, z);
                    if (!window.contains(key)) continue;
                    BlockState state = null;
                    var point =
                            GroundClient.planePoint(
                                    planet,
                                    CubeSurfaceTopology.Face.VALUES[face],
                                    new BentStripGeometry.Point(x + .5, 0, z + .5));
                    if (window.contains(key) && point != null) {
                        var pos = BlockPos.containing(point.x(), point.y(), point.z());
                        if (level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
                            state = level.getChunkAt(pos).getBlockState(pos);
                    }
                    if (state != null && state.is(Anomaly.CORE_BLOCK)) bits |= 1 << (low * 2 + far);
                }
            if (bits != realCorners[face]) {
                realCorners[face] = bits;
                if (masks[face] != null) masks[face].close();
                if (masks[face + 6] != null) masks[face + 6].close();
                masks[face] = masks[face + 6] = null;
                maskAttempted[face] = maskAttempted[face + 6] = false;
            }
        }
    }

    private static MaskMesh buildMask(int face, boolean wedge) {
        try (var builders = new Builders()) {
            int core = mask.core(), height = mask.height();
            var state = Anomaly.shell();
            if (core == 0 || height <= -8) return builders.upload(0);
            var renderer = Minecraft.getInstance().getBlockRenderer();
            var shell = renderer.getBlockModel(state).getParticleIcon();
            var glow = renderer.getBlockModel(Anomaly.glow()).getParticleIcon();
            for (int low : new int[] {0, 1})
                for (int far : new int[] {0, 1}) {
                    // The face's own square lies inside the chart at each vertex; the wedge is its
                    // mirror image through that vertex.
                    int size = GroundClient.shape().size(),
                            a = low == 0 ? (wedge ? -core : 0) : (wedge ? size : size - core),
                            b = far == 0 ? (wedge ? -core : 0) : (wedge ? size : size - core);
                    boolean left = (low == 0) != wedge, near = (far == 0) != wedge;
                    var out = builders.get(RenderType.solid());
                    if (wedge || (realCorners[face] & (1 << (low * 2 + far))) == 0) {
                        if (wedge)
                            for (int x = 0; x < core; x += Anomaly.QUAD)
                                for (int z = 0; z < core; z += Anomaly.QUAD) {
                                    int w = Math.min(Anomaly.QUAD, core - x),
                                            d = Math.min(Anomaly.QUAD, core - z);
                                    wall(
                                            out,
                                            shell,
                                            Anomaly.SHELL_TINT,
                                            new float[][] {
                                                {a + x, height, b + z},
                                                {a + x, height, b + z + d},
                                                {a + x + w, height, b + z + d},
                                                {a + x + w, height, b + z}
                                            },
                                            0,
                                            0);
                                }
                        else {
                            int horizontal = Math.max(4, (core + 63) / 64);
                            for (int x = a; x < a + core; x += horizontal)
                                for (int z = b; z < b + core; z += horizontal)
                                    top(
                                            builders,
                                            state,
                                            x,
                                            height,
                                            z,
                                            Math.min(horizontal, a + core - x),
                                            Math.min(horizontal, b + core - z),
                                            false);
                        }
                        // Rows end on every seam, so a lit seam is its own quad and never shares a
                        // plane with the shell. Quads stay as
                        // small as terrain's: the projection decides visibility per vertex, and a
                        // tall quad seen from its foot is cut away.
                        for (long row = -8; row < height; ) {
                            boolean lit = Anomaly.band(row);
                            long next = row + 1;
                            while (next < height
                                    && next - row < Anomaly.QUAD
                                    && Anomaly.band(next) == lit) next++;
                            frustum(
                                    out,
                                    lit ? glow : shell,
                                    lit ? Anomaly.GLOW_TINT : Anomaly.SHELL_TINT,
                                    a,
                                    b,
                                    core,
                                    row,
                                    a,
                                    b,
                                    core,
                                    next);
                            row = next;
                        }
                    }
                    // The spire stands on the vertex itself and narrows smoothly; the quarters meet
                    // there.
                    for (int above = 0; above < Anomaly.BEAM_HEIGHT; above += Anomaly.QUAD) {
                        int top = Math.min(Anomaly.BEAM_HEIGHT, above + Anomaly.QUAD);
                        float w = (float) Math.min(core, Anomaly.beamWidth(above)),
                                v = (float) Math.min(core, Anomaly.beamWidth(top));
                        frustum(
                                out,
                                glow,
                                Anomaly.GLOW_TINT,
                                left ? a : a + core - w,
                                near ? b : b + core - w,
                                w,
                                height + above,
                                left ? a : a + core - v,
                                near ? b : b + core - v,
                                v,
                                height + top);
                    }
                }
            return builders.upload(0);
        }
    }

    /**
     * The four walls between a square (a,b,size) at height h and another at height high, each split
     * along its length.
     */
    private static void frustum(
            BufferBuilder out,
            TextureAtlasSprite sprite,
            int tint,
            float a,
            float b,
            float size,
            float h,
            float a2,
            float b2,
            float size2,
            float high) {
        int parts = Math.max(1, (int) Math.ceil(Math.max(size, size2) / Anomaly.QUAD));
        for (int k = 0; k < parts; k++) {
            float t = size * k / parts,
                    end = size * (k + 1) / parts,
                    t2 = size2 * k / parts,
                    end2 = size2 * (k + 1) / parts;
            wall(
                    out,
                    sprite,
                    tint,
                    new float[][] {
                        {a, h, b + t}, {a2, high, b2 + t2}, {a2, high, b2 + end2}, {a, h, b + end}
                    },
                    -1,
                    0);
            wall(
                    out,
                    sprite,
                    tint,
                    new float[][] {
                        {a + size, h, b + end},
                        {a2 + size2, high, b2 + end2},
                        {a2 + size2, high, b2 + t2},
                        {a + size, h, b + t}
                    },
                    1,
                    0);
            wall(
                    out,
                    sprite,
                    tint,
                    new float[][] {
                        {a + end, h, b}, {a2 + end2, high, b2}, {a2 + t2, high, b2}, {a + t, h, b}
                    },
                    0,
                    -1);
            wall(
                    out,
                    sprite,
                    tint,
                    new float[][] {
                        {a + t, h, b + size},
                        {a2 + t2, high, b2 + size2},
                        {a2 + end2, high, b2 + size2},
                        {a + end, h, b + size}
                    },
                    0,
                    1);
        }
    }

    private static void wall(
            BufferBuilder out,
            TextureAtlasSprite sprite,
            int tint,
            float[][] points,
            int nx,
            int nz) {
        for (int i = 0; i < 4; i++)
            out.addVertex(points[i][0], points[i][1], points[i][2])
                    .setColor(tint >> 16 & 255, tint >> 8 & 255, tint & 255, 255)
                    .setUv(
                            i < 2 ? sprite.getU0() : sprite.getU1(),
                            i == 0 || i == 3 ? sprite.getV1() : sprite.getV0())
                    .setUv2(240, 240)
                    .setNormal(nx, 0, nz);
    }

    static int surfaceLight(int face, BlockPos pos) {
        return surfaceLight(face, pos, null);
    }

    private static boolean loadedLightColumn(long chartChunk) {
        int x = net.minecraft.world.level.ChunkPos.getX(chartChunk) * 16 + GroundClient.storageOffset();
        int z = net.minecraft.world.level.ChunkPos.getZ(chartChunk) * 16 + GroundClient.storageOffsetZ();
        return level != null && level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    private static int loadedSurfaceLight(BlockPos pos) {
        var storage = GroundClient.storage(pos);
        if (level == null || !level.getLightEngine().lightOnInSection(net.minecraft.core.SectionPos.of(storage))) return -1;
        return level.getBrightness(LightLayer.BLOCK, storage) * 16
                | level.getBrightness(LightLayer.SKY, storage) * 4096;
    }

    private static int surfaceLight(int face, BlockPos pos, GroundMeshContext.Sky sky) {
        var storage = GroundClient.storage(pos);
        if (level != null
                && level.getChunkSource().hasChunk(storage.getX() >> 4, storage.getZ() >> 4)
                && level.getLightEngine()
                        .lightOnInSection(net.minecraft.core.SectionPos.of(storage)))
            return level.getBrightness(LightLayer.BLOCK, storage) * 16
                    | level.getBrightness(LightLayer.SKY, storage) * 4096;
        var terrain = GroundViewClient.terrain();
        int x = pos.getX() - face * 4096, z = pos.getZ();
        int water = sky == null ? GroundMeshContext.skyLevel(terrain, face, x, z)
                : sky.level(terrain, face, x, z);
        return GroundFarTexture.sky(water == Integer.MIN_VALUE ? 0 : water - pos.getY()) * 4096;
    }

    private GroundRenderer() {}
}
