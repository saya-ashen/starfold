package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundSolar;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceClientHost;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.*;

import java.util.Random;

/** Dim cached galactic background and a small, observer-relative batch of actual suns. */
public final class GroundStars {
    private static VertexBuffer stars, realStars;
    private static ShaderInstance shader;

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent c) -> {
                    try {
                        c.registerShader(
                                new net.minecraft.client.renderer.ShaderInstance(
                                        c.getResourceProvider(),
                                        ResourceLocation.parse("starfold:native_ground_stars"),
                                        DefaultVertexFormat.POSITION_COLOR),
                                s -> {
                                    shader = s;
                                    shader.getUniform("DepthFar").set(ReversedDepth.ACTIVE ? 0f : 1f);
                                    clear();
                                });
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
    }

    public static void clear() {
        if (realStars != null) {
            realStars.close();
            realStars = null;
        }
        if (stars != null) {
            stars.close();
            stars = null;
        }
    }

    private static void build() {
        var random = new Random(5601);
        var b =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 2400; i++) {
            double y = random.nextDouble() * 2 - 1,
                    a = random.nextDouble() * Math.PI * 2,
                    r = Math.sqrt(1 - y * y);
            var n = new Vector3d(r * Math.cos(a), y, r * Math.sin(a));
            var u =
                    new Vector3d(Math.abs(y) < .9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0))
                            .cross(n)
                            .normalize();
            var v = new Vector3d(n).cross(u).normalize();
            double size = .025 + Math.pow(random.nextDouble(), 5) * .12;
            int light = 28 + random.nextInt(40);
            int warm = random.nextInt(5);
            var center = new Vector3d(n).mul(128);
            for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
                double x = k == 0 || k == 3 ? -size : size, z = k < 2 ? -size : size;
                var p = new Vector3d(center).fma(x, u).fma(z, v);
                b.addVertex((float) p.x, (float) p.y, (float) p.z)
                        .setColor(
                                warm == 0 ? light * 4 / 5 : light,
                                warm == 1 ? light * 9 / 10 : light,
                                warm == 1 ? light * 4 / 5 : light,
                                255);
            }
        }
        var mesh = b.build();
        if (mesh != null) {
            stars = new VertexBuffer(VertexBuffer.Usage.STATIC);
            stars.bind();
            try {
                stars.upload(mesh);
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    static Point direction(Point star, Point eye) {
        var delta = star.sub(eye);
        return delta.scale(1 / delta.length());
    }

    static double brightness(double distance) {
        return .35 + .65 / (1 + Math.pow(distance / 5_200_000, 2));
    }

    private static boolean buildReal(RenderFrame context, boolean region) {
        var model = region ? SpaceClientHost.get().ephemeris() : GroundClient.model();
        if (model == null) return false;
        var eye = region
                ? SpaceClient.universeEye(context.camera().getPosition())
                : GroundClient.universeEye();
        var b = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (var star : model.everyStar(region ? SpaceClient.time() : GroundClient.time())) {
            double distance = star.position().sub(eye).length();
            // Nearby suns already have resolved emissive meshes; points are only their distant image.
            if (distance <= star.radius() * 100) continue;
            var d = direction(star.position(), eye);
            var n = new Vector3d(d.x(), d.y(), d.z());
            var u = new Vector3d(Math.abs(n.y) < .9
                    ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0)).cross(n).normalize();
            var v = new Vector3d(n).cross(u).normalize();
            double light = brightness(distance);
            double size = .16 + .20 * light;
            int color = (int) (255 * light);
            var center = new Vector3d(n).mul(128);
            for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
                double x = k == 0 || k == 3 ? -size : size, y = k < 2 ? -size : size;
                var p = new Vector3d(center).fma(x, u).fma(y, v);
                b.addVertex((float) p.x, (float) p.y, (float) p.z)
                        .setColor(color, color, color, 255);
            }
        }
        var mesh = b.build();
        if (mesh == null) return false;
        if (realStars == null) realStars = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        realStars.bind();
        try {
            realStars.upload(mesh);
        } finally {
            VertexBuffer.unbind();
        }
        return true;
    }

    public static void draw(RenderFrame context) {
        if (!RendererGuard.pipelineSupported()) return;
        boolean region = SpaceClient.active();
        float fade =
                region
                        ? 1f
                        : GroundAtmosphere.enabled()
                                ? GroundAtmosphere.stars()
                                : (float)
                                        (GroundClient.solar()
                                                ? GroundSolar.stars(
                                                        GroundClient.sunElevation(),
                                                        GroundClient.darkness())
                                                : GroundClient.darkness());
        if (fade <= 0 || shader == null) return;
        if (stars == null) build();
        if (stars == null) return;
        boolean real = buildReal(context, region);
        var previous = RenderSystem.getShader();
        var color = RenderSystem.getShaderColor().clone();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int sr = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dr = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        double[] range = region ? new double[2] : null;
        if (region) GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, range);
        try {
            if (region)
                GL11.glDepthRange(
                        ReversedDepth.ACTIVE ? 0 : 1,
                        ReversedDepth.ACTIVE ? 0 : 1); // Infinite background must not cover distant finite planets.
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, fade);
            RenderSystem.setShader(() -> shader);
            if (region) SpaceClient.lens(shader);
            else GroundClient.lensUniforms(shader);
            Matrix4f model;
            if (region)
                model =
                        new Matrix4f(context.positionMatrix())
                                .m30(0)
                                .m31(0)
                                .m32(0)
                                .rotate(new org.joml.Quaternionf(SpaceClient.frame().rotation()));
            else {
                var map =
                        new PlanetOrbit(
                                GroundClient.shape(),
                                GroundClient.state().pose(),
                                GroundClient.eye(),
                                GroundClient.view());
                model = new Matrix4f(context.positionMatrix());
                model.m30(0)
                        .m31(0)
                        .m32(0)
                        .rotateY(GroundClient.chartYaw())
                        .rotate(new org.joml.Quaternionf(map.observerFrame.rotation().conjugate()))
                        .rotate(
                                new org.joml.Quaternionf(
                                        GroundClient.planetFrame(GroundClient.planet())
                                                .rotation()
                                                .conjugate()));
            }
            stars.bind();
            try {
                stars.drawWithShader(model, RenderSystem.getProjectionMatrix(), shader);
                if (real) {
                    realStars.bind();
                    realStars.drawWithShader(model, RenderSystem.getProjectionMatrix(), shader);
                }
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            if (region) GL11.glDepthRange(range[0], range[1]);
            RenderSystem.depthMask(mask);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.blendFuncSeparate(sr, dr, sa, da);
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (previous != null) RenderSystem.setShader(() -> previous);
        }
    }

    private GroundStars() {}
}
