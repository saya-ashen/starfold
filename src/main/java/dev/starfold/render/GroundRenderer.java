package dev.starfold.render;

import dev.starfold.storage.ChunkKeys;

import static dev.starfold.render.GroundMeshVertices.*;
import static dev.starfold.render.GroundTerrainMeshes.*;

import static dev.starfold.render.GroundMeshScheduler.completed;
import static dev.starfold.render.GroundMeshScheduler.lodFov;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetChart;
import dev.starfold.chart.PlanetGap;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.GroundTopology;
import dev.starfold.DevelopmentHooks;
import dev.starfold.space.RenderFrame;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;
import dev.starfold.worldgen.lod.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.util.*;

/** Derived native models plus conservative surface coverage. No material/name placement ledger. */
public final class GroundRenderer {
    private static void changedLod(long key, LodTile tile) {
        int groupKey = GroundLodGroup.key(key);
        var group = lodGroups.computeIfAbsent(groupKey, k -> new GroundLodGroup<>());
        group.update(key, tile);
        lodDrawCache.invalidate(group);
        if (group.members.isEmpty()) {
            lodGroups.remove(groupKey);
            lodCompactions.remove(group);
        } else lodCompactions.add(group);
    }
    private static final LinkedHashSet<Long> lodMaintenance = new LinkedHashSet<>();
    private static final ArrayDeque<Long> lodFades = new ArrayDeque<>();
    private static final PriorityQueue<GroundLodSelection.Tile> lodRequests =
            new PriorityQueue<>(GroundLodSelection.REBUILD_ORDER);
    private static final Set<Long> lodQueued = new HashSet<>();
    private static final BitSet lodComplete = new BitSet();
    private static GroundLodSelection lodSelection = new GroundLodSelection();
    private static GroundLodSelection.Refresh lodRefresh = new GroundLodSelection.Refresh();
    private static PlanetShape.Pose selectionPose;
    private static BentStripGeometry.Point selectionCamera;
    private static long selectionVersion, selectionNow;
    private static int farRemaining, maintenanceRemaining;
    private static boolean selectionChanged, lodStarted;
    // CPU meshing runs off the render thread; only GL upload stays here.
    private static final int LOD_THREADS =
            Math.clamp(Runtime.getRuntime().availableProcessors() / 3, 1, 6);
    private static final GroundMeshScheduler lodWorkers = GroundMeshScheduler.shared;
    private static final List<LodJob> lodJobs = new ArrayList<>();
    private static long lodBytes;
    private static boolean lodAtlasDirty;
    private static double lodPressure = Double.POSITIVE_INFINITY;

    private static long lodBytes(Mesh mesh) {
        if (mesh == null) return 0;
        return mesh.bytes
                + (mesh.surface == null
                        ? 0
                        : mesh.surface.memoryBytes()
                                + (mesh.surface.voxels() == null
                                        ? 0
                                        : mesh.surface.voxels().memoryBytes()));
    }

    private static void closeLodMesh(Mesh mesh) {
        if (mesh == null) return;
        lodBytes -= lodBytes(mesh) - mesh.bytes;
        mesh.close();
    }

    private static void clearLod() {
        for (var job : lodJobs) job.close();
        lodJobs.clear();
        clearLodBuffers();
        lodMaintenance.clear();
        lodFades.clear();
        lodRequests.clear();
        lodQueued.clear();
        lodComplete.clear();
        lodBytes = 0;
        lodSelection = new GroundLodSelection();
        lodRefresh = new GroundLodSelection.Refresh();
        farRemaining = maintenanceRemaining = 0;
        lodStarted = false;
        lodAtlasDirty = true;
        lodPressure = Double.POSITIVE_INFINITY;
    }


    // Only pure current geometry is cached: packed buffers never accumulate older generations.
    // shortcut: faded vertices remain in the buffer; compact only if vertex bandwidth is measured
    // as a bottleneck.
    private static final Map<Integer, List<VoxelMesher.Quad>> patchQuads = new HashMap<>(),
            stagedQuads = new HashMap<>();
    private static final boolean[] patchPaired = new boolean[GroundViewPolicy.PATCH_COUNT],
            stagedPaired = new boolean[GroundViewPolicy.PATCH_COUNT];

    private static final long[] patchCommitted = new long[GroundViewPolicy.PATCH_COUNT];
    private static final int[] patchLevels = new int[GroundViewPolicy.PATCH_COUNT],
            builtLevels = new int[GroundViewPolicy.PATCH_COUNT],
            drawnLevels = new int[GroundViewPolicy.PATCH_COUNT];

    private static final GroundCoverSnapshot[] coverInputs =
            new GroundCoverSnapshot[GroundViewPolicy.PATCH_COUNT];
    private static ParentJob parentJob;
    private static Mesh[] pendingStitches;
    private static StitchJob pendingJob;
    private static GroundOwnership.Snapshot residentOwnership = GroundOwnership.empty();
    private static GroundOwnership.Snapshot availableOwnership = GroundOwnership.empty();
    private static long coverEpoch, stagedEpoch;
    private static int nativeFace = -1;
    private static boolean selectionActive;
    private static long selectionWork;
    /** Development probe switch: the far view neither builds nor draws, vanilla terrain only. */
    public static boolean probeOff;
    /** Development probe totals over a sampling window: CPU and GPU time of the far draw, draws, tiles visited. */
    public static boolean probeTiming;
    public static long probeCpuNanos, probeGpuNanos, probeMultiNanos, probeSkyNanos, probePrepNanos;
    public static int probeDraws, probeTiles, probeSubDraws, probeGroups, probeCompactGroups;
    private static final int[] probeQueries = new int[8];
    private static int probeQuery;
    private static final GroundViewPolicy.CohortPatches dirty =
            new GroundViewPolicy.CohortPatches(GroundClient::shape);
    private static GroundCoverSnapshot coverCohort;
    private static BitSet priorCoverage = new BitSet();
    private static Set<Long> window = Set.of();
    // Several far patches in flight: one at a time costs a few frames each, about a minute per cohort.
    private static final List<CoverJob> coverJobs = new ArrayList<>();
    private static StitchJob stitchJob;
    private static boolean stitchDirty = true;
    private static long frameWork, lastCells;
    private static int uploads;
    private static final GroundCoverage coverage = new GroundCoverage();
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static GroundSpace.Config mask;
    private static boolean ready;
    private static int planet = -1;

    public static boolean covered() {
        return level == Minecraft.getInstance().level
                && planet == GroundClient.planet()
                && GroundClient.shape().equals(shape)
                && ready
                && GroundExterior.ready();
    }

    public static boolean selectionReady() {
        return selectionActive && coverage.texture() != 0;
    }

    public static int coverageTexture() {
        return coverage.texture();
    }

