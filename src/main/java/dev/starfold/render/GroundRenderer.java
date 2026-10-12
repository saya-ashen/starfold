package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetChart;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.GroundTopology;
import dev.starfold.probe.ArrivalClientState;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.space.RenderFrame;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;

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

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.util.*;

/** Derived native models plus conservative surface coverage. No material/name placement ledger. */
public final class GroundRenderer {
    private record Mesh(
            long revision,
            Map<RenderType, VertexBuffer> layers,
            long bytes,
            GroundViewCache.Surface surface,
            GroundCoverSnapshot cover) {
        void close() {
            for (var buffer : layers.values()) buffer.close();
        }
    }

    private static final Map<Long, Mesh> detail = new LinkedHashMap<>(),
            stagedDetails = new LinkedHashMap<>();
    private static final Set<Long> dirtyDetails = new HashSet<>();
    private static final Map<Long, Long> measuredDetails = new HashMap<>();

    private static void clearDetails() {
        for (var m : detail.values()) m.close();
        detail.clear();
        for (var m : stagedDetails.values()) m.close();
        stagedDetails.clear();
        dirtyDetails.clear();
        measuredDetails.clear();
    }

    /** Closure meshes: 0-5 each face's own squares, 6-11 the wedge an unfolded plane leaves open beside them. */
    private static final Mesh[] masks = new Mesh[12],
            stitches = new Mesh[6],
            parents = new Mesh[6],
            patches = new Mesh[GroundViewPolicy.PATCH_COUNT],
            staging = new Mesh[GroundViewPolicy.PATCH_COUNT];

    private static ParentJob parentJob;
    private static Mesh[] pendingStitches;
    private static StitchJob pendingJob;
    private static GroundOwnership.Snapshot ownership = GroundOwnership.empty();
    private static long coverEpoch, stagedEpoch;
    private static int nativeFace = -1;
    private static boolean selectionActive;
    private static long selectionWork;
    private static final GroundViewPolicy.DirtyPatches dirty =
            new GroundViewPolicy.DirtyPatches(GroundClient::shape);
    private static BitSet priorCoverage = new BitSet();
    private static Set<Long> wanted = Set.of(), window = Set.of(), publishedWindow = Set.of();
    private static DetailJob job;
    private static CoverJob coverJob;
    private static StitchJob stitchJob;
    private static boolean stitchDirty = true;
    private static long frameWork, lastCells;
    private static int uploads, coverageTexture;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static GroundSpace.Config mask;
    private static boolean ready;
    private static int planet = -1;

    public static boolean covered() {
        return level == Minecraft.getInstance().level && ready && GroundExterior.ready();
    }

    public static boolean selectionReady() {
        return selectionActive && coverageTexture != 0;
    }

    public static boolean replacesNative() {
        return GroundClient.replacesNative();
    }

    public static int coverageTexture() {
        return coverageTexture;
    }

