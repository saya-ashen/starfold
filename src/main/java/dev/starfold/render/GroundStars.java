package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.*;

import java.util.Random;

/** Deterministic, cached, world-oriented background only. Travel destinations are real meshes. */
public final class GroundStars {
    private static VertexBuffer stars;
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
                                    clear();
                                });
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
    }

    public static void clear() {
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
            int light = 110 + random.nextInt(146);
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

    public static void draw(RenderFrame context) {
        boolean region = SpaceClient.active();
        float fade =
                region
                        ? 1f
                        : (float)
                                (GroundClient.solar()
                                        ? GroundSolar.stars(
                                                GroundClient.sunElevation(),
                                                GroundClient.darkness())
                                        : GroundClient.darkness());
        if (fade <= 0 || shader == null) return;
        if (stars == null) build();
        if (stars == null) return;
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
                        1, 1); // Infinite background must not cover distant finite planets.
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