    public static boolean nativeVolume(int face) {
        return false;
    } // No face-wide ownership override: ground uses canonical full volumes.

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        GroundExterior.initialize(modBus);
        GroundCompact.initialize(modBus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) -> {
                    // Vanilla's FOV interpolation starts at zero before its first client tick.
                    if(event.usedConfiguredFov() && event.getFOV()>0) lodFov=event.getFOV();
                });
        // Selection and connectors become visible before vanilla terrain draws, not mid-frame.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_SKY || probeOff) return;
                    long start = System.nanoTime();
                    if (GroundClient.ready() && RendererGuard.pipelineSupported()) ensureState();
                    selectionActive =
                            GroundClient.ready()
                                    && RendererGuard.pipelineSupported()
                                    && level == Minecraft.getInstance().level
                                    && (ready || parentsComplete());
                    if (GroundClient.ready()
                            && RendererGuard.pipelineSupported()
                            && level == Minecraft.getInstance().level) prepareSurfaceUpdates();
                    if (selectionActive) updateExclusions();
                    else if (level == Minecraft.getInstance().level && GroundClient.ready()) {
                        // Partial startup cover must yield to submitted vanilla, not cover it.
                        window = GroundNativeCoverage.visible();
                        publishOwnership(coverage.ownership);
                    }
                    selectionWork = System.nanoTime() - start;
                    if (probeTiming) probeSkyNanos += selectionWork;
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_ENTITIES || probeOff) return;
                    var c = new RenderFrame(cpEvent);
                    if (!GroundClient.ready()
                            || !RendererGuard.pipelineSupported()
                            || c.matrixStack() == null) return;
                    refreshNativeDraws();
                    draw(c, false);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_PARTICLES || probeOff) return;
                    var c = new RenderFrame(cpEvent);
                    if (GroundClient.ready()
                            && RendererGuard.pipelineSupported()
                            && c.matrixStack() != null) {
                        refreshNativeDraws();
                        draw(c, true);
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

    private static volatile long meshSession;

    static long meshSession() { return meshSession; }

    public static void clear() {
        meshSession++;
        lodSourceTerrain = null;
        lodSource = null;
        farPyramids.clear();
        Arrays.fill(farVersions, 0);
        Arrays.fill(overlayVersions, 0);
        Arrays.fill(builtFarVersions, 0);
        selectionActive = false;
        selectionWork = 0;
        if (pendingStitches != null) {
            for (var mesh : pendingStitches) if (mesh != null) mesh.close();
            pendingStitches = null;
        }
        pendingJob = null;
        coverEpoch = stagedEpoch = 0;
        nativeFace = -1;
        clearParents();
        closeSlots(staging);
        patchQuads.clear();
        stagedQuads.clear();
        Arrays.fill(patchPaired, false);
        Arrays.fill(stagedPaired, false);
        Arrays.fill(patchLevels, 0);
        Arrays.fill(builtLevels, 0);
        Arrays.fill(drawnLevels, 0);
        GroundExterior.clear();
        Arrays.fill(coverInputs, null);
        if (stitchJob != null) {
            stitchJob.close();
            stitchJob = null;
        }
        closeSlots(stitches);
        stitchDirty = true;
        coverJobs.forEach(CoverJob::close);
        coverJobs.clear();
        clearLod();
        closeSlots(masks);
        closeSlots(patches);
        GroundCompact.clear();
        coverage.close();
        dirty.all();
        coverCohort = null;
        priorCoverage.clear();
        window = Set.of();
        level = null;
        planet = -1;
        mask = null;
        ready = false;
    }

    public static int probeParentTiles() {
        return parentJob == null ? -1 : parentJob.finished * 256;
    }

    public static int probeParents() {
        int n = 0;
        for (int face = 0; face < 6; face++) {
            boolean complete = true;
            for (int p = face * 64; p < (face + 1) * 64; p++)
                if (GroundViewPolicy.exists(GroundClient.shape(), p) && parents[p] == null)
                    complete = false;
            if (complete) n++;
        }
        return n;
    }

    public static String performance() {
        return "Parent coverage "
                + probeParents()
                + "/6 | derived build "
                + String.format(java.util.Locale.ROOT, "%.2f", frameWork / 1e6)
                + "ms | uploads "
                + uploads
                + " | dirty tiles "
                + dirty.count()
                + " | LOD "
                + lodTiles.size()
                + " / "
                + String.format(java.util.Locale.ROOT, "%.1f MiB", lodBytes / 1048576.0)
                + " | LOD queue "
                + lodRequests.size()
                + " | scanned "
                + lastCells;
    }

    /** Vertex payloads (driver overhead/shared index buffers excluded) and packed CPU payloads. */
    public static String memory() {
        var meshes=Collections.newSetFromMap(new IdentityHashMap<Mesh,Boolean>());
        for(var group:new Mesh[][]{masks,stitches,parents,patches,staging})
            for(var mesh:group) if(mesh!=null) meshes.add(mesh);
        var bands=new TreeMap<Integer,Integer>();
        for(var tile:lodTiles.values()) {
            meshes.add(tile.current); if(tile.previous!=null) meshes.add(tile.previous);
            bands.merge(tile.requested,1,Integer::sum);
        }
        var farBands=new TreeMap<Integer,Integer>();
        var stagedBands=new TreeMap<Integer,Integer>();
        int coarseParents=0;
        for(int p=0;p<patches.length;p++) {
            if(patches[p]!=null) farBands.merge(drawnLevels[p],1,Integer::sum);
            if(staging[p]!=null) stagedBands.merge(builtLevels[p],1,Integer::sum);
            if(parents[p]!=null) coarseParents++;
        }
        long gpu=meshes.stream().mapToLong(m->m.bytes).sum()+GroundExterior.gpuBytes()+GroundCompact.gpuBytes();
        long edits=GroundViewClient.overlays().values().stream().mapToLong(GroundSurface::memoryBytes).sum();
        long quads=patchQuads.values().stream().mapToLong(List::size).sum()+stagedQuads.values().stream().mapToLong(List::size).sum();
        var runtime=Runtime.getRuntime();
        return "LOD_MEMORY basePackedBytes="+GroundBaseCache.retainedBytes()+" fineLruBytes="+GroundBaseCache.fineBytes()+" overlayPackedBytes="+edits
                +" gpuVertexBytes="+gpu+" retainedPatchQuads="+quads
                +" heapUsedBytes="+(runtime.totalMemory()-runtime.freeMemory())
                +" heapCommittedBytes="+runtime.totalMemory()+" residentAccountedBytes="+lodBytes
                +" residentBands="+bands+" farBands="+farBands+" stagedFarBands="+stagedBands+" L64Parents="+coarseParents
                +" farBuild="+(coverJobs.isEmpty() ? "idle":coverJobs.size()+" building")
                +" fov="+lodFov+" windowHeight="+Minecraft.getInstance().getWindow().getHeight()
                +" edits="+GroundViewClient.overlays().entrySet().stream().map(e->{
                    var tile=lodTiles.get(e.getKey());
                    return e.getKey()+":"+Arrays.stream(e.getValue().heights()).max().orElse(-1)+":"
                            +(tile==null ? "absent":tile.current.surface==null ? "base":tile.current.surface.revision())
                            +"/"+e.getValue().revision();
                }).toList();
    }

    private static PlanetShape shape;
    private static final int[] realCorners = new int[6];

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
            }
        }
    }

    private static boolean parentsComplete() {
        for (int p = 0; p < parents.length; p++)
            if (GroundViewPolicy.exists(shape, p) && parents[p] == null) return false;
        return true;
    }

    /** Every patch the observed planet has is built. */
    private static boolean patchesComplete() {
        var now = GroundClient.shape();
        for (int p = 0; p < patches.length; p++)
            if (patches[p] == null && GroundViewPolicy.exists(now, p)) return false;
        return true;
    }

    private static void ensureState() {
        var mc = Minecraft.getInstance();
        // Another planet is another surface: nothing built for the last one is kept, and its
        // patches need not exist here.
        var observed = GroundClient.shape();
        int observedPlanet = GroundClient.planet();
        if (level != mc.level || planet != observedPlanet || !observed.equals(shape)) {
            clear();
            level = mc.level;
            planet = observedPlanet;
            shape = observed;
        }
        var current = GroundFlightServer.mask(level);
        if (!current.equals(mask)) {
            mask = current;
            Arrays.fill(coverInputs, null);
            clearParents();
            stitchDirty = true;
            dirty.all();
            coverCohort = null;
            if (stitchJob != null) {
                stitchJob.close();
                stitchJob = null;
            }
            if (pendingStitches != null) {
                for (var m : pendingStitches) m.close();
                pendingStitches = null;
            }
            pendingJob = null;
            coverJobs.forEach(CoverJob::close);
            coverJobs.clear();
            for (int i = 0; i < masks.length; i++) {
                if (masks[i] != null) masks[i].close();
                masks[i] = null;
            }
                clearLod();
        }
    }

    private static void draw(RenderFrame c, boolean translucent) {
        var mc = Minecraft.getInstance();
        long prep = System.nanoTime();
        ensureState();
        if (GroundViewClient.terrain() == null || lodSource(GroundViewClient.terrain()) == null) return;
        refreshCoverage();
        closureCoverage();
        if (!translucent) updateMeshes();
        if (probeTiming) probePrepNanos += System.nanoTime() - prep;
        if (!probeTiming) {
            drawLayers(c, translucent);
            return;
        }
        if (probeQueries[0] == 0) GL15.glGenQueries(probeQueries);
        int query = probeQueries[probeQuery++ % probeQueries.length];
        // The slot was last used a few frames ago; its result is ready or nearly so.
        if (probeQuery > probeQueries.length)
            probeGpuNanos += GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT);
        long start = System.nanoTime();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
        drawLayers(c, translucent);
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        probeCpuNanos += System.nanoTime() - start;
    }

    private static void updateMeshes() {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.MESH);
        try {
        long start = System.nanoTime();
        // Near volumes are the only terrain shown on the ground: while any is meshing or
        // waiting to
        // commit, spend the larger near budget. Idle frames and background cover keep the base
        // budget.
        boolean parentsReady = parentsComplete();
        long deadline =
                start
                        + (!parentsReady
                                        || !lodJobs.isEmpty()
                                        || stitchJob != null
                                ? GroundViewPolicy.NEAR_BUDGET_NS
                                : GroundViewPolicy.FRAME_BUDGET_NS);
        uploads = 0;
        lastCells = 0;
        long readyBytes = 0;
        for (var resident : lodJobs)
            if (resident.quads.state() == java.util.concurrent.Future.State.SUCCESS)
                readyBytes += resident.quads.resultNow().size() * 4L * GroundCompact.FORMAT.getVertexSize();
        for (var cover : coverJobs)
            if (cover.done()) readyBytes += (cover.quads.size() + cover.previous.size()) * 96L;
        lodWorkers.readyBytes(readyBytes);
        selectionPose = shape.canonical(CubeSurfaceTopology.Face.VALUES[GroundClient.face()], GroundClient.eye()).pose();
        selectionCamera = shape.position(selectionPose);
        for (int face = 0; face < 6 && System.nanoTime() < deadline; face++)
            if (masks[face] == null) {
                masks[face] = buildMask(face, false);
                masks[face + 6] = buildMask(face, true);
                uploads++;
            }
        // Build the first complete cover promptly; refinements then return to their background
        // share.
        long coverDeadline = start + GroundViewPolicy.coverBudget(parentsReady);
        // Commit/build before optional discovery, which can otherwise consume the whole share.
        updateCover(coverDeadline);
        var look = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
        selectionNow = System.nanoTime();
        selectionChanged = lodRefresh.update(selectionPose, lodFov,
                Minecraft.getInstance().getWindow().getHeight(), selectionVersion,
                look.x(), look.y(), look.z(), selectionNow);
        if (selectionChanged) {
            farRemaining = GroundViewPolicy.PATCH_COUNT;
            maintenanceRemaining = lodMaintenance.size();
        }
        selectFarLevels(coverDeadline);
        // Own budget: earlier cover/parent work must not starve resident scheduling and drains.
        if (parentsReady) updateLod(System.nanoTime() + GroundViewPolicy.NEAR_BUDGET_NS / 2);
        ready = GroundViewClient.coverageReady() && patchesComplete();
        updateStitches(deadline);
        // Only G changes here. Vanilla's R ownership was fixed before its draw began.
        if (lodAtlasDirty && coverage.texture() != 0) publishOwnership(coverage.ownership);
        frameWork = System.nanoTime() - start + selectionWork;
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.MESH);
        }
    }

    private static void updateCover(long coverDeadline) {
        farPyramids.focus(selectionCamera);
        farPyramids.poll().stream()
                .forEach(
                        p -> {
                            farVersions[p]++;
                            selectionVersion++;
                            dirty.patch(p);
                        });
        var overlays = farPyramids.polledOverlays();
        for (int p = overlays.nextSetBit(0); p >= 0; p = overlays.nextSetBit(p + 1)) overlayVersions[p]++;
        if (GroundViewClient.coverageReady()
                && !parentsComplete()
                && System.nanoTime() < coverDeadline) {
            long parentStart = DevelopmentHooks.arrival.start();
            if (parentJob == null) parentJob = new ParentJob();
            parentJob.step(coverDeadline);
            if (DevelopmentHooks.arrival.enabled())
                DevelopmentHooks.parentNanos += System.nanoTime() - parentStart;
            if (parentJob.done()) {
                parentJob.close();
                parentJob = null;
                stitchDirty = true;
            }
        }
        if (GroundViewClient.coverageReady()
                && parentsComplete()
                && System.nanoTime() < coverDeadline) {
            // A latched cohort polls each patch once, so jobs in flight never share a patch.
            int polled = 0;
            while (coverJobs.size() < LOD_THREADS && lodWorkers.canSubmit(true)
                    && polled++ < 64 && System.nanoTime() < coverDeadline) {
                if (coverCohort == null && dirty.count() != 0) {
                    coverCohort =
                            GroundCoverSnapshot.far(
                                    GroundViewClient.heights(),
                                    GroundViewClient.overlays(),
                                    GroundViewClient.changed());
                    dirty.latch();
                }
                int patch = dirty.poll();
                if (patch < 0) break;
                if (patchPaired[patch]
                        && VoxelLodPolicy.fade(patchCommitted[patch], System.nanoTime()) < 1) {
                    dirty.tile(
                            tileKey(
                                    GroundViewPolicy.face(patch),
                                    GroundViewPolicy.x(patch),
                                    GroundViewPolicy.z(patch)));
                    break;
                }
                var inputs =
                        coverCohort.farPatch(
                                GroundViewPolicy.face(patch),
                                GroundViewPolicy.x(patch),
                                GroundViewPolicy.z(patch));
                if (!inputs.sameInputs(coverInputs[patch])
                        || builtLevels[patch] != patchLevels[patch]
                        || builtFarVersions[patch] != farVersions[patch])
                    coverJobs.add(new CoverJob(patch, inputs));
            }
            for (var it = coverJobs.iterator(); it.hasNext(); ) {
                var coverJob = it.next();
                coverJob.step(coverDeadline);
                if (!coverJob.done()) continue;
                it.remove();
                int patch = coverJob.patch;
                var inputs = coverJob.builders.cover;
                var mesh = coverJob.finish();
                coverInputs[patch] = inputs;
                builtLevels[patch] = coverJob.step;
                builtFarVersions[patch] = coverJob.farVersion;
                stagedQuads.put(patch, coverJob.quads);
                stagedPaired[patch] = !coverJob.previous.isEmpty();
                var old = staging[patch];
                staging[patch] = mesh;
                stagedEpoch++;
                if (old != null) old.close();
                uploads++;
            }
            if (coverCohort != null
                    && dirty.count() == 0
                    && coverJobs.isEmpty()
                    && Arrays.stream(staging).allMatch(Objects::isNull)) {
                coverCohort = null;
                dirty.promote();
            }
        }
    }

    private static final GroundMipCache farPyramids = new GroundMipCache();
    private static final long[] farVersions = new long[GroundViewPolicy.PATCH_COUNT];
    // Captured-column changes only: L1/L2 tiles read fine columns, never the coarse base mips.
    private static final long[] overlayVersions = new long[GroundViewPolicy.PATCH_COUNT];
    private static final long[] builtFarVersions = new long[GroundViewPolicy.PATCH_COUNT];
    private static int farCursor;

    private static void selectFarLevels(long deadline) {
        if (!GroundViewClient.coverageReady()) return;
        var camera = selectionCamera;
        // Complete a bounded sweep only when its spatial/projection/data inputs changed.
        for (int work = 0; work < 8 && farRemaining > 0 && System.nanoTime() < deadline; work++, farRemaining--) {
            int p = farCursor;
            farCursor = (farCursor + 1) % GroundViewPolicy.PATCH_COUNT;
            if (!GroundViewPolicy.exists(shape, p)) continue;
            var mesh = patches[p] == null ? parents[p] : patches[p];
            double distance =
                    mesh == null ? Double.POSITIVE_INFINITY : mesh.cover.distance(shape, camera, p);
            if (!Double.isFinite(distance)) {
                var heights = GroundViewClient.heights();
                double h =
                        heights == null
                                ? 0
                                : heights.surfaceNode(
                                        CubeSurfaceTopology.Face.VALUES[GroundViewPolicy.face(p)],
                                        Math.min(shape.size(), GroundViewPolicy.x(p) + 128),
                                        Math.min(shape.size(), GroundViewPolicy.z(p) + 128));
                distance = GroundFarMesh.distance(shape, camera, p, h);
            }
            int next = Double.isFinite(distance) ? GroundFarMesh.level(distance, patchLevels[p],lodFov,
                    Minecraft.getInstance().getWindow().getHeight()) : 64;
            if (next != patchLevels[p]) {
                patchLevels[p] = next;
                dirty.tile(
                        tileKey(
                                GroundViewPolicy.face(p),
                                GroundViewPolicy.x(p),
                                GroundViewPolicy.z(p)));
            }
        }
    }

    static java.util.concurrent.Future<List<VoxelMesher.Quad>> farMesh(
            PlanetShape observed,
            int patch,
            int step,
            PlanetTerrain terrain,
            GroundCoverSnapshot cover,
            GroundMipCache pyramids) {
        var field = pyramids==farPyramids
                ? pyramids.field(observed,terrain,GroundViewClient::overlay,patch)
                : pyramids.field(observed, terrain, cover, patch);
        return lodWorkers.farMesh(observed, patch, step, field);
    }

    private static double lodHeight(long key) {
        var image = GroundViewClient.surface(key);
        if (image != null) return image.height(8, 8) + 1 - 128;
        return GroundTransition.generated(
                GroundViewClient.heights(),
                CubeSurfaceTopology.Face.VALUES[(int) (key / 16384)],
                key % 128 * 16 + 8,
                key / 128 % 128 * 16 + 8);
    }

    /** In orbit the drawn disk is flatter than the sphere and reaches far past its horizon: use the
     *  orbit shader's own facing and range clip. Near the ground the sphere horizon stays. */
    private static java.util.function.LongPredicate displayed(BentStripGeometry.Point camera) {
        var e = GroundClient.eye();
        if (e.y() <= GroundOrbit.START)
            return key ->
                    VoxelLodPolicy.horizonVisible(
                            shape,
                            camera,
                            CubeSurfaceTopology.Face.VALUES[(int) (key / 16384)],
                            (int) (key % 128) * 16,
                            (int) (key / 128 % 128) * 16);
        var orbit = GroundClient.orbit();
        // Half a tile diagonal beyond the clip, so edge tiles are not left coarse.
        double range = GroundClient.range(e, orbit, GroundClient.gap()) + 12;
        return key -> {
            var face = CubeSurfaceTopology.Face.VALUES[(int) (key / 16384)];
            var p =
                    new BentStripGeometry.Point(
                            key % 128 * 16 + 8, lodHeight(key), key / 128 % 128 * 16 + 8);
            if (shape.direction(face, p.x(), p.z()).dot(orbit.normal) < orbit.minimumFacing - .01)
                return false;
            var q = orbit.point(face, p);
            return Math.hypot(q.x() - e.x(), q.z() - e.z()) <= range;
        };
    }

    private static void requestLod(GroundLodSelection.Tile tile, int step) {
        var old = lodTiles.get(tile.key());
        if (old != null
                && (old.retiring
                        || old.fadeQueued
                        || old.requested == step
                                && !lodInputsChanged(old, GroundViewClient.surface(tile.key()))))
            return;
        double priority=tile.distance();
        if(lodFov<30) {
            var chart = GroundClient.projectedFrame() ? GroundClient.gap().chart
                    : new PlanetChart(shape,CubeSurfaceTopology.Face.VALUES[GroundClient.face()]);
            var plot=chart.tile(CubeSurfaceTopology.Face.VALUES[(int)(tile.key()/16384)]);
            if(plot!=null) {
                var target=plot.point(new BentStripGeometry.Point(tile.key()%128*16+8,lodHeight(tile.key()),tile.key()/128%128*16+8))
                        .sub(GroundClient.eye());
                var look=Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
                priority+=(1-(target.x()*look.x()+target.y()*look.y()+target.z()*look.z())/target.length())*100000;
            } else priority+=200000;
        }
        if (lodQueued.size() < VoxelLodPolicy.RESIDENT_TILES && lodQueued.add(tile.key()))
            lodRequests.add(new GroundLodSelection.Tile(tile.key(),priority,GroundViewClient.overlay(tile.key())!=null));
    }

    private static boolean lodInputsChanged(LodTile tile, GroundSurface image) {
        if (tile.observed != image) {
            tile.observed = image;
            tile.dirty =
                    tile.current.surface == null
                            ? image != null
                            : image == null || !tile.current.surface.sameColumns(image);
        }
        return tile.dirty || tile.mipVersion != mipVersion(tile.mipPatch, tile.requested);
    }

    /** A rebuild input version for a tile at {@code step}; base-mip progress cannot change L1/L2. */
    private static long mipVersion(int patch, int step) {
        return step >= 4 ? farVersions[patch] : overlayVersions[patch];
    }

    private static void retireLod(long key, LodTile tile, long now) {
        if (tile.retiring) return;
        changedLod(key, tile);
        // The coarse layer is already committed. Restore it before fading/releasing the resident.
        lodComplete.clear((int) key);
        lodAtlasDirty = true;
        closeLodMesh(tile.previous);
        tile.previous = null;
        tile.retiring = true;
        tile.committed = now;
        if (!tile.fadeQueued) {
            lodFades.add(key);
            tile.fadeQueued = true;
        }
    }

    private static void updateLod(long deadline) {
        if (!GroundViewClient.coverageReady()) return;
        if (!lodStarted) {
            selectionChanged = true;
            lodStarted = true;
        }
        var camera = selectionCamera;
        if (selectionChanged) {
            lodSelection.visible(displayed(camera));
            lodSelection.projection(lodFov,Minecraft.getInstance().getWindow().getHeight(),selectionNow);
        }
        if (selectionChanged && lodSelection.move(shape, selectionPose, camera, GroundRenderer::lodHeight)) {
            lodRequests.clear();
            lodQueued.clear();
            lodPressure = Double.POSITIVE_INFINITY;
            lodJobs.removeIf(
                    job -> {
                        double distance =
                                GroundLodSelection.distance(
                                        shape, camera, job.key, lodHeight(job.key));
                        int step = lodSelection.level(job.key, distance);
                        if (step != 0 && step <= job.requested && !window.contains(job.key))
                            return false;
                        job.close();
                        return true;
                    });
        }
        long now = System.nanoTime();
        // Poll transitions separately: stable far residents must not delay a half-second swap.
        int fading = Math.min(128, lodFades.size());
        while (fading-- > 0 && System.nanoTime() < deadline) {
            long key = lodFades.remove();
            var tile = lodTiles.get(key);
            if (tile == null) continue;
            if (VoxelLodPolicy.fade(tile.committed, now) < 1) {
                lodFades.add(key);
                continue;
            }
            tile.fadeQueued = false;
            closeLodMesh(tile.previous);
            tile.previous = null;
            if (tile.retiring) {
                closeLodMesh(tile.current);
                lodTiles.remove(key);
                changedLod(key, null);
                lodMaintenance.remove(key);
            } else {
                changedLod(key, tile);
                lodComplete.set((int) key);
                lodAtlasDirty = true;
                maintenanceRemaining = lodMaintenance.size();
            }
        }
        // Bound housekeeping too; retained geometry is safe if fast motion outruns this queue.
        int count = Math.min(128, Math.min(maintenanceRemaining, lodMaintenance.size()));
        while (count-- > 0 && System.nanoTime() < deadline) {
            maintenanceRemaining--;
            long key = lodMaintenance.removeFirst();
            var tile = lodTiles.get(key);
            if (tile == null) continue;
            lodMaintenance.add(key);
            if (tile.retiring) continue;
            double distance = GroundLodSelection.distance(shape, camera, key, lodHeight(key));
            int step = lodSelection.level(key, distance);
            if (step == 0 || distance > Math.max(256, lodPressure + 64)) retireLod(key, tile, now);
            else if (!tile.fadeQueued) requestLod(new GroundLodSelection.Tile(key, distance), step);
        }
        // Discovery gets at most a quarter of this frame's existing base budget.
        lodSelection.step(
                shape,
                Math.min(deadline, System.nanoTime() + GroundViewPolicy.FRAME_BUDGET_NS / 4),
                System::nanoTime,
                GroundRenderer::lodHeight,
                GroundRenderer::requestLod);
        while (lodJobs.size() < 4 * LOD_THREADS
                && lodWorkers.canSubmit(false)
                && !lodRequests.isEmpty()
                && System.nanoTime() < deadline) {
            var next = lodRequests.peek();
            double distance =
                    GroundLodSelection.distance(shape, camera, next.key(), lodHeight(next.key()));
            int step = lodSelection.level(next.key(), distance);
            var prior = lodTiles.get(next.key());
            var image = GroundViewClient.surface(next.key());
            if (step == 0
                    || window.contains(next.key())
                    || prior != null
                            && (prior.retiring
                                    || prior.fadeQueued
                                    || prior.requested == step && !lodInputsChanged(prior, image))
                    || image == null && GroundViewClient.terrain() == null
                    || GroundViewClient.isChanged(next.key()) && image == null
                    || lodJobs.stream().anyMatch(j -> j.key == next.key())) {
                lodRequests.remove();
                lodQueued.remove(next.key());
                continue;
            }
            if (!VoxelLodPolicy.admits(
                    lodBytes + lodJobs.size() * VoxelLodPolicy.TILE_BYTES,
                    VoxelLodPolicy.TILE_BYTES,
                    lodTiles.size() + lodJobs.size())) {
                lodPressure = distance;
                break;
            }
            lodPressure = Double.POSITIVE_INFINITY;
            lodRequests.remove();
            lodQueued.remove(next.key());
            lodJobs.add(new LodJob(next.key(), step, image));
        }
        if (lodRequests.isEmpty()) lodPressure = Double.POSITIVE_INFINITY;
        for (var it = lodJobs.iterator(); it.hasNext() && System.nanoTime() < deadline; ) {
            var job = it.next();
            if (!job.drain(deadline)) continue;
            it.remove();
            if (job.failed()) {
                job.close();
                continue;
            }
            {
                long key = job.key;
                int step = job.requested;
                var mesh = job.finish();
                if (mesh == null) continue; // Arena capacity hit the resident byte ceiling.
                // Current + fading previous + one candidate share the same byte ceiling.
                if (VoxelLodPolicy.admits(lodBytes, lodBytes(mesh), lodTiles.size())) {
                    var old = lodTiles.get(key);
                    if (old != null) closeLodMesh(old.previous);
                    var tile =
                            new LodTile(
                                    mesh,
                                    old == null ? null : old.current,
                                    step,
                                    System.nanoTime(),
                                    key,
                                    job.mipVersion);
                    if (old == null || !old.fadeQueued) lodFades.add(key);
                    tile.fadeQueued = true;
                    lodTiles.put(key, tile);
                    changedLod(key, tile);
                    lodBytes += lodBytes(mesh) - mesh.bytes;
                    if (old == null) lodMaintenance.add(key);
                    uploads++;
                } else mesh.close();
            }
        }
    }

    private static PlanetTerrain lodSourceTerrain;
    private static dev.starfold.worldgen.surface.SurfaceFunction lodSource;

    private static dev.starfold.worldgen.surface.SurfaceFunction lodSource(PlanetTerrain terrain) {
        if (terrain == null) return null;
        var ready = terrain.readySurface();
        if (ready != null) {
            GroundBaseCache.observe(terrain);
            lodSourceTerrain = terrain;
            lodSource = ready;
        }
        return terrain == lodSourceTerrain ? lodSource : null;
    }

    private static final class LodJob implements AutoCloseable {
        final long key;
        final int requested, face, x, z;
        final long mipVersion;
        final Builders builders = new Builders(true, true);
        final java.util.concurrent.Future<List<VoxelMesher.Quad>> quads;
        List<VoxelMesher.Quad> ready;
        private final GroundMeshLease lease = new GroundMeshLease(builders::close);

        LodJob(long key, int requested, GroundSurface image) {
            this.key = key;
            this.requested = requested;
            mipVersion = mipVersion(GroundViewPolicy.patch(key), requested);
            face = (int) (key / 16384);
            x = (int) (key % 128) * 16;
            z = (int) (key / 128 % 128) * 16;
            builders.surfaceFace = face;
            builders.lodKey = key;
            builders.image = image;
            builders.cover = GroundCoverSnapshot.parent(GroundViewClient.heights(), new BitSet());
            var observed = shape;
            int patch = GroundViewPolicy.patch(key);
            // Freeze captured column inputs here; workers never access the live view or height
            // cache.
            var field =
                    farPyramids.field(
                            observed,
                            GroundViewClient.terrain(),
                            GroundViewClient::overlay,
                            patch);
            // Vertex building (models, atlas, biome tint) runs on the worker like vanilla section
            // compiles; the render thread only uploads.
            quads =
                    lodWorkers.submit(
                            () -> {
                                var built =
                                        GroundFarMesh.build(
                                                observed,
                                                CubeSurfaceTopology.Face.VALUES[face],
                                                x,
                                                z,
                                                16,
                                                requested,
                                                field);
                                if (!lease.begin()) return List.<VoxelMesher.Quad>of();
                                try {
                                    GroundMeshResources.read(builders.context.resources(), () -> {
                                    for (var q : built)
                                        surfacePolygon(
                                                builders,
                                                q.material(),
                                                q.points(),
                                                x,
                                                z,
                                                true,
                                                GroundShaderSource.patchTag(false, GroundFarMesh.side(q)),
                                                q.rgb());
                                    });
                                } finally {
                                    lease.end();
                                }
                                return built;
                            });
        }

        /** True once the worker has built and fed the tile (or failed). */
        boolean drain(long deadline) {
            if (ready == null) {
                if (!quads.isDone()) return false;
                try {
                    ready = quads.get();
                } catch (Exception failure) {
                    org.slf4j.LoggerFactory.getLogger("starfold")
                            .warn("LOD tile {} failed", key, failure);
                    ready = List.of();
                }
            }
            return true;
        }

        boolean failed() {
            return ready == null || ready.isEmpty();
        }

        Mesh finish() {
            try {
                return builders.upload(0);
            } finally {
                close();
            }
        }

        public void close() {
            quads.cancel(false);
            lease.close();
        }
    }

    // Repaired grid nodes for this frame's gap; kept across frames only for its capacity.
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BentStripGeometry.Point> lodNodes =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    private static boolean compactLod(GroundLodGroup<LodTile> group) {
        var counts = new LinkedHashMap<RenderType, Integer>();
        AABB bounds = null;
        for (var tile : group.members.values()) {
            var mesh = tile.current;
            if (mesh.bounds != null) bounds = bounds == null ? mesh.bounds : bounds.minmax(mesh.bounds);
            for (var e : mesh.lodLayers.entrySet()) counts.merge(e.getKey(), e.getValue().vertices(), Integer::sum);
        }
        // Reserve every destination before freeing any source: same-buffer copies cannot overlap.
        var blocks = new LinkedHashMap<RenderType, GroundLodArena.Range>();
        int face = (int)(group.members.keySet().iterator().next() / 16384);
        for (var e : counts.entrySet()) {
            var target = lodArenas.get(face).get(e.getKey());
            long before = target.bytes();
            var block = target.allocate(e.getValue(), VoxelLodPolicy.RESIDENT_BYTES - lodBytes);
            lodBytes += target.bytes() - before;
            if (block == null) {
                for (var allocated : blocks.values()) allocated.close();
                return false;
            }
            blocks.put(e.getKey(), block);
        }
        for (var e : blocks.entrySet()) {
            int offset = 0;
            for (var tile : group.members.values()) {
                var ranges = tile.current.lodLayers;
                var source = ranges.get(e.getKey());
                if (source == null) continue;
                var range = e.getValue().slice(offset, source.vertices());
                source.arena().copy(source, range);
                source.close();
                ranges.put(e.getKey(), range);
                offset += range.vertices();
            }
        }
        group.layers.putAll(blocks);
        group.bounds = bounds;
        group.compact = true;
        lodDrawCache.invalidate(group);
        return true;
    }

    private static void compactLods() {
        long now = System.nanoTime();
        // Round-robin, including budget failures: at most four groups (64 members) checked/copied.
        int count = Math.min(4, lodCompactions.size());
        while (count-- > 0) {
            var group = lodCompactions.removeFirst();
            boolean settled = true;
            for (var tile : group.members.values()) {
                probeTiles++;
                if (tile.previous != null || tile.retiring || VoxelLodPolicy.fade(tile.committed, now) != 1) {
                    settled = false;
                    break;
                }
            }
            if (!settled || !compactLod(group)) lodCompactions.add(group);
        }
    }

    private static boolean visibleLod(RenderFrame c, int face, AABB bounds, PlanetGap gap) {
        if (gap == null || bounds == null) return true;
        bounds = GroundLodBounds.planar(bounds, gap, CubeSurfaceTopology.Face.VALUES[face], lodNodes);
        if (bounds == null) return true;
        var frame = GroundClient.frame();
        var origin = GroundClient.origin();
        var a = frame.toClient(origin.add(new BentStripGeometry.Point(bounds.minX, bounds.minY, bounds.minZ)));
        var b = frame.toClient(origin.add(new BentStripGeometry.Point(bounds.maxX, bounds.maxY, bounds.maxZ)));
        bounds = new AABB(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
        var eye = c.camera().getPosition();
        // Closure walls are pushed along the eye ray in the shader.
        double dx = Math.max(Math.abs(bounds.minX-eye.x), Math.abs(bounds.maxX-eye.x));
        double dy = Math.max(Math.abs(bounds.minY-eye.y), Math.abs(bounds.maxY-eye.y));
        double dz = Math.max(Math.abs(bounds.minZ-eye.z), Math.abs(bounds.maxZ-eye.z));
        double radius = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double scale = 1 + Math.max(.002, 3e-6 * radius);
        var pushed = new AABB(eye.x + (bounds.minX-eye.x)*scale, eye.y + (bounds.minY-eye.y)*scale, eye.z + (bounds.minZ-eye.z)*scale,
                eye.x + (bounds.maxX-eye.x)*scale, eye.y + (bounds.maxY-eye.y)*scale, eye.z + (bounds.maxZ-eye.z)*scale);
        return c.event().getFrustum().isVisible(bounds.minmax(pushed).inflate(1));
    }

    private static void prepareLodDraws(RenderFrame c) {
        compactLods();
        // Above the ground chart the shader changes the screen lens too; vanilla's frustum
        // cannot conservatively test those positions. Keep them until a lens-aware culler exists.
        var gap = GroundClient.eye().y() <= GroundOrbit.START && !GroundFlightClient.controlled()
                ? GroundClient.gap() : null;
        lodNodes.clear();
        // The orbital path has no camera culling; only mesh/fade/group changes affect its draws.
        if (lodDrawCache.prepare(lodGroups, gap != null, !lodFades.isEmpty(),
                (face, bounds) -> visibleLod(c, face, bounds, gap))) {
            probeTiles += lodDrawCache.visited;
            probeGroups += lodDrawCache.groups;
            probeCompactGroups += lodDrawCache.compact;
        }
    }

    private static void drawLods(RenderFrame c, int face, RenderType layer) {
        var tiles = lodDrawCache.faces.get(face).get(layer);
        if (tiles == null || tiles.isEmpty()) return;
        var arena = lodArenas.get(face).get(layer);
        if (arena == null) return;
        var shader = GroundCompact.shader();
        if (shader == null) return;
        GroundClient.source(face, true, true);
        GroundClient.cover(true);
        GroundClient.lod(0, 1);
        var matrix =
                GroundClient.place(
                        new Matrix4f(RenderSystem.getModelViewMatrix())
                                .mul(c.matrixStack().last().pose()),
                        face,
                        c.camera().getPosition());
        // All resident buffers use the same format/program and face transform. Do not upload
        // the entire ground/atmosphere uniform set for every 16-block tile.
        shader.setDefaultUniforms(
                VertexFormat.Mode.QUADS,
                matrix,
                RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        boolean rebuild = arena.begin(tiles.size(), lodDrawCache.epoch);
        long now = System.nanoTime();
        try {
            shader.apply();
            if (rebuild) for (var draw : tiles) {
                probeTiles++;
                var tile = draw.tile();
                var range = draw.range();
                if (tile == null) {
                    arena.add(range);
                    continue;
                }
                float fade = VoxelLodPolicy.fade(tile.committed, now);
                if (range != null && tile.previous == null && !tile.retiring && fade == 1) {
                    arena.add(range);
                    continue;
                }
                if (tile.previous != null) drawLodMesh(tile.previous, shader, 1, layer);
                drawLodMesh(tile.current, shader, tile.retiring ? 1 - fade : fade, layer);
            }
            ShaderUniforms.set(shader, "CPNativeLodFade", 1);
            arena.draw();
        } finally {
            shader.clear();
            VertexBuffer.unbind();
            GroundClient.sourceEnd();
        }
    }

    private static void drawLodMesh(Mesh mesh, ShaderInstance shader, float fade, RenderType layer) {
        var range = mesh.lodLayers.get(layer);
        if (range == null || fade <= 0) return;
        ShaderUniforms.set(shader, "CPNativeLodFade", fade);
        range.arena().draw(range);
    }

    private static void updateStitches(long deadline) {
        if (stitchJob != null && System.nanoTime() < deadline) {
            stitchJob.step(deadline);
            if (stitchJob.done()) {
                pendingJob = stitchJob;
                pendingStitches = stitchJob.finish();
                stitchJob = null;
                uploads++;
            }
        }
    }

    private static void drawLayers(RenderFrame c, boolean translucent) {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.FAR);
        try {
        if (!translucent) prepareLodDraws(c);
        if (DevelopmentHooks.arrival.enabled()
                && !translucent
                && selectionReady()
                && (ready || parentsComplete())) DevelopmentHooks.ownDraw = true;
        var pass = new RenderPassState(true, true, false);
        int texture0 = RenderSystem.getShaderTexture(0),
                texture1 = RenderSystem.getShaderTexture(1),
                texture2 = RenderSystem.getShaderTexture(2);
        int activeTexture = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        var coverage = new CoverageBinding();
        boolean orbitalChart = GroundClient.eye().y() > GroundOrbit.START;
        var chart = orbitalChart ? null : GroundClient.projectedFrame() ? GroundClient.gap().chart
                : new PlanetChart(GroundClient.shape(), CubeSurfaceTopology.Face.VALUES[GroundClient.face()]);
        try (var filtering = GroundTextureFilter.open()) {
            GroundDrawPlan.draw(translucent, orbitalChart, GroundClient.face(),
                    selectionReady() && ready,
                    face -> chart.tile(CubeSurfaceTopology.Face.VALUES[face]) != null,
                    new GroundDrawPlan.Draw() {
                        private RenderType layer;
                        public void begin(RenderType value) {
                            layer = value;
                            layer.setupRenderState();
                        }
                        public void mask(int face, boolean residual) {
                            render(c, face, masks[face + (residual ? 6 : 0)], layer);
                        }
                        public void stitch(int face) { render(c, face, stitches[face], layer, true); }
                        public void surface(int face, int patch, boolean ready) {
                            if (ready) renderPatch(c, face, patch, layer);
                            else render(c, face, parents[patch], layer, true);
                        }
                        public void lod(int face) { drawLods(c, face, layer); }
                        public void end(RenderType value) { value.clearRenderState(); }
                    });
            if (!translucent) {
                drawResidual(c);
            }
        } finally {
            try {
                GroundClient.sourceEnd();
                coverage.close();
                com.mojang.blaze3d.platform.GlStateManager._activeTexture(activeTexture);
                RenderSystem.setShaderTexture(0, texture0);
                RenderSystem.setShaderTexture(1, texture1);
                RenderSystem.setShaderTexture(2, texture2);
            } finally {
                pass.close();
            }
        }
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.FAR);
        }
    }

    private static void prepareSurfaceUpdates() {
        if (!GroundViewClient.coverageReady()) return;
        refreshCoverage();
        var mc = Minecraft.getInstance();
        int next = GroundFlightClient.controlled() ? -1 : GroundClient.face();
        // A face crossing changes projection only. No mesh/owner rebase or native promotion.
        nativeFace = next;
        for (long key : GroundViewClient.drainInvalidated()) {
            stitchDirty = true;
            // Cover geometry reads a tile's surface only while its coverage bit is set; an unedited
            // volume arriving must not restart the patch being built around it.
            dirtyHalo(key);
            // Edited structures must not wait behind thousands of optional procedural refinements.
            var lod=lodTiles.get(key);
            if(lod!=null) {
                lod.dirty=true;
                lodRequests.removeIf(t->t.key()==key); lodQueued.remove(key);
                requestLod(new GroundLodSelection.Tile(key,0,true),lod.requested);
            }
        }
    }

    private static void refreshCoverage() {
        if (!GroundViewClient.coverageReady()) return;
        var changed = GroundViewClient.changed();
        for (int key = priorCoverage.nextSetBit(0);
                key >= 0;
                key = priorCoverage.nextSetBit(key + 1)) if (!changed.get(key)) dirtyHalo(key);
        for (int key = changed.nextSetBit(0); key >= 0; key = changed.nextSetBit(key + 1))
            if (!priorCoverage.get(key)) dirtyHalo(key);
        dirty.coverage(priorCoverage, changed);
        priorCoverage = changed;
    }

    private static void dirtyHalo(long key) {
        selectionVersion++;
        int face = (int) (key / 16384),
                x = (int) (key % 128) * 16,
                z = (int) (key / 128 % 128) * 16;
        for (int dx = -64; dx <= 64; dx += 64)
            for (int dz = -64; dz <= 64; dz += 64) {
                var cell =
                        GroundClient.shape()
                                .topology()
                                .canonical(
                                        new CubeSurfaceTopology.Cell(
                                                CubeSurfaceTopology.Face.VALUES[face],
                                                x + dx,
                                                0,
                                                z + dz))
                                .cell();
                dirty.tile(tileKey(cell.face().ordinal(), cell.u(), cell.v()));
            }
    }

    private static boolean coversPending() {
        return GroundPipeline.publish(dirty.count(), !coverJobs.isEmpty(), patches, staging,
                p->GroundViewPolicy.exists(shape,p));
    }

    private static GroundCoverSnapshot[] coverSnapshots(boolean pending) {
        var result = new GroundCoverSnapshot[patches.length];
        for (int p = 0; p < result.length; p++) {
            var mesh = pending && staging[p] != null ? staging[p] : ready ? patches[p] : parents[p];
            result[p] = mesh == null ? null : mesh.cover;
        }
        return result;
    }

    private static void commitCovers() {
        boolean firstCover = !ready;
        for (int p = 0; p < patches.length; p++)
            if (staging[p] != null) {
                if (patches[p] != null) patches[p].close();
                patchQuads.put(p, stagedQuads.remove(p));
                patchPaired[p] = stagedPaired[p];
                patches[p] = staging[p];
                drawnLevels[p] = builtLevels[p];
                staging[p] = null;
                patchCommitted[p] = System.nanoTime();
            }
        coverEpoch++;
        selectionVersion++;
        GroundExterior.commit();
        coverCohort = null;
        dirty.promote();
        ready = GroundViewClient.coverageReady() && patchesComplete();
        // Initial parents use 64-block tops; rebuild their resident edge skirts once patches take
        // over.
        if (firstCover && ready) for (var tile : lodTiles.values()) tile.dirty = true;
    }

    /** Run after vanilla's layer draws, never infer this frame's coverage at AFTER_SKY. */
    private static void refreshNativeDraws() {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.NATIVE);
        try {
            window = GroundNativeCoverage.visible();
            publishOwnership(coverage.ownership);
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.NATIVE);
        }
    }

    private static void updateExclusions() {
        // No native draw has happened at AFTER_SKY: do not walk last frame's visible list.
        window = GroundNativeCoverage.visible();
        // A dirty replacement is staged; the currently drawn mesh and strips remain valid.
        publishOwnership(coverage.ownership.retainCompatible(residentOwnership));
        var available = availableOwnership;
        if (pendingStitches != null) {
            var retained = pendingJob.selection.retainCompatible(available);
            // Never trim an already displayed owner to COVER merely because its adapter is
            // changing.
            boolean preserves = GroundHandoff.preservesSelected(coverage.ownership, retained, available);
            if (preserves
                    && GroundOwnership.canCommit(
                            pendingJob.nextCover,
                            coversPending(),
                            pendingJob.coverVersion,
                            coverEpoch,
                            pendingJob.stagedVersion,
                            stagedEpoch,
                            retained,
                            available)) {
                if (pendingJob.nextCover) commitCovers();
                for (int i = 0; i < 6; i++) {
                    var old = stitches[i];
                    stitches[i] = pendingStitches[i];
                    if (old != null) old.close();
                }
                publishOwnership(retained);
                stitchDirty = false;
            } else for (var mesh : pendingStitches) mesh.close();
            pendingStitches = null;
            pendingJob = null;
        }
        if (coverage.texture() == 0) publishOwnership(coverage.ownership);
        if (stitchJob == null
                && pendingJob == null
                && (stitchDirty || coversPending() || !coverage.ownership.sameAs(available))) {
            stitchDirty = false;
            boolean next = coversPending();
            stitchJob =
                    new StitchJob(available, coverSnapshots(next), next, coverEpoch, stagedEpoch);
        }
    }

    // Each render stage sees its own native window (translucent sections draw after entities),
    // so one frame publishes up to three atlases; repack only when ownership or LOD coverage moves.

    private static void publishOwnership(GroundOwnership.Snapshot next) {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.OWNERSHIP);
        try {
            coverage.publish(GroundClient.shape(), next, window, lodComplete, lodAtlasDirty);
            lodAtlasDirty = false;
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.OWNERSHIP);
        }
    }

    /**
     * Diagnostic for the part of the corner gap the repair leaves open; the closure stands on it,
     * so only draw it without one.
     */
    private static void drawResidual(RenderFrame c) {
        if (GroundFlightClient.controlled() || mask != null && mask.core() > 0) return;
        var map = GroundClient.gap();
        var quads = map.residual();
        if (quads.isEmpty()) return;
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var matrix =
                GroundClient.place(
                        new Matrix4f(c.matrixStack().last().pose()),
                        GroundClient.face(),
                        c.camera().getPosition());
        var out =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (var quad : quads) {
            var points = new BentStripGeometry.Point[] {quad.a(), quad.b(), quad.c(), quad.d()};
            int[] order =
                    quad.b().sub(quad.a()).cross(quad.c().sub(quad.a())).y() < 0
                            ? new int[] {0, 2, 1, 0, 3, 2}
                            : new int[] {0, 1, 2, 0, 2, 3};
            for (int i : order)
                out.addVertex(matrix, (float) points[i].x(), -.002f, (float) points[i].z())
                        .setColor(220, 35, 35, 255);
        }
        var mesh = out.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    private static Mesh buildMask(int face, boolean wedge) {
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

    private static void renderPatch(RenderFrame c, int face, int p, RenderType layer) {
        float fade = patchPaired[p] ? VoxelLodPolicy.fade(patchCommitted[p], System.nanoTime()) : 1;
        render(c, face, patches[p], layer, true, fade);
    }

    private static void render(RenderFrame c, int face, Mesh mesh, RenderType layer) {
        render(c, face, mesh, layer, mesh == masks[face] || mesh == masks[face + 6]);
    }

    private static void render(
            RenderFrame c, int face, Mesh mesh, RenderType layer, boolean coarse) {
        render(c, face, mesh, layer, coarse, 1);
    }

    private static void render(
            RenderFrame c, int face, Mesh mesh, RenderType layer, boolean coarse, float fade) {
        if (mesh == null) return;
        var gpu = mesh.layers.get(layer);
        if (gpu == null) return;
        GroundClient.source(face, true, coarse);
        GroundClient.cover(
                coarse && mesh != masks[face] && mesh != masks[face + 6] && mesh != stitches[face]);
        GroundClient.stitch(mesh == stitches[face]);
        GroundClient.patchFade(fade);
        var matrix =
                GroundClient.place(
                        new Matrix4f(RenderSystem.getModelViewMatrix())
                                .mul(c.matrixStack().last().pose()),
                        face,
                        c.camera().getPosition());
        var shader = mesh.compact ? GroundCompact.shader() : RenderSystem.getShader();
        if (shader == null) return;
        gpu.bind();
        try {
            gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
            probeDraws++;
        } finally {
            VertexBuffer.unbind();
            GroundClient.sourceEnd();
        }
    }

    private static final class Builders extends GroundMeshVertices.Buffers {
        Builders() { super(); }
        Builders(boolean surface) { super(surface); }
        Builders(boolean surface, boolean compact) { super(surface, compact); }

        Mesh upload(long version) {
            if (!context.current()) return null;
            var out = new LinkedHashMap<RenderType, VertexBuffer>();
            var lodOut = new LinkedHashMap<RenderType, GroundLodArena.Range>();
            long size = 0;
            AABB bounds = null;
            try {
                for (var e : buffers.entrySet()) {
                    var data = e.getValue().build();
                    if (data == null) continue;
                    if (lodKey >= 0) {
                        var vertices = data.vertexBuffer();
                        for (int i = 0; i < data.drawState().vertexCount(); i++) {
                            int p = i * 32;
                            double x = Math.round(vertices.getFloat(p) * 16) / 16.0;
                            double y = Math.round((vertices.getFloat(p + 4) + 2048.0) * 16) / 16.0 - 2048;
                            double z = Math.round(vertices.getFloat(p + 8) * 16) / 16.0;
                            var point = new AABB(x, y, z, x, y, z);
                            bounds = bounds == null ? point : bounds.minmax(point);
                        }
                    }
                    try (var packed = new ByteBufferBuilder(4096)) {
                        if (compact) data = GroundCompact.pack(data, packed, e.getKey(), surfaceFace, lodKey);
                        size += data.vertexBuffer().remaining();
                        if (lodKey >= 0) {
                            var arena = lodArenas.get(surfaceFace).computeIfAbsent(e.getKey(), l -> new GroundLodArena());
                            long before = arena.bytes();
                            try (var upload = data) {
                                var range = arena.upload(upload, VoxelLodPolicy.RESIDENT_BYTES - lodBytes);
                                if (range == null) {
                                    for (var prior : lodOut.values()) prior.close();
                                    return null;
                                }
                                lodOut.put(e.getKey(), range);
                            } finally {
                                lodBytes += arena.bytes() - before;
                            }
                            continue;
                        }
                        var gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        gpu.bind();
                        try {
                            gpu.upload(data);
                        } catch (RuntimeException failure) {
                            gpu.close();
                            throw failure;
                        } finally {
                            VertexBuffer.unbind();
                        }
                        out.put(e.getKey(), gpu);
                    }
                }
                return new Mesh(version, out, lodOut, size, image, cover, compact, bounds);
            } catch (RuntimeException failure) {
                for (var gpu : out.values()) gpu.close();
                for (var range : lodOut.values()) range.close();
                throw failure;
            }
        }

    }

    private static long tileKey(int face, int x, int z) {
        return ChunkKeys.faceLocal(face, x / 16, z / 16);
    }

    // Source-chart edges: neighbour column, edge origin, edge delta, outward normal.
    private static final int[][] EDGES = {
        {-1, 0, 0, 0, 0, 1, -1, 0},
        {1, 0, 1, 1, 0, -1, 1, 0},
        {0, -1, 1, 0, -1, 0, 0, -1},
        {0, 1, 0, 1, 1, 0, 0, 1}
    };

    /**
     * Cached strips between actually drawn native/detail tiles and the simplified cover. An
     * exclusion bit removes a surface; it is not by itself a geometric stitch.
     */
    private static final class StitchJob implements AutoCloseable {
        final GroundOwnership.Snapshot selection;
        final GroundCoverSnapshot[] covers;
        final boolean nextCover;
        final long coverVersion, stagedVersion;
        final GroundSpace.Config config = mask;
        final long[] keys;
        final Builders[] builders = new Builders[6];
        int tile, column, edge;

        StitchJob(
                GroundOwnership.Snapshot selection,
                GroundCoverSnapshot[] covers,
                boolean nextCover,
                long coverVersion,
                long stagedVersion) {
            this.selection = selection;
            this.covers = covers.clone();
            this.nextCover = nextCover;
            this.coverVersion = coverVersion;
            this.stagedVersion = stagedVersion;
            keys = selection.keys().stream().sorted().mapToLong(Long::longValue).toArray();
            for (int i = 0; i < 6; i++) builders[i] = new Builders();
        }

        void step(long deadline) {
            while (tile < keys.length && System.nanoTime() < deadline) {
                long key = keys[tile];
                int face = (int) (key / 16384),
                        x = (int) (key % 128) * 16,
                        z = (int) (key / 128 % 128) * 16;
                var e = EDGES[edge];
                int u = edge == 0 ? 0 : edge == 1 ? 15 : column,
                        v = edge == 2 ? 0 : edge == 3 ? 15 : column;
                long neighbour = GroundOwnership.key(face, x + u + e[0], z + v + e[1]);
                {
                    // Prebuild all canonical exits, including the current native face: chart
                    // rebasing reuses them.
                    var fine = selection.surface(key);
                    var cover = covers[GroundViewPolicy.patch(neighbour)];
                    double h = fine.height(u, v) + 1 - 128;
                    double
                            a =
                                    cover.height(
                                            face,
                                            x + u + e[0],
                                            z + v + e[1],
                                            x + u + e[2],
                                            z + v + e[3]),
                            b =
                                    cover.height(
                                            face,
                                            x + u + e[0],
                                            z + v + e[1],
                                            x + u + e[2] + e[4],
                                            z + v + e[3] + e[5]);
                    var bridge =
                            GroundTransition.bridge(
                                    x + u + e[2] - e[6] * .02,
                                    z + v + e[3] - e[7] * .02,
                                    e[4],
                                    e[5],
                                    Math.max(h, Math.max(a, b)),
                                    Math.min(h, a) - 64,
                                    Math.min(h, b) - 64);
                    var owner = new BlockPos(face * 4096 + x + u, (int) h + 127, z + v);
                    var state = fine.state(u, v);
                    if (!state.isAir()
                            && !Anomaly.mask(
                                    GroundClient.shape(), config, GroundTopology.cell(owner)))
                        surfaceWall(
                                builders[face],
                                state,
                                bridge,
                                face,
                                x + u,
                                z + v,
                                e[6],
                                e[7],
                                true);
                }
                if (++column == 16) {
                    column = 0;
                    if (++edge == 4) {
                        edge = 0;
                        tile++;
                    }
                }
            }
        }

        boolean done() {
            return tile == keys.length;
        }

        Mesh[] finish() {
            var result = new Mesh[6];
            try {
                for (int i = 0; i < 6; i++) result[i] = builders[i].upload(0);
                return result;
            } catch (RuntimeException failure) {
                for (var mesh : result) if (mesh != null) mesh.close();
                throw failure;
            } finally {
                close();
            }
        }

        public void close() {
            for (var b : builders) b.close();
        }
    }

    private static void clearParents() {
        if (parentJob != null) {
            parentJob.close();
            parentJob = null;
        }
        for (int i = 0; i < parents.length; i++) {
            if (parents[i] != null) parents[i].close();
            parents[i] = null;
        }
    }

    /** Startup terraces use the existing distance levels, nearest first, one patch per commit. */
    private static final class ParentJob implements AutoCloseable {
        final GroundCoverSnapshot snapshot =
                GroundCoverSnapshot.parent(GroundViewClient.heights(), new BitSet());
        final List<Integer> order = new ArrayList<>();
        final Map<Integer, java.util.concurrent.Future<List<VoxelMesher.Quad>>> prefetched =
                new LinkedHashMap<>();
        final GroundFarMesh.Field field =
                farPyramids.initialField(GroundViewClient.terrain());
        final Map<Integer, Integer> steps = new HashMap<>();
        Builders builders;
        List<VoxelMesher.Quad> quads;
        int patch = -1, step, fed, finished;

        void step(long deadline) {
            // Re-sort unscheduled work when the camera moves; already submitted work stays bounded.
            var pose =
                    shape.canonical(
                                    CubeSurfaceTopology.Face.VALUES[GroundClient.face()],
                                    GroundClient.eye())
                            .pose();
            order.clear();
            order.addAll(GroundViewPolicy.nearestPatches(shape, shape.position(pose)));
            order.removeIf(p -> parents[p] != null || prefetched.containsKey(p));
            while (!done() && System.nanoTime() < deadline) {
                while (prefetched.size() < LOD_THREADS
                        && !order.isEmpty()
                        && System.nanoTime() < deadline) {
                    int p = order.removeFirst();
                    var observed = shape;
                    var heights = GroundViewClient.heights();
                    double h = heights == null ? 0 : heights.surfaceNode(
                            CubeSurfaceTopology.Face.VALUES[GroundViewPolicy.face(p)],
                            Math.min(shape.size(), GroundViewPolicy.x(p) + 128),
                            Math.min(shape.size(), GroundViewPolicy.z(p) + 128));
                    int selected = GroundFarMesh.level(
                            GroundFarMesh.distance(shape, shape.position(pose), p, h), 64,
                            lodFov, Minecraft.getInstance().getWindow().getHeight());
                    steps.put(p, selected);
                    var source=GroundViewClient.overlays().isEmpty() ? field : farPyramids.field(
                            observed,GroundViewClient.terrain(),GroundViewClient::overlay,p);
                    prefetched.put(
                            p,
                            lodWorkers.submitFirst(() -> GroundFarMesh.build(observed, p, selected, source)));
                }
                if (patch < 0) {
                    patch =
                            prefetched.entrySet().stream()
                                    .filter(e -> e.getValue().isDone())
                                    .mapToInt(Map.Entry::getKey)
                                    .findFirst()
                                    .orElse(-1);
                    if (patch < 0) return;
                    quads = completed(prefetched.get(patch));
                    step = steps.remove(patch);
                    builders = new Builders(true, true);
                    builders.surfaceFace = GroundViewPolicy.face(patch);
                    builders.cover = snapshot;
                }
                while (fed < quads.size() && System.nanoTime() < deadline) {
                    var q = quads.get(fed++);
                    surfacePolygon(
                            builders,
                            q.material(),
                            q.points(),
                            GroundViewPolicy.x(patch),
                            GroundViewPolicy.z(patch),
                            true,
                            GroundShaderSource.patchTag(false, GroundFarMesh.side(q)),q.rgb());
                }
                if (fed < quads.size()) return;
                builders.cover = snapshot.withTerraces(GroundViewPolicy.face(patch), quads, step);
                parents[patch] = builders.upload(0);
                patchQuads.put(patch, quads);
                builders.close();
                builders = null;
                prefetched.remove(patch);
                patch = -1;
                quads = null;
                fed = 0;
                finished++;
                uploads++;
            }
        }

        boolean done() {
            return parentsComplete();
        }

        public void close() {
            prefetched.values().forEach(f -> f.cancel(false));
            if (builders != null) builders.close();
        }
    }

    private static final class CoverJob implements AutoCloseable {
        final int patch, face, x0, z0, step;
        final long farVersion;
        final Builders builders = new Builders(true, true);
        final GroundExterior.Job exterior;
        final java.util.concurrent.Future<List<VoxelMesher.Quad>> future;
        final List<VoxelMesher.Quad> previous;
        List<VoxelMesher.Quad> quads;
        java.util.concurrent.Future<?> fill;
        private final GroundMeshLease lease;

        CoverJob(int patch, GroundCoverSnapshot inputs) {
            this.patch = patch;
            farVersion = farVersions[patch];
            face = GroundViewPolicy.face(patch);
            x0 = GroundViewPolicy.x(patch);
            z0 = GroundViewPolicy.z(patch);
            step = patchLevels[patch] == 0 ? 16 : patchLevels[patch];
            builders.surfaceFace = face;
            builders.cover = inputs;
            exterior = new GroundExterior.Job(patch, shape, mask);
            lease = new GroundMeshLease(() -> {
                builders.close();
                exterior.close();
            });
            previous = patchQuads.getOrDefault(patch, List.of());
            future =
                    farMesh(
                            shape,
                            patch,
                            step,
                            GroundViewClient.terrain(),
                            coverCohort,
                            farPyramids);
        }

        /** Vertices are written on a worker; the render thread only uploads in {@link #finish()}. */
        void step(long deadline) {
            if (fill != null || !future.isDone() || !lodWorkers.canSubmit(true)) return;
            quads = completed(future);
            fill =
                    lodWorkers.submitRefinement(
                            () -> {
                                if (!lease.begin()) return null;
                                try {
                                    GroundMeshResources.read(builders.context.resources(), () -> {
                                        for (var q : quads) emit(q, false);
                                        for (var q : previous) emit(q, true);
                                    });
                                } finally {
                                    lease.end();
                                }
                                return null;
                            });
        }

        private void emit(VoxelMesher.Quad q, boolean old) {
            surfacePolygon(
                    builders,
                    q.material(),
                    q.points(),
                    x0,
                    z0,
                    true,
                    GroundShaderSource.patchTag(old, GroundFarMesh.side(q)),q.rgb());
            var pos=builders.context.storage(GroundAlbedo.position(face,q.points()));
            var p=q.points(); var normal=p.get(1).sub(p.getFirst()).cross(p.get(2).sub(p.getFirst()));
            int rgb=GroundAlbedo.color(q.material(),pos,builders.context.tint(),Direction.getNearest((float)normal.x(),(float)normal.y(),(float)normal.z()));
            if(q.rgb()>=0) rgb=GroundAlbedo.calibrate(q.rgb(),
                    dev.starfold.worldgen.surface.SurfaceColor.color(q.material()),rgb);
            exterior.emit(q, rgb, old);
        }

        boolean done() {
            return fill != null && fill.isDone();
        }

        Mesh finish() {
            try {
                completed(fill);
                builders.cover = builders.cover.withTerraces(face, quads, step);
                var mesh = builders.upload(0);
                try {
                    GroundExterior.replace(patch, exterior.finish(), !previous.isEmpty());
                    return mesh;
                } catch (RuntimeException failure) {
                    mesh.close();
                    throw failure;
                }
            } finally {
                close();
            }
        }

        public void close() {
            future.cancel(false);
            if (fill != null) fill.cancel(false);
            lease.close();
        }
    }

    static int surfaceLight(int face, BlockPos pos) {
        var storage = GroundClient.storage(pos);
        if (level.getChunkSource().hasChunk(storage.getX() >> 4, storage.getZ() >> 4)
                && level.getLightEngine().lightOnInSection(net.minecraft.core.SectionPos.of(storage)))
            return level.getBrightness(LightLayer.BLOCK, storage) * 16
                    | level.getBrightness(LightLayer.SKY, storage) * 4096;
        var terrain = GroundViewClient.terrain();
        var liquid = terrain == null ? null : terrain.liquid(CubeSurfaceTopology.Face.VALUES[face], pos.getX() - face * 4096, pos.getZ());
        int depth = liquid == null
                || terrain.groundY(CubeSurfaceTopology.Face.VALUES[face], pos.getX() - face * 4096, pos.getZ()) >= liquid.level()
                ? 0 : liquid.level() + 1 - pos.getY();
        return GroundFarTexture.sky(depth) * 4096;
    }

    private GroundRenderer() {}
}