    public static boolean nativeVolume(int face) {
        return false;
    } // No face-wide ownership override: ground uses canonical full volumes.

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        GroundExterior.initialize(modBus);
        // Selection and connectors become visible before vanilla terrain draws, not mid-frame.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_SKY) return;
                    long start = System.nanoTime();
                    if (GroundClient.ready() && RendererGuard.pipelineSupported()) ensureState();
                    selectionActive =
                            GroundClient.ready()
                                    && RendererGuard.pipelineSupported()
                                    && level == Minecraft.getInstance().level
                                    && (ready || GroundPipeline.complete(parents));
                    if (GroundClient.ready()
                            && RendererGuard.pipelineSupported()
                            && level == Minecraft.getInstance().level) prepareDetails();
                    if (selectionActive) updateExclusions();
                    selectionWork = System.nanoTime() - start;
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_ENTITIES) return;
                    var c = new RenderFrame(cpEvent);
                    if (!GroundClient.ready()
                            || !RendererGuard.pipelineSupported()
                            || c.matrixStack() == null) return;
                    draw(c, false);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_PARTICLES) return;
                    var c = new RenderFrame(cpEvent);
                    if (GroundClient.ready()
                            && RendererGuard.pipelineSupported()
                            && c.matrixStack() != null) draw(c, true);
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
                                        clear();
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

    public static void clear() {
        selectionActive = false;
        selectionWork = 0;
        if (pendingStitches != null) {
            for (var mesh : pendingStitches) if (mesh != null) mesh.close();
            pendingStitches = null;
        }
        pendingJob = null;
        ownership = GroundOwnership.empty();
        coverEpoch = stagedEpoch = 0;
        nativeFace = -1;
        clearParents();
        for (int i = 0; i < staging.length; i++) {
            if (staging[i] != null) staging[i].close();
            staging[i] = null;
        }
        GroundExterior.clear();
        if (stitchJob != null) {
            stitchJob.close();
            stitchJob = null;
        }
        for (int i = 0; i < 6; i++) {
            if (stitches[i] != null) stitches[i].close();
            stitches[i] = null;
        }
        stitchDirty = true;
        if (coverJob != null) {
            coverJob.close();
            coverJob = null;
        }
        if (job != null) {
            job.close();
            job = null;
        }
        clearDetails();
        for (int i = 0; i < masks.length; i++) {
            if (masks[i] != null) masks[i].close();
            masks[i] = null;
        }
        for (int i = 0; i < patches.length; i++) {
            if (patches[i] != null) patches[i].close();
            patches[i] = null;
        }
        if (coverageTexture != 0) GL11.glDeleteTextures(coverageTexture);
        coverageTexture = 0;
        dirty.all();
        priorCoverage.clear();
        wanted = Set.of();
        window = Set.of();
        publishedWindow = Set.of();
        level = null;
        planet = -1;
        mask = null;
        ready = false;
    }

    public static int probeParentTiles() {
        return parentJob == null
                ? -1
                : parentJob.face * parentJob.across * parentJob.across + parentJob.tile;
    }

    public static int probeParents() {
        int n = 0;
        for (var parent : parents) if (parent != null) n++;
        return n;
    }

    public static String performance() {
        return "Parent coverage "
                + Arrays.stream(parents).filter(Objects::nonNull).count()
                + "/6 | derived build "
                + String.format(java.util.Locale.ROOT, "%.2f", frameWork / 1e6)
                + "ms | uploads "
                + uploads
                + " | dirty tiles "
                + dirty.count()
                + " | detail "
                + detail.size()
                + "/"
                + wanted.size()
                + " | resident "
                + GroundViewClient.residentTiles().size()
                + " | scanned "
                + lastCells;
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
                    if (!window.contains(key) && !drawDetail(key)) continue;
                    var state =
                            GroundViewClient.cache()
                                    .canonical(new BlockPos(face * 4096 + x, 128, z));
                    var point =
                            GroundClient.planePoint(
                                    planet,
                                    CubeSurfaceTopology.Face.values()[face],
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
            clearParents();
            stitchDirty = true;
            dirty.all();
            if (stitchJob != null) {
                stitchJob.close();
                stitchJob = null;
            }
            if (pendingStitches != null) {
                for (var m : pendingStitches) m.close();
                pendingStitches = null;
            }
            pendingJob = null;
            if (coverJob != null) {
                coverJob.close();
                coverJob = null;
            }
            for (int i = 0; i < masks.length; i++) {
                if (masks[i] != null) masks[i].close();
                masks[i] = null;
            }
            if (job != null) {
                job.close();
                job = null;
            }
            clearDetails();
        }
    }

    private static void draw(RenderFrame c, boolean translucent) {
        var mc = Minecraft.getInstance();
        ensureState();
        refreshCoverage();
        closureCoverage();
        if (!translucent) updateMeshes();
        drawLayers(c, translucent);
    }

    private static void updateMeshes() {
        long start = System.nanoTime();
        // Near volumes are the only terrain shown on the ground: while any is meshing or
        // waiting to
        // commit, spend the larger near budget. Idle frames and background cover keep the base
        // budget.
        boolean parentsReady = GroundPipeline.complete(parents);
        long deadline =
                start
                        + (!parentsReady
                                        || job != null
                                        || stitchJob != null
                                        || !stagedDetails.isEmpty()
                                ? GroundViewPolicy.NEAR_BUDGET_NS
                                : GroundViewPolicy.FRAME_BUDGET_NS);
        uploads = 0;
        lastCells = 0;
        for (int face = 0; face < 6 && System.nanoTime() < deadline; face++)
            if (masks[face] == null) {
                masks[face] = buildMask(face, false);
                masks[face + 6] = buildMask(face, true);
                uploads++;
            }
        // Build the first complete cover promptly; refinements then return to their background
        // share.
        long coverDeadline = start + GroundViewPolicy.coverBudget(parentsReady);
        updateCover(coverDeadline);
        long detailDeadline = stitchJob == null ? deadline : deadline - (deadline - start) / 4;
        updateDetails(detailDeadline);
        ready = GroundViewClient.coverageReady() && patchesComplete();
        updateStitches(deadline);
        frameWork = System.nanoTime() - start + selectionWork;
    }

    private static void updateCover(long coverDeadline) {
        if (GroundViewClient.coverageReady()
                && !GroundPipeline.complete(parents)
                && System.nanoTime() < coverDeadline) {
            long parentStart = ArrivalProbe.start();
            if (parentJob == null) parentJob = new ParentJob();
            parentJob.step(coverDeadline);
            if (ArrivalProbe.ENABLED)
                ArrivalClientState.parentNanos += System.nanoTime() - parentStart;
            if (parentJob.done()) {
                var meshes = parentJob.finish();
                parentJob = null;
                System.arraycopy(meshes, 0, parents, 0, 6);
                stitchDirty = true;
            }
        }
        if (GroundViewClient.coverageReady()
                && GroundPipeline.complete(parents)
                && System.nanoTime() < coverDeadline) {
            if (coverJob == null) {
                int patch = dirty.poll();
                if (patch >= 0) coverJob = new CoverJob(patch);
            }
            if (coverJob != null) {
                coverJob.step(coverDeadline);
                if (coverJob.done()) {
                    int patch = coverJob.patch;
                    var mesh = coverJob.finish();
                    coverJob = null;
                    var old = staging[patch];
                    staging[patch] = mesh;
                    stagedEpoch++;
                    if (old != null) old.close();
                    uploads++;
                }
            }
        }
    }

    private static void updateDetails(long detailDeadline) {
        if (job == null
                && stagedDetails.values().stream().mapToLong(Mesh::bytes).sum()
                        <= GroundViewPolicy.STAGING_BYTES - GroundStreaming.MAX_MESH_BYTES
                && System.nanoTime() < detailDeadline) {
            var cache = GroundViewClient.cache();
            // Nearest first, but only once the resident neighbours it is culled/lit against
            // have arrived.
            long key =
                    GroundStreaming.next(
                            GroundClient.shape(),
                            wanted,
                            k ->
                                    cache.detailed(k)
                                            && !stagedDetails.containsKey(k)
                                            && (!detail.containsKey(k) || dirtyDetails.contains(k)),
                            cache::detailed,
                            GroundViewClient.residentTiles(),
                            GroundViewClient.stalled());
            if (key >= 0) job = new DetailJob(cache.image(key));
        }
        if (job != null && System.nanoTime() < detailDeadline) {
            lastCells = job.step(detailDeadline);
            if (job.done()) {
                var image = job.image;
                var mesh = job.finish();
                job = null;
                measuredDetails.put(
                        image.key(),
                        Math.max(
                                mesh.bytes,
                                detail.containsKey(image.key())
                                        ? detail.get(image.key()).bytes
                                        : 0));
                if (GroundStreaming.admit(
                                GroundViewClient.residentTiles(),
                                measuredDetails,
                                GroundViewPolicy.DETAIL_BYTES,
                                wanted)
                        .contains(image.key())) {
                    stagedDetails.put(image.key(), mesh);
                    dirtyDetails.remove(image.key());
                    stitchDirty = true;
                } else mesh.close();
                uploads++;
            }
        }
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
        if (replacesNative()) {
            if (!translucent) GroundExterior.draw(c);
            return;
        }
        if (ArrivalProbe.ENABLED
                && !translucent
                && selectionReady()
                && (ready || GroundPipeline.complete(parents))) ArrivalClientState.own = true;
        var shader = RenderSystem.getShader();
        var color = RenderSystem.getShaderColor().clone();
        int[] textures = {
            RenderSystem.getShaderTexture(0),
            RenderSystem.getShaderTexture(1),
            RenderSystem.getShaderTexture(2)
        };
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        int boundCoverage = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean samplers =
                GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        int coverageSampler =
                samplers ? GL30.glGetIntegeri(ARBSamplerObjects.GL_SAMPLER_BINDING, 3) : 0;
        GL13.glActiveTexture(activeTexture);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int sr = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dr = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try (var filtering = GroundTextureFilter.open()) {
            for (var layer : RenderType.chunkBufferLayers()) {
                if ((layer == RenderType.translucent() || layer == RenderType.tripwire())
                        != translucent) continue;
                layer.setupRenderState();
                try {
                    for (int face = 0; face < 6; face++) {
                        boolean orbitalChart = GroundClient.eye().y() > GroundOrbit.START;
                        if (!orbitalChart
                                && (new PlanetChart(
                                                        GroundClient.shape(),
                                                        CubeSurfaceTopology.Face.values()[
                                                                GroundClient.face()])
                                                .tile(CubeSurfaceTopology.Face.values()[face])
                                        == null)) continue;
                        render(c, face, masks[face], layer);
                        // On the plane the three faces' columns leave a quarter open; the sphere
                        // has none.
                        if (!orbitalChart && face == GroundClient.face())
                            render(c, face, masks[face + 6], layer);
                        render(c, face, stitches[face], layer, true);
                        // Never expose a partial globe or mixed fine-patch generation.
                        if (selectionReady() && ready) {
                            for (int p = face * 64; p < (face + 1) * 64; p++)
                                render(c, face, patches[p], layer, true);
                        } else if (selectionReady() && GroundPipeline.complete(parents))
                            render(c, face, parents[face], layer, true);
                        for (var e : detail.entrySet())
                            if (e.getKey() / 16384 == face && drawDetail(e.getKey()))
                                render(c, face, e.getValue(), layer);
                    }
                } finally {
                    layer.clearRenderState();
                }
            }
            if (!translucent) {
                drawEntities(c);
                drawResidual(c);
            }
        } finally {
            GroundClient.sourceEnd();
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundCoverage);
            if (samplers) ARBSamplerObjects.glBindSampler(3, coverageSampler);
            GL13.glActiveTexture(activeTexture);
            RenderSystem.blendFuncSeparate(sr, dr, sa, da);
            RenderSystem.depthMask(write);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            for (int i = 0; i < 3; i++) RenderSystem.setShaderTexture(i, textures[i]);
            if (shader != null) RenderSystem.setShader(() -> shader);
        }
    }

    private static void prepareDetails() {
        if (!GroundViewClient.coverageReady()) return;
        refreshCoverage();
        var mc = Minecraft.getInstance();
        int next = GroundFlightClient.controlled() ? -1 : GroundClient.face();
        // A face crossing changes projection only. No mesh/owner rebase or native promotion.
        nativeFace = next;
        var resident = GroundViewClient.residentTiles();
        measuredDetails.keySet().retainAll(resident);
        wanted =
                GroundStreaming.admit(
                        resident, measuredDetails, GroundViewPolicy.DETAIL_BYTES, wanted);
        for (long key : GroundViewClient.drainInvalidated()) {
            stitchDirty = true;
            // Cover geometry reads a tile's surface only while its coverage bit is set; an unedited
            // volume arriving must not restart the patch being built around it.
            boolean cover = GroundViewClient.isChanged(key) || priorCoverage.get((int) key);
            if (cover) dirtyHalo(key);
            dirtyDetails.add(key);
            if (measuredDetails.getOrDefault(key, 0L) > GroundStreaming.MAX_MESH_BYTES)
                measuredDetails.remove(key);
            var staged = stagedDetails.remove(key);
            if (staged != null) staged.close();
            if (job != null && job.image.key() == key) {
                job.close();
                job = null;
            }
            if (cover && coverJob != null && coverJob.patch == GroundViewPolicy.patch(key)) {
                coverJob.close();
                coverJob = null;
            }
        }
        var iterator = detail.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            if (!wanted.contains(e.getKey())) {
                e.getValue().close();
                iterator.remove();
            }
        }
        var pending = stagedDetails.entrySet().iterator();
        while (pending.hasNext()) {
            var e = pending.next();
            if (!wanted.contains(e.getKey())) {
                e.getValue().close();
                pending.remove();
            }
        }
        dirtyDetails.retainAll(resident);
        if (job != null && !wanted.contains(job.image.key())) {
            job.close();
            job = null;
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
        if (coverJob != null
                && GroundViewPolicy.coverageChanged(priorCoverage, changed, coverJob.patch)) {
            coverJob.close();
            coverJob = null;
        }
        dirty.coverage(priorCoverage, changed);
        priorCoverage = changed;
    }

    private static void dirtyHalo(long key) {
        int face = (int) (key / 16384),
                x = (int) (key % 128) * 16,
                z = (int) (key / 128 % 128) * 16;
        for (int dx = -16; dx <= 16; dx += 16)
            for (int dz = -16; dz <= 16; dz += 16) {
                var cell =
                        GroundClient.shape()
                                .topology()
                                .canonical(
                                        new CubeSurfaceTopology.Cell(
                                                CubeSurfaceTopology.Face.values()[face],
                                                x + dx,
                                                0,
                                                z + dz))
                                .cell();
                dirty.tile(tileKey(cell.face().ordinal(), cell.u(), cell.v()));
            }
        if (coverJob != null) {
            int p = coverJob.patch;
            if (dirty.contains(p)) {
                coverJob.close();
                coverJob = null;
            }
        }
    }

    private static boolean drawDetail(long key) {
        return selectionReady() && ownership.owner(key) == GroundOwnership.Owner.DETAIL;
    }

    private static boolean coversPending() {
        return GroundPipeline.publish(dirty.count(), coverJob != null, patches, staging);
    }

    private static GroundCoverSnapshot[] coverSnapshots(boolean pending) {
        var result = new GroundCoverSnapshot[patches.length];
        for (int p = 0; p < result.length; p++) {
            var mesh =
                    pending && staging[p] != null
                            ? staging[p]
                            : ready ? patches[p] : parents[p / 64];
            result[p] = mesh.cover;
        }
        return result;
    }

    private static void commitCovers() {
        for (int p = 0; p < patches.length; p++)
            if (staging[p] != null) {
                var old = patches[p];
                patches[p] = staging[p];
                staging[p] = null;
                if (old != null) old.close();
            }
        coverEpoch++;
        GroundExterior.commit();
        ready = GroundViewClient.coverageReady() && patchesComplete();
    }

    private static void updateExclusions() {
        var mc = Minecraft.getInstance();
        var nativeReady = new BitSet();
        var columns = new HashMap<Long, GroundViewCache.Surface>();
        // Vanilla draws every chart chunk in its view window (halo images included): hide cover
        // there.
        window = replacesNative() ? Set.of() : GroundViewClient.window();
        for (var e : detail.entrySet())
            if (!columns.containsKey(e.getKey())) columns.put(e.getKey(), e.getValue().surface);
        var residentNative = (BitSet) nativeReady.clone();
        var resident = new GroundOwnership.Snapshot(columns, residentNative);
        // A dirty replacement is staged; the currently drawn mesh and strips remain valid.
        publishOwnership(ownership.retainCompatible(resident));
        for (var e : stagedDetails.entrySet())
            if (!nativeReady.get(e.getKey().intValue()))
                columns.put(e.getKey(), e.getValue().surface);
        var available = new GroundOwnership.Snapshot(columns, nativeReady);
        if (pendingStitches != null) {
            var retained = pendingJob.selection.retainCompatible(available);
            // Never trim an already displayed owner to COVER merely because its adapter is
            // changing.
            boolean preserves = GroundHandoff.preservesSelected(ownership, retained, available);
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
                commitDetails(retained);
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
        if (coverageTexture == 0) publishOwnership(ownership);
        if (stitchJob == null
                && pendingJob == null
                && (stitchDirty || coversPending() || !ownership.sameAs(available))) {
            stitchDirty = false;
            boolean next = coversPending();
            stitchJob =
                    new StitchJob(available, coverSnapshots(next), next, coverEpoch, stagedEpoch);
        }
    }

    private static void commitDetails(GroundOwnership.Snapshot selected) {
        var iterator = stagedDetails.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            if (GroundOwnership.same(e.getValue().surface, selected.surface(e.getKey()))) {
                var old = detail.put(e.getKey(), e.getValue());
                if (old != null) old.close();
                measuredDetails.put(e.getKey(), e.getValue().bytes);
                iterator.remove();
            }
        }
    }

    private static void publishOwnership(GroundOwnership.Snapshot next) {
        if (coverageTexture != 0 && ownership.sameAs(next) && publishedWindow.equals(window))
            return;
        ownership = next;
        publishedWindow = window;
        var data = GroundOwnership.atlas(GroundClient.shape(), next, window);
        var bytes = org.lwjgl.system.MemoryUtil.memAlloc(data.length);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
                rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),
                skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),
                skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            bytes.put(data).flip();
            if (coverageTexture == 0) {
                coverageTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, coverageTexture);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        GL30.GL_R8,
                        GroundOwnership.SIDE,
                        GroundOwnership.HEIGHT,
                        0,
                        GL11.GL_RED,
                        GL11.GL_UNSIGNED_BYTE,
                        bytes);
            } else {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, coverageTexture);
                GL11.glTexSubImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        GroundOwnership.SIDE,
                        GroundOwnership.HEIGHT,
                        GL11.GL_RED,
                        GL11.GL_UNSIGNED_BYTE,
                        bytes);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            org.lwjgl.system.MemoryUtil.memFree(bytes);
        }
    }

    /** Diagnostic for the part of the corner gap the repair leaves open; the closure stands on it, so only draw it without one. */
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void drawEntities(RenderFrame c) {
        var mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        var buffer = mc.renderBuffers().bufferSource();
        for (var be : GroundViewClient.entities()) {
            int face = GroundClient.access().face(be.getBlockPos().getX());
            var renderer = dispatcher.getRenderer(be);
            if (renderer == null) continue;
            if (Anomaly.mask(
                    GroundClient.shape(), mask, GroundClient.access().cell(be.getBlockPos())))
                continue;
            if (level.getChunkSource()
                            .hasChunk(be.getBlockPos().getX() >> 4, be.getBlockPos().getZ() >> 4)
                    && level.getChunkAt(be.getBlockPos())
                            .getBlockEntities()
                            .containsKey(be.getBlockPos())
                    && renderer.shouldRender(be, c.camera().getPosition())) continue;
            var point =
                    new BentStripGeometry.Point(
                            be.getBlockPos().getX() - GroundClient.storageOffset() - face * 4096,
                            be.getBlockPos().getY() - 128,
                            be.getBlockPos().getZ() - GroundClient.storageOffsetZ());
            if (!GroundFlightClient.controlled()) {
                var map = GroundClient.gap();
                if (map.chart.tile(CubeSurfaceTopology.Face.values()[face]) == null
                        || map.point(CubeSurfaceTopology.Face.values()[face], point)
                                        .sub(GroundClient.eye())
                                        .length()
                                > GroundClient.view() + 24) continue;
            }
            var poses = c.matrixStack();
            poses.pushPose();
            var camera = c.camera().getPosition();
            poses.translate(
                    be.getBlockPos().getX() - camera.x,
                    be.getBlockPos().getY() - camera.y,
                    be.getBlockPos().getZ() - camera.z);
            GroundClient.source(face, true);
            try {
                renderer.render(
                        be,
                        c.tickCounter().getGameTimeDeltaPartialTick(false),
                        poses,
                        buffer,
                        LevelRenderer.getLightColor(level, be.getBlockPos()),
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                buffer.endBatch();
            } finally {
                GroundClient.sourceEnd();
                poses.popPose();
            }
        }
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

    /** The four walls between a square (a,b,size) at height h and another at height high, each split along its length. */
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

    private static void render(RenderFrame c, int face, Mesh mesh, RenderType layer) {
        render(c, face, mesh, layer, mesh == masks[face] || mesh == masks[face + 6]);
    }

    private static void render(
            RenderFrame c, int face, Mesh mesh, RenderType layer, boolean coarse) {
        if (mesh == null) return;
        var gpu = mesh.layers.get(layer);
        if (gpu == null) return;
        GroundClient.source(face, true, coarse);
        GroundClient.cover(
                coarse && mesh != masks[face] && mesh != masks[face + 6] && mesh != stitches[face]);
        GroundClient.stitch(mesh == stitches[face]);
        var matrix =
                GroundClient.place(
                        new Matrix4f(RenderSystem.getModelViewMatrix())
                                .mul(c.matrixStack().last().pose()),
                        face,
                        c.camera().getPosition());
        var shader = RenderSystem.getShader();
        if (shader == null) return;
        gpu.bind();
        try {
            gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
        } finally {
            VertexBuffer.unbind();
            GroundClient.sourceEnd();
        }
    }

    private static final class Builders implements AutoCloseable {
        final Map<RenderType, ByteBufferBuilder> memory = new LinkedHashMap<>();
        final Map<RenderType, BufferBuilder> buffers = new LinkedHashMap<>();
        final boolean surface;
        int surfaceFace;
        GroundViewCache.Surface image;
        GroundCoverSnapshot cover;

        Builders() {
            this(false);
        }

        Builders(boolean surface) {
            this.surface = surface;
        }

        BufferBuilder get(RenderType layer) {
            return buffers.computeIfAbsent(
                    layer,
                    l -> {
                        var bytes = new ByteBufferBuilder(l.bufferSize());
                        memory.put(l, bytes);
                        return new BufferBuilder(bytes, l.mode(), l.format());
                    });
        }

        Mesh upload(long version) {
            var out = new LinkedHashMap<RenderType, VertexBuffer>();
            long size = 0;
            try {
                for (var e : buffers.entrySet()) {
                    var data = e.getValue().build();
                    if (data == null) continue;
                    size += data.vertexBuffer().remaining();
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
                return new Mesh(version, out, size, image, cover);
            } catch (RuntimeException failure) {
                for (var gpu : out.values()) gpu.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            for (var bytes : memory.values()) bytes.close();
        }
    }

    private static BlockAndTintGetter view(GroundChunkSnapshot own) {
        return new BlockAndTintGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                var address = GroundTopology.address(GroundClient.shape(), pos);
                var cell = GroundTopology.cell(address.owner());
                if (Anomaly.mask(GroundClient.shape(), mask, cell))
                    return Blocks.BEDROCK.defaultBlockState();
                if (own.owns(address.owner()))
                    return address.read(GroundViewClient.updatedState(own, address.owner()));
                var state = GroundViewClient.state(address.owner());
                if (state != null) return address.read(state);
                long key = cell.u() / 16 + 128L * (cell.v() / 16 + 128L * cell.face().ordinal());
                if (GroundViewClient.coverageReady()
                        && GroundViewClient.flat()
                        && !GroundViewClient.isChanged(key)) {
                    int y = pos.getY();
                    return y < -64 || y >= 128
                            ? Blocks.AIR.defaultBlockState()
                            : y == -64
                                    ? Blocks.BEDROCK.defaultBlockState()
                                    : y < 124
                                            ? Blocks.STONE.defaultBlockState()
                                            : y < 127
                                                    ? Blocks.DIRT.defaultBlockState()
                                                    : Blocks.GRASS_BLOCK.defaultBlockState();
                }
                return Blocks.BEDROCK.defaultBlockState();
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return level.getBlockEntity(
                        GroundClient.storage(
                                GroundTopology.address(GroundClient.shape(), pos).owner()));
            }

            @Override
            public int getHeight() {
                return level.getHeight();
            }

            @Override
            public int getMinBuildHeight() {
                return level.getMinBuildHeight();
            }

            @Override
            public float getShade(Direction direction, boolean shade) {
                return level.getShade(direction, shade);
            }

            @Override
            public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
                return level.getLightEngine();
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver resolver) {
                var owner = GroundTopology.address(GroundClient.shape(), pos).owner();
                var terrain = GroundViewClient.terrain();
                if (terrain != null && terrain.ecology() != null) {
                    var cell = GroundTopology.cell(owner);
                    var key =
                            terrain.sea() != null
                                            && terrain.groundY(cell.face(), cell.u(), cell.v())
                                                    < terrain.sea().level()
                                    ? net.minecraft.world.level.biome.Biomes.OCEAN
                                    : switch (terrain.ecology()
                                            .region(cell.face(), cell.u(), cell.v())) {
                                        case FOREST ->
                                                net.minecraft.world.level.biome.Biomes.FOREST;
                                        case PLAINS ->
                                                net.minecraft.world.level.biome.Biomes.PLAINS;
                                        case DESERT ->
                                                net.minecraft.world.level.biome.Biomes.DESERT;
                                    };
                    var biome =
                            level.registryAccess()
                                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                                    .getHolderOrThrow(key);
                    return resolver.getColor(biome.value(), owner.getX(), owner.getZ());
                }
                return level.getBlockTint(GroundClient.storage(owner), resolver);
            }

            @Override
            public int getBrightness(LightLayer layer, BlockPos pos) {
                var owner = GroundTopology.address(GroundClient.shape(), pos).owner();
                if (own.owns(owner)) return own.light(layer, owner);
                var image =
                        GroundViewClient.cache()
                                .image(
                                        GroundSurfaceIndex.key(
                                                owner.getX() >> 4, owner.getZ() >> 4));
                return image != null
                        ? image.light(layer, owner)
                        : level.hasChunk(
                                        (owner.getX() + GroundClient.storageOffset()) >> 4,
                                        owner.getZ() >> 4)
                                ? level.getBrightness(layer, GroundClient.storage(owner))
                                : layer == LightLayer.SKY ? 15 : 0;
            }
        };
    }

    private static final class DetailJob implements AutoCloseable {
        final GroundChunkSnapshot image;
        final Builders builders = new Builders();
        final BlockAndTintGetter getter;
        final PoseStack poses = new PoseStack();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final RandomSource random = RandomSource.create(0);
        final boolean clean;
        final int limit;
        int cursor;

        DetailJob(GroundChunkSnapshot image) {
            this.image = image;
            clean = !GroundViewClient.hasDeltas(image.key());
            limit =
                    clean
                            ? Math.min(
                                    GroundChunkSnapshot.VALUES,
                                    (image.top() + 1 - GroundChunkSnapshot.MIN_Y) * 256)
                            : GroundChunkSnapshot.VALUES;
            builders.image = GroundViewCache.surfaceImage(image);
            getter = view(image);
        }

        long step(long deadline) {
            long cells = 0;
            var mc = Minecraft.getInstance();
            while (cursor < limit) {
                if ((cursor & 7) == 0 && System.nanoTime() >= deadline) break;
                int i = cursor++, x = i & 15, z = i >> 4 & 15, y = (i >> 8) - 64;
                cells++;
                if (clean && image.enclosed(x, y, z)) continue;
                pos.set(image.face() * 4096 + image.u() * 16 + x, y, image.v() * 16 + z);
                var state = GroundViewClient.updatedState(image, pos);
                if (state.isAir()
                        || !state.is(Anomaly.CORE_BLOCK)
                                && Anomaly.mask(
                                        GroundClient.shape(), mask, GroundTopology.cell(pos)))
                    continue;
                if (state.getRenderShape() == RenderShape.MODEL) {
                    var layer = ItemBlockRenderTypes.getChunkRenderType(state);
                    poses.pushPose();
                    poses.translate(image.u() * 16 + x, y - 128, image.v() * 16 + z);
                    try {
                        mc.getBlockRenderer()
                                .renderBatched(
                                        state,
                                        pos,
                                        getter,
                                        poses,
                                        builders.get(layer),
                                        true,
                                        random);
                    } finally {
                        poses.popPose();
                    }
                }
                var fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    var layer = ItemBlockRenderTypes.getRenderLayer(fluid);
                    mc.getBlockRenderer()
                            .renderLiquid(
                                    pos,
                                    getter,
                                    new OffsetConsumer(
                                            builders.get(layer),
                                            image.u() * 16,
                                            (y & ~15) - 128,
                                            image.v() * 16),
                                    state,
                                    fluid);
                }
            }
            return cells;
        }

        boolean done() {
            return cursor >= limit;
        }

        Mesh finish() {
            try {
                return builders.upload(image.revision());
            } finally {
                close();
            }
        }

        public void close() {
            builders.close();
        }
    }

    private static long tileKey(int face, int x, int z) {
        return x / 16 + 128L * (z / 16 + 128L * face);
    }

    private static void baselineTop(
            Builders builders, int face, int x, int z, int size, boolean unknown) {
        if (GroundViewClient.terrain() != null)
            planetTop(builders, GroundViewClient.terrain(), face, x, z, size);
        else
            top(
                    builders,
                    Blocks.GRASS_BLOCK.defaultBlockState(),
                    x,
                    0,
                    z,
                    size,
                    unknown || !GroundViewClient.flat());
    }

    // Source-chart edges: neighbour column, edge origin, edge delta, outward normal.
    private static final int[][] EDGES = {
        {-1, 0, 0, 0, 0, 1, -1, 0},
        {1, 0, 1, 1, 0, -1, 1, 0},
        {0, -1, 1, 0, -1, 0, 0, -1},
        {0, 1, 0, 1, 1, 0, 0, 1}
    };

    private static void columnWalls(
            Builders builders, int face, int x, int z, double h, BlockState state) {
        for (var e : EDGES) {
            double a = builders.cover.height(face, x + e[0], z + e[1], x + e[2], z + e[3]),
                    b =
                            builders.cover.height(
                                    face, x + e[0], z + e[1], x + e[2] + e[4], z + e[3] + e[5]);
            boolean coarse = !builders.cover.column(GroundOwnership.key(face, x + e[0], z + e[1]));
            if (!coarse && h <= a) continue;
            // Inset only the wall by 1/1000 block so its mask belongs to its own source column.
            var bridge =
                    GroundTransition.bridge(
                            x + e[2] - e[6] * .001, z + e[3] - e[7] * .001, e[4], e[5], h, a, b);
            surfaceWall(builders, state, bridge, face, x, z, e[6], e[7], coarse);
        }
    }

    private static void surfaceWall(
            Builders builders,
            BlockState state,
            List<BentStripGeometry.Point> points,
            int face,
            int x,
            int z,
            int nx,
            int nz,
            boolean twoSided) {
        if (points.isEmpty()) return;
        var level = surfaceTint();
        var mc = Minecraft.getInstance();
        var model = mc.getBlockRenderer().getBlockModel(state);
        var side = Direction.fromDelta(nx, 0, nz);
        var quads = model.getQuads(state, side, RandomSource.create(0));
        var pos =
                GroundClient.storage(
                        new BlockPos(
                                face * 4096 + x, 128 + (int) Math.floor(points.getFirst().y()), z));
        var fluid = state.getFluidState();
        var handler =
                fluid.isEmpty()
                        ? null
                        : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                                .of(fluid.getType());
        if (handler
                == net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                        .DEFAULT) handler = null;
        var sprite =
                handler != null
                        ? mc.getTextureAtlas(
                                        net.minecraft.client.renderer.texture.TextureAtlas
                                                .LOCATION_BLOCKS)
                                .apply(handler.getStillTexture(fluid, level, pos))
                        : quads.isEmpty() ? model.getParticleIcon() : quads.getFirst().getSprite();
        int color =
                handler != null
                        ? handler.getTintColor(fluid, level, pos)
                        : !quads.isEmpty() && quads.getFirst().isTinted()
                                ? mc.getBlockColors()
                                        .getColor(
                                                state, level, pos, quads.getFirst().getTintIndex())
                                : 0xffffff;
        int albedo = builders.surface ? GroundAlbedo.color(state, pos, level) : 0;
        var out =
                builders.get(
                        builders.surface
                                ? RenderType.solid()
                                : handler == null
                                        ? ItemBlockRenderTypes.getChunkRenderType(state)
                                        : ItemBlockRenderTypes.getRenderLayer(fluid));
        var normal =
                points.get(1).sub(points.getFirst()).cross(points.get(2).sub(points.getFirst()));
        boolean reverse = normal.x() * nx + normal.z() * nz < 0;
        for (int pass = 0; pass < (twoSided ? 2 : 1); pass++) {
            boolean flip = reverse ^ (pass == 1);
            for (int j = 0; j < 4; j++) {
                int i = flip ? 3 - j : j;
                var p = points.get(i);
                out.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                        .setColor(
                                color >> 16 & 255,
                                color >> 8 & 255,
                                color & 255,
                                builders.surface
                                        ? (nx < 0 ? 0 : nx > 0 ? 252 : nz < 0 ? 253 : 254)
                                        : 255)
                        .setUv(
                                i < 2 ? sprite.getU0() : sprite.getU1(),
                                i == 0 || i == 3 ? sprite.getV0() : sprite.getV1())
                        .setUv2(240, 240)
                        .setNormal(
                                builders.surface ? GroundPipeline.channel(albedo, 16) : nx,
                                builders.surface ? GroundPipeline.channel(albedo, 8) : 0,
                                builders.surface ? GroundPipeline.channel(albedo, 0) : nz);
            }
        }
    }

    /** Cached strips between actually drawn native/detail tiles and the simplified cover.
     * An exclusion bit removes a surface; it is not by itself a geometric stitch. */
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
                                    h,
                                    a,
                                    b);
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
        for (int i = 0; i < 6; i++) {
            if (parents[i] != null) parents[i].close();
            parents[i] = null;
        }
    }

    /** Complete shared-16-node baseline, published as six faces together. */
    private static final class ParentJob implements AutoCloseable {
        final Builders[] builders = new Builders[6];
        final Mesh[] built = new Mesh[6];
        final BitSet changed = GroundViewClient.changed();
        final int across = GroundClient.shape().chunks();
        int face, tile;
        boolean transferred;

        ParentJob() {
            var snapshot = GroundCoverSnapshot.parent(GroundViewClient.heights(), changed);
            for (int i = 0; i < 6; i++) {
                builders[i] = new Builders(true);
                builders[i].surfaceFace = i;
                builders[i].cover = snapshot;
            }
        }

        void step(long deadline) {
            while (face < 6 && System.nanoTime() < deadline) {
                int x = (tile % across) * 16, z = (tile / across) * 16;
                baselineTop(builders[face], face, x, z, 16, changed.get((int) tileKey(face, x, z)));
                if (++tile == across * across) {
                    built[face] = builders[face].upload(0);
                    builders[face].close();
                    builders[face] = null;
                    uploads++;
                    tile = 0;
                    face++;
                }
            }
        }

        boolean done() {
            return face == 6;
        }

        Mesh[] finish() {
            if (!done()) throw new IllegalStateException("Incomplete parent coverage");
            transferred = true;
            close();
            return built;
        }

        public void close() {
            for (var b : builders) if (b != null) b.close();
            if (!transferred) for (var mesh : built) if (mesh != null) mesh.close();
        }
    }

    private static final class CoverJob implements AutoCloseable {
        final int patch, face, x0, z0;
        final Builders builders = new Builders(true);
        final GroundExterior.Job exterior;
        final BitSet changed = GroundViewClient.changed();
        int tile, pixel;

        CoverJob(int patch) {
            this.patch = patch;
            face = GroundViewPolicy.face(patch);
            x0 = GroundViewPolicy.x(patch);
            z0 = GroundViewPolicy.z(patch);
            builders.surfaceFace = face;
            builders.cover =
                    GroundCoverSnapshot.patch(
                            GroundViewClient.heights(),
                            changed,
                            face,
                            x0,
                            z0,
                            GroundViewClient::surface);
            exterior = new GroundExterior.Job(patch, mask);
        }

        void step(long deadline) {
            exterior.step(deadline);
            while (tile < 256 && System.nanoTime() < deadline) {
                int x = x0 + tile % 16 * 16, z = z0 + tile / 16 * 16;
                long key = tileKey(face, x, z);
                var surface = builders.cover.surface(key);
                if (surface == null) {
                    baselineTop(builders, face, x, z, 16, builders.cover.unknown(key));
                    tile++;
                    continue;
                }
                int u = pixel & 15, v = pixel >> 4;
                var state = surface.state(u, v);
                if (!state.isAir()) {
                    top(builders, state, x + u, surface.height(u, v) + 1 - 128, z + v, 1, false);
                    columnWalls(
                            builders, face, x + u, z + v, surface.height(u, v) + 1 - 128, state);
                }
                if (++pixel == 256) {
                    pixel = 0;
                    tile++;
                }
            }
        }

        boolean done() {
            return tile == 256 && exterior.done();
        }

        Mesh finish() {
            try {
                var mesh = builders.upload(0);
                try {
                    GroundExterior.replace(patch, exterior.finish());
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
            builders.close();
            exterior.close();
        }
    }

    private static void planetTop(
            Builders buffers, PlanetTerrain terrain, int face, int x, int z, int size) {
        if (size != GroundSurfaceMesh.GRID)
            throw new IllegalArgumentException("Projection tile must share 16-block nodes");
        var heights = buffers.cover == null ? GroundViewClient.heights() : buffers.cover.heights();
        for (var polygon :
                GroundSurfaceMesh.tile(heights, CubeSurfaceTopology.Face.values()[face], x, z)) {
            surfacePolygon(
                    buffers,
                    polygon.water()
                            ? heights.sea().fluid()
                            : heights.surfaceState(
                                    CubeSurfaceTopology.Face.values()[face], x + 8, z + 8),
                    polygon.points(),
                    x,
                    z);
        }
    }

    private static PlanetPackets.Profile tintProfile;
    private static BlockAndTintGetter tintView;
    private static Object tintLevel;
    private static int tintPlanet;

    static BlockAndTintGetter surfaceTint() {
        var profile = GroundViewClient.profile();
        int planet = GroundClient.planet();
        if (profile == null) return level;
        if (tintProfile != profile || tintLevel != level || tintPlanet != planet) {
            tintProfile = profile;
            tintLevel = level;
            tintPlanet = planet;
            tintView =
                    PlanetTint.view(
                            level,
                            level.registryAccess(),
                            planet,
                            GroundViewClient.terrain(),
                            profile.biome());
        }
        return tintView;
    }

    private static void surfacePolygon(
            Builders builders,
            BlockState state,
            List<BentStripGeometry.Point> points,
            int x,
            int z) {
        var level = surfaceTint();
        var mc = Minecraft.getInstance();
        var fluid = state.getFluidState();
        var handler =
                fluid.isEmpty()
                        ? null
                        : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                                .of(fluid.getType());
        if (handler
                == net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                        .DEFAULT) handler = null;
        var layer =
                handler == null
                        ? ItemBlockRenderTypes.getChunkRenderType(state)
                        : ItemBlockRenderTypes.getRenderLayer(fluid);
        var model = mc.getBlockRenderer().getBlockModel(state);
        var tops = model.getQuads(state, Direction.UP, RandomSource.create(0));
        var pos = GroundClient.storage(new BlockPos(builders.surfaceFace * 4096 + x, 128, z));
        var sprite =
                handler != null
                        ? mc.getTextureAtlas(
                                        net.minecraft.client.renderer.texture.TextureAtlas
                                                .LOCATION_BLOCKS)
                                .apply(handler.getStillTexture(fluid, level, pos))
                        : tops.isEmpty() ? model.getParticleIcon() : tops.getFirst().getSprite();
        int color =
                handler != null
                        ? handler.getTintColor(fluid, level, pos)
                        : !tops.isEmpty() && tops.getFirst().isTinted()
                                ? mc.getBlockColors()
                                        .getColor(state, level, pos, tops.getFirst().getTintIndex())
                                : 0xffffff;
        int albedo = GroundAlbedo.color(state, pos, level);
        var out = builders.get(builders.surface ? RenderType.solid() : layer);
        if (points.size() == 4) {
            for (var p : points) surfaceVertex(out, sprite, color, albedo, p, x, z);
        } else
            for (int i = 1; i < points.size() - 1; i++) {
                surfaceVertex(out, sprite, color, albedo, points.getFirst(), x, z);
                surfaceVertex(out, sprite, color, albedo, points.get(i), x, z);
                surfaceVertex(out, sprite, color, albedo, points.get(i + 1), x, z);
                surfaceVertex(out, sprite, color, albedo, points.get(i + 1), x, z);
            }
    }

    private static void surfaceVertex(
            BufferBuilder out,
            TextureAtlasSprite sprite,
            int color,
            int albedo,
            BentStripGeometry.Point p,
            int x,
            int z) {
        out.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 16)
                .setUv(
                        sprite.getU0()
                                + (sprite.getU1() - sprite.getU0()) * (float) ((p.x() - x) / 16),
                        sprite.getV0()
                                + (sprite.getV1() - sprite.getV0()) * (float) ((p.z() - z) / 16))
                .setUv2(240, 240)
                .setNormal(
                        GroundPipeline.channel(albedo, 16),
                        GroundPipeline.channel(albedo, 8),
                        GroundPipeline.channel(albedo, 0));
    }

    private static void top(
            Builders buffers,
            BlockState state,
            float x,
            float y,
            float z,
            float size,
            boolean unknown) {
        top(buffers, state, x, y, z, size, size, unknown);
    }

    private static void top(
            Builders buffers,
            BlockState state,
            float x,
            float y,
            float z,
            float width,
            float depth,
            boolean unknown) {
        topHeights(buffers, state, x, z, width, depth, new float[] {y, y, y, y}, unknown);
    }

    private static void topHeights(
            Builders buffers,
            BlockState state,
            float x,
            float z,
            float width,
            float depth,
            float[] heights,
            boolean unknown) {
        float y = heights[0];
        var level = surfaceTint();
        var mc = Minecraft.getInstance();
        var fluid = state.getFluidState();
        var handler =
                fluid.isEmpty()
                        ? null
                        : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                                .of(fluid.getType());
        if (handler
                == net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                        .DEFAULT) handler = null;
        var layer =
                unknown
                        ? RenderType.solid()
                        : handler == null
                                ? ItemBlockRenderTypes.getChunkRenderType(state)
                                : ItemBlockRenderTypes.getRenderLayer(fluid);
        var model = mc.getBlockRenderer().getBlockModel(state);
        var tops = model.getQuads(state, Direction.UP, RandomSource.create(0));
        var pos =
                GroundClient.storage(
                        new BlockPos(buffers.surfaceFace * 4096 + (int) x, (int) y + 128, (int) z));
        TextureAtlasSprite sprite =
                handler != null
                        ? mc.getTextureAtlas(
                                        net.minecraft.client.renderer.texture.TextureAtlas
                                                .LOCATION_BLOCKS)
                                .apply(handler.getStillTexture(fluid, level, pos))
                        : tops.isEmpty() ? model.getParticleIcon() : tops.getFirst().getSprite();
        var out = buffers.get(buffers.surface ? RenderType.solid() : layer);
        int albedo =
                buffers.surface ? (unknown ? 0xff3333 : GroundAlbedo.color(state, pos, level)) : 0;
        int color =
                unknown
                        ? 0xff3333
                        : handler != null
                                ? handler.getTintColor(fluid, level, pos)
                                : !tops.isEmpty() && tops.getFirst().isTinted()
                                        ? mc.getBlockColors()
                                                .getColor(
                                                        state,
                                                        level,
                                                        pos,
                                                        tops.getFirst().getTintIndex())
                                        : 0xffffff;
        float[][] p = {
            {x, heights[0], z},
            {x, heights[1], z + depth},
            {x + width, heights[2], z + depth},
            {x + width, heights[3], z}
        };
        float[] us = {sprite.getU0(), sprite.getU0(), sprite.getU1(), sprite.getU1()},
                vs = {sprite.getV0(), sprite.getV1(), sprite.getV1(), sprite.getV0()};
        for (int i = 0; i < 4; i++)
            out.addVertex(p[i][0], p[i][1], p[i][2])
                    .setColor(
                            color >> 16 & 255,
                            color >> 8 & 255,
                            color & 255,
                            buffers.surface ? Math.max(1, (int) width) : 255)
                    .setUv(us[i], vs[i])
                    .setUv2(240, 240)
                    .setNormal(
                            buffers.surface ? GroundPipeline.channel(albedo, 16) : 0,
                            buffers.surface ? GroundPipeline.channel(albedo, 8) : 1,
                            buffers.surface ? GroundPipeline.channel(albedo, 0) : 0);
    }

    private record OffsetConsumer(VertexConsumer target, float x, float y, float z)
            implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float a, float b, float c) {
            target.addVertex(a + x, b + y, c + z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            target.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            target.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            target.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            target.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float a, float b, float c) {
            target.setNormal(a, b, c);
            return this;
        }
    }

    private GroundRenderer() {}
}
