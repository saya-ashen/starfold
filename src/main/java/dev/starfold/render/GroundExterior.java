package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.CubicColor;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.planet.Anomaly;
import dev.starfold.probe.ArrivalClientState;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.space.RenderFrame;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

/** Cached planet-local XYZ and low-frequency material colours. No atlas, UV repair or warp. */
public final class GroundExterior {
    private static ShaderInstance shader;
    private static final VertexBuffer[] patches = new VertexBuffer[GroundViewPolicy.PATCH_COUNT],
            staging = new VertexBuffer[GroundViewPolicy.PATCH_COUNT];

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent c) -> {
                    try {
                        c.registerShader(
                                new net.minecraft.client.renderer.ShaderInstance(
                                        c.getResourceProvider(),
                                        ResourceLocation.parse("starfold:native_ground_exterior"),
                                        DefaultVertexFormat.BLOCK),
                                s -> {
                                    shader = s;
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
            patches[i] = null;
            staging[i] = null;
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

    /** Every patch that exists on the observed planet has its mesh. */
    public static boolean ready() {
        if (shader == null) return false;
        var shape = GroundClient.shape();
        for (int i = 0; i < patches.length; i++)
            if (patches[i] == null && GroundViewPolicy.exists(shape, i)) return false;
        return true;
    }

    static void replace(int patch, VertexBuffer mesh) {
        var old = staging[patch];
        staging[patch] = mesh;
        if (old != null) old.close();
    }

    /** Swap the completed dirty cohort together. Never expose half-updated shared borders. */
    static void commit() {
        for (int i = 0; i < patches.length; i++)
            if (staging[i] != null) {
                var old = patches[i];
                patches[i] = staging[i];
                staging[i] = null;
                if (old != null) old.close();
            }
    }

    static final class Job implements AutoCloseable {
        final ByteBufferBuilder memory = new ByteBufferBuilder(256 * 1024);
        final BufferBuilder out =
                new BufferBuilder(memory, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
        final int patch, face, x0, z0;
        final BitSet changed = GroundViewClient.changed();
        final GroundSpace.Config mask;
        final Map<Long, GroundViewCache.Surface> surfaces = new HashMap<>();
        final Map<Long, Integer> colors = new HashMap<>();
        int tile, pixel;
        final PlanetShape shape = GroundClient.shape();
        final GroundExteriorMesh.Heights heights =
                new GroundExteriorMesh.Heights() {
                    public double node(Face f, int x, int z) {
                        double h =
                                GroundViewClient.heights() == null
                                        ? 0
                                        : GroundViewClient.heights().surfaceNode(f, x, z);
                        return masked(x, z) ? Math.max(h, mask.height()) : h;
                    }

                    public Double edited(Face f, int x, int z) {
                        var cell =
                                shape.topology()
                                        .canonical(new CubeSurfaceTopology.Cell(f, x, 0, z))
                                        .cell();
                        long key = key(cell.face(), cell.u(), cell.v());
                        var s = surfaces.get(key);
                        return s == null
                                ? null
                                : Math.max(
                                        -192, s.height(cell.u() & 15, cell.v() & 15) + 1 - 128.0);
                    }
                };

        Job(int patch, GroundSpace.Config mask) {
            this.patch = patch;
            this.mask = mask;
            face = GroundViewPolicy.face(patch);
            x0 = GroundViewPolicy.x(patch);
            z0 = GroundViewPolicy.z(patch);
            // Snapshot the small patch halo; no live per-frame world traversal during drawing.
            for (int x = x0 - 16; x <= x0 + 256; x += 16)
                for (int z = z0 - 16; z <= z0 + 256; z += 16) {
                    var c =
                            shape.topology()
                                    .canonical(
                                            new CubeSurfaceTopology.Cell(
                                                    Face.values()[face], x, 0, z))
                                    .cell();
                    long k = key(c.face(), c.u(), c.v());
                    var s = GroundViewClient.surface(k);
                    if (changed.get((int) k) && s != null) surfaces.put(k, s);
                }
        }

        boolean masked(double x, double z) {
            return mask.core() > 0
                    && Math.min(x, shape.size() - x) < mask.core()
                    && Math.min(z, shape.size() - z) < mask.core();
        }

        boolean refined(int x, int z) {
            for (int dx = -16; dx <= 16; dx += 16)
                for (int dz = -16; dz <= 16; dz += 16) {
                    var c =
                            shape.topology()
                                    .canonical(
                                            new CubeSurfaceTopology.Cell(
                                                    Face.values()[face], x + dx, 0, z + dz))
                                    .cell();
                    if (surfaces.containsKey(key(c.face(), c.u(), c.v()))) return true;
                }
            return false;
        }

        void step(long deadline) {
            var f = Face.values()[face];
            while (tile < 256 && System.nanoTime() < deadline) {
                int x = x0 + tile % 16 * 16, z = z0 + tile / 16 * 16;
                if (refined(x, z)) {
                    int a = x + (pixel & 15), b = z + (pixel >> 4);
                    var p = GroundExteriorMesh.node(shape, heights, f, a, b);
                    var q = GroundExteriorMesh.node(shape, heights, f, a, b + 1);
                    var r = GroundExteriorMesh.node(shape, heights, f, a + 1, b + 1);
                    var s = GroundExteriorMesh.node(shape, heights, f, a + 1, b);
                    var centre = GroundExteriorMesh.centre(shape, heights, f, a, b);
                    int color = color(a, b);
                    triangle(p, q, centre, color);
                    triangle(q, r, centre, color);
                    triangle(r, s, centre, color);
                    triangle(s, p, centre, color);
                    if (++pixel == 256) {
                        pixel = 0;
                        tile++;
                    }
                } else {
                    var p = GroundExteriorMesh.baseline(shape, heights, f, x, z);
                    var q = GroundExteriorMesh.baseline(shape, heights, f, x, z + 16);
                    var r = GroundExteriorMesh.baseline(shape, heights, f, x + 16, z + 16);
                    var s = GroundExteriorMesh.baseline(shape, heights, f, x + 16, z);
                    int color = color(x + 8, z + 8);
                    triangle(p, q, r, color);
                    triangle(p, r, s, color);
                    tile++;
                }
            }
        }

        int color(int x, int z) {
            long k = key(Face.values()[face], x, z);
            return colors.computeIfAbsent(
                    k,
                    ignored -> {
                        int a = x / 16 * 16, b = z / 16 * 16;
                        var surface = surfaces.get(k);
                        double[] sum = new double[3];
                        int n = 0;
                        int step = surface == null ? 8 : 1;
                        for (int u = 0; u < 16; u += step)
                            for (int v = 0; v < 16; v += step) {
                                int rgb = columnColor(a + u, b + v, surface);
                                for (int channel = 0; channel < 3; channel++)
                                    sum[channel] +=
                                            CubicColor.linear(
                                                    (rgb >> (16 - channel * 8) & 255) / 255.0);
                                n++;
                            }
                        int rgb = 0;
                        for (double channel : sum)
                            rgb =
                                    rgb << 8
                                            | (int)
                                                    Math.round(
                                                            CubicColor.encoded(channel / n) * 255);
                        return rgb;
                    });
        }

        int columnColor(int x, int z, GroundViewCache.Surface surface) {
            var heights = GroundViewClient.heights();
            if (surface == null
                    && heights == null
                    && changed.get((int) key(Face.values()[face], x, z))) return 0xff3333;
            var state =
                    surface != null
                            ? surface.state(x & 15, z & 15)
                            : heights == null
                                    ? Blocks.GRASS_BLOCK.defaultBlockState()
                                    : heights.surfaceState(Face.values()[face], x, z);
            if (masked(x, z)) state = Anomaly.shell();
            int y =
                    surface != null
                            ? surface.height(x & 15, z & 15)
                            : heights == null ? 127 : heights.surfaceY(Face.values()[face], x, z);
            return GroundAlbedo.color(
                    state,
                    GroundClient.storage(new BlockPos(face * 4096 + x, y, z)),
                    GroundRenderer.surfaceTint());
        }

        void triangle(Point a, Point b, Point c, int color) {
            var normal = PlanetSpace.unit(b.sub(a).cross(c.sub(a)));
            if (normal.dot(a) < 0) normal = normal.scale(-1);
            double radius = shape.radius();
            boolean sea =
                    Math.abs(a.length() - radius) < .15
                            && Math.abs(b.length() - radius) < .15
                            && Math.abs(c.length() - radius) < .15;
            for (var p : List.of(a, b, c)) {
                var n = sea ? PlanetSpace.unit(p) : normal;
                out.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                        .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255)
                        .setUv(0, 0)
                        .setUv2(240, 240)
                        .setNormal((float) n.x(), (float) n.y(), (float) n.z());
            }
        }

        boolean done() {
            return tile == 256;
        }

        /** Alpha 0 marks a self-lit vertex for the exterior shader. */
        void glow(Point[] t) {
            for (var p : t) {
                var n = PlanetSpace.unit(p);
                out.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                        .setColor(
                                Anomaly.GLOW_TINT >> 16 & 255,
                                Anomaly.GLOW_TINT >> 8 & 255,
                                Anomaly.GLOW_TINT & 255,
                                0)
                        .setUv(0, 0)
                        .setUv2(240, 240)
                        .setNormal((float) n.x(), (float) n.y(), (float) n.z());
            }
        }

        VertexBuffer finish() {
            if (mask.core() > 0)
                for (var t :
                        GroundExteriorMesh.anomalyBeams(
                                shape, Face.values()[face], x0, z0, mask.height())) glow(t);
            var data = out.build();
            var gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
            gpu.bind();
            try {
                gpu.upload(data);
                return gpu;
            } catch (RuntimeException e) {
                gpu.close();
                throw e;
            } finally {
                VertexBuffer.unbind();
            }
        }

        public void close() {
            memory.close();
        }
    }

    private static long key(Face f, int x, int z) {
        return x / 16 + 128L * (z / 16 + 128L * f.ordinal());
    }

    private static void vector(String name, Point p) {
        shader.getUniform(name).set((float) p.x(), (float) p.y(), (float) p.z());
    }

    public static void draw(RenderFrame c) {
        if (!ready()) return;
        if (ArrivalProbe.ENABLED) ArrivalClientState.own = true;
        var previous = RenderSystem.getShader();
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
            // At weight=1 the former warp is just this rigid basis transform + origin offset.
            vector("ChartU", f.u());
            vector("ChartUp", f.up());
            vector("ChartV", f.v());
            vector(
                    "DrawCenter",
                    new Point(e.x(), -GroundClient.shape().radius(), e.z()).add(orbit.offset));
            vector("SunBody", GroundClient.home().local(GroundClient.star().position()));
            GroundClient.lensUniforms(shader);
            var matrix =
                    GroundClient.place(
                            new Matrix4f(RenderSystem.getModelViewMatrix())
                                    .mul(c.matrixStack().last().pose()),
                            GroundClient.face(),
                            c.camera().getPosition());
            for (var gpu : patches) {
                if (gpu == null) continue;
                gpu.bind();
                try {
                    gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
                } finally {
                    VertexBuffer.unbind();
                }
            }
        } finally {
            RenderSystem.depthMask(write);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            if (previous != null) RenderSystem.setShader(() -> previous);
        }
    }

    private GroundExterior() {}
}
