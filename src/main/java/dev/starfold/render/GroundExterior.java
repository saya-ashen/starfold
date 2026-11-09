package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.planet.Anomaly;
import dev.starfold.DevelopmentHooks;
import dev.starfold.space.RenderFrame;
import dev.starfold.worldgen.lod.VoxelLodPolicy;
import dev.starfold.worldgen.lod.VoxelMesher.Quad;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

/** Cached planet-local terraces and material colours; shares CPU geometry with chart patches. */
public final class GroundExterior {
    private static ShaderInstance shader;
    private static final java.util.Map<VertexBuffer,Long> bufferBytes=new java.util.WeakHashMap<>();
    static long gpuBytes() {
        long bytes=0;
        for(var mesh:patches) if(mesh!=null) bytes+=bufferBytes.getOrDefault(mesh,0L);
        for(var mesh:staging) if(mesh!=null) bytes+=bufferBytes.getOrDefault(mesh,0L);
        return bytes;
    }
    private static final VertexBuffer[] patches = new VertexBuffer[GroundViewPolicy.PATCH_COUNT],
            staging = new VertexBuffer[GroundViewPolicy.PATCH_COUNT];
    private static final boolean[] paired = new boolean[GroundViewPolicy.PATCH_COUNT],
            stagedPaired = new boolean[GroundViewPolicy.PATCH_COUNT];
    private static final long[] committed = new long[GroundViewPolicy.PATCH_COUNT];

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent c) -> {
                    try {
                        c.registerShader(
                                new ShaderInstance(
                                        c.getResourceProvider(),
                                        ResourceLocation.parse("starfold:native_ground_exterior"),
                                        GroundExteriorVertices.FORMAT),
                                s -> {
                                    shader = s;
                                    GasLighting.initialize(s);
                                    clear();
                                });
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
    }

    public static void clear() {
        GroundAlbedo.clear();
        for (int i = 0; i < patches.length; i++) {
            if (patches[i] != null) patches[i].close();
            if (staging[i] != null) staging[i].close();
            patches[i] = staging[i] = null;
            paired[i] = stagedPaired[i] = false;
        }
    }

    static ShaderInstance shader() {
        return shader;
    }

    /** Transfer ownership before the chart cache is cleared; no GPU rebuild at departure. */
    static VertexBuffer[] take(PlanetShape shape) {
        for (int i = 0; i < patches.length; i++)
            if (patches[i] == null && GroundViewPolicy.exists(shape, i)) return null;
        var out = patches.clone();
        Arrays.fill(patches, null);
        return out;
    }

    public static boolean ready() {
        if (shader == null) return false;
        var shape = GroundClient.shape();
        for (int i = 0; i < patches.length; i++)
            if (patches[i] == null && GroundViewPolicy.exists(shape, i)) return false;
        return true;
    }

    static void replace(int patch, VertexBuffer mesh, boolean generations) {
        var old = staging[patch];
        staging[patch] = mesh;
        stagedPaired[patch] = generations;
        if (old != null) old.close();
    }

    /** Swap a completed cohort, retaining one old generation through the dither fade. */
    static void commit() {
        long now = System.nanoTime();
        for (int i = 0; i < patches.length; i++)
            if (staging[i] != null) {
                if (patches[i] != null) patches[i].close();
                paired[i] = stagedPaired[i];
                patches[i] = staging[i];
                staging[i] = null;
                committed[i] = now;
            }
    }

    /** Render-thread adapter only: CPU quads are built once by the shared LOD worker pool. */
    static final class Job implements AutoCloseable {
        final GroundExteriorVertices out = new GroundExteriorVertices();
        final int face, x0, z0;
        final GroundSpace.Config mask;
        final PlanetShape shape;

        Job(int patch, PlanetShape shape, GroundSpace.Config mask) {
            this.shape = shape;
            this.mask = mask;
            face = GroundViewPolicy.face(patch);
            x0 = GroundViewPolicy.x(patch);
            z0 = GroundViewPolicy.z(patch);
        }

        void emit(Quad q, int rgb, boolean old) {
            var p = GroundCapturedMesh.project(shape, Face.VALUES[face], q.points());
            boolean sea = p.stream().allMatch(v -> Math.abs(v.length() - shape.radius()) < .15);
            int surfaceClass=dev.starfold.api.SurfaceClass.of(q.material(),net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO);
            out.quad(p, rgb, GroundExteriorVertices.surfaceTag(surfaceClass,old), sea);
        }

        void glow(Point[] t) {
            out.glow(t, Anomaly.GLOW_TINT);
        }

        VertexBuffer finish() {
            if (mask.core() > 0)
                for (var t :
                        GroundExteriorMesh.anomalyBeams(
                                shape, Face.VALUES[face], x0, z0, mask.height())) glow(t);
            var gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
            gpu.bind();
            try {
                var data=out.build();
                long bytes=data==null ? 0:data.vertexBuffer().remaining();
                gpu.upload(data);
                bufferBytes.put(gpu,bytes);
                return gpu;
            } catch (RuntimeException e) {
                gpu.close();
                throw e;
            } finally {
                VertexBuffer.unbind();
            }
        }

        public void close() {
            out.close();
        }
    }

    private static void vector(String name, Point p) {
        shader.getUniform(name).set((float) p.x(), (float) p.y(), (float) p.z());
    }

    private static void drawPatch(VertexBuffer gpu, Matrix4f matrix, float fade) {
        if (gpu == null) return;
        shader.getUniform("PatchFade").set(fade);
        gpu.bind();
        try {
            gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
        } finally {
            VertexBuffer.unbind();
        }
    }

    public static void draw(RenderFrame c) {
        if (!ready()) return;
        if (DevelopmentHooks.arrival.enabled()) DevelopmentHooks.ownDraw = true;
        var saved = RenderSystem.getShader();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShader(() -> shader);
            var e = GroundClient.eye();
            var orbit = GroundClient.orbit();
            var f = orbit.observerFrame;
            vector("ChartU", f.u());
            vector("ChartUp", f.up());
            vector("ChartV", f.v());
            vector(
                    "DrawCenter",
                    new Point(e.x(), -GroundClient.shape().radius(), e.z()).add(orbit.offset));
            dev.starfold.api.PlanetSurfaceRender.exteriorContext(new dev.starfold.api.PlanetSurfaceRender.Context(
                    GroundClient.model(), GroundClient.planet(), GroundClient.time(), null, GroundViewClient.terrain().terrainPlan()));
            vector("SunBody", GroundClient.home().local(GroundClient.star().position()));
            GasLighting.apply(
                    shader, GroundClient.model(), GroundClient.home(), GroundClient.time(), -1);
            vector("AtmosphereGrade", GroundAtmosphere.grade());
            GroundClient.lensUniforms(shader);
            var matrix =
                    GroundClient.place(
                            new Matrix4f(RenderSystem.getModelViewMatrix())
                                    .mul(c.matrixStack().last().pose()),
                            GroundClient.face(),
                            c.camera().getPosition());
            long now = System.nanoTime();
            for (int i = 0; i < patches.length; i++) {
                float fade = paired[i] ? VoxelLodPolicy.fade(committed[i], now) : 1;
                drawPatch(patches[i], matrix, fade);
            }
            if (orbit.weight >= 1)
                GroundAtmosphere.rim(
                        c,
                        GroundClient.model(),
                        GroundClient.planet(),
                        GroundClient.time(),
                        GroundClient.home().local(GroundClient.universeEye()),
                        matrix,
                        new Point(e.x(), -GroundClient.shape().radius(), e.z()).add(orbit.offset),
                        f.u(),
                        f.up(),
                        f.v(),
                        GroundClient.shape().radius());
        } finally {
            dev.starfold.api.PlanetSurfaceRender.exteriorContext(null);
            shader.getUniform("PatchFade").set(1f);
            GasLighting.clear(shader);
            RenderSystem.depthMask(write);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            if (saved != null) RenderSystem.setShader(() -> saved);
        }
    }

    private GroundExterior() {}
}
