package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceView;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** Cached emissive sphere, not a block planet or skybox sprite. Celestial data is separate. */
public final class GroundSunRenderer {
    private static ShaderInstance shader;
    private static VertexBuffer mesh;

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent c) -> {
                    try {
                        c.registerShader(
                                new net.minecraft.client.renderer.ShaderInstance(
                                        c.getResourceProvider(),
                                        ResourceLocation.parse("starfold:native_ground_sun"),
                                        DefaultVertexFormat.POSITION),
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
        if (mesh != null) mesh.close();
        mesh = null;
    }

    private static Point vertex(int longitude, int latitude) {
        double a = longitude * Math.PI * 2 / 64, b = latitude * Math.PI / 32;
        return new Point(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a));
    }

    private static void build() {
        var b =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++) {
                var q =
                        new Point[] {
                            vertex(x, y), vertex(x + 1, y), vertex(x + 1, y + 1), vertex(x, y + 1)
                        };
                for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
                    var p = q[i];
                    b.addVertex((float) p.x(), (float) p.y(), (float) p.z());
                }
            }
        var data = b.build();
        if (data != null) {
            mesh = new VertexBuffer(VertexBuffer.Usage.STATIC);
            mesh.bind();
            try {
                mesh.upload(data);
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    private static void vector(String name, Point p) {
        shader.getUniform(name).set((float) p.x(), (float) p.y(), (float) p.z());
    }

    public static void draw(RenderFrame context) {
        if (SpaceClient.active()) {
            drawRegion(context);
            return;
        }
        if (!GroundClient.solar() || shader == null) return;
        if (mesh == null) build();
        if (mesh == null) return;
        var home = GroundClient.planetFrame(GroundClient.planet());
        var frame = PlanetOrbit.frame(GroundClient.shape(), GroundClient.state().pose());
        var eye = GroundClient.universeEye();
        double blend = GroundOrbit.weight(GroundClient.eye().y());
        var previous = RenderSystem.getShader();
        var color = RenderSystem.getShaderColor().clone();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                enabledBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int sr = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dr = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        var stack = context.matrixStack() == null ? new PoseStack() : context.matrixStack();
        stack.pushPose();
        GroundClient.place(
                stack.last().pose(), GroundClient.face(), context.camera().getPosition());
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(() -> shader);
            GroundClient.lensUniforms(shader);
            // Every sun is drawn where it is; the far ones are small discs among the stars.
            for (var sun : GroundClient.suns()) {
                var delta = home.inverseVector(sun.position().sub(eye));
                double distance = Math.max(1e-6, delta.length());
                double drawDistance =
                        Math.min(256, Math.max(16, GroundClient.view() * .5)) * (1 - blend)
                                + distance * blend;
                double scale = drawDistance / distance;
                var centre = GroundClient.eye().add(frame.local(delta).scale(scale));
                vector("DrawCenter", centre);
                vector("ChartU", frame.u());
                vector("ChartUp", frame.up());
                vector("ChartV", frame.v());
                vector("SunBody", home.local(sun.position()));
                vector("EyeBody", home.local(eye));
                shader.getUniform("SunRadius").set((float) sun.radius());
                shader.getUniform("DrawRadius").set((float) (sun.radius() * scale));
                shader.getUniform("PlanetRadius")
                        .set((float) PlanetShape.coreRadius(GroundClient.planet()));
                shader.getUniform("Background").set((float) (1 - blend));
                shader.getUniform("SunTime")
                        .set((float) ((GroundClient.simulationTime() % 24000) / 20));
                var base = new Matrix4f(context.positionMatrix()).mul(stack.last().pose());
                mesh.bind();
                try {
                    RenderSystem.depthMask(false);
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    shader.getUniform("Halo").set(1f);
                    mesh.drawWithShader(base, RenderSystem.getProjectionMatrix(), shader);
                    RenderSystem.depthMask(true);
                    RenderSystem.disableBlend();
                    shader.getUniform("Halo").set(0f);
                    mesh.drawWithShader(base, RenderSystem.getProjectionMatrix(), shader);
                } finally {
                    VertexBuffer.unbind();
                }
            }
        } finally {
            RenderSystem.depthMask(mask);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.blendFuncSeparate(sr, dr, sa, da);
            if (enabledBlend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (previous != null) RenderSystem.setShader(() -> previous);
            stack.popPose();
        }
    }

    /** Physical spheres in region axes; remote planet depth replaces the chart's selected-core shortcut. */
    private static void drawRegion(RenderFrame context) {
        if (shader == null) return;
        if (mesh == null) build();
        if (mesh == null) return;
        var eye = SpaceClient.universeEye(context.camera().getPosition());
        var frame = SpaceClient.frame();
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
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(() -> shader);
            SpaceClient.lens(shader);
            vector("ChartU", frame.universeVector(new Point(1, 0, 0)));
            vector("ChartUp", frame.universeVector(new Point(0, 1, 0)));
            vector("ChartV", frame.universeVector(new Point(0, 0, 1)));
            vector("EyeBody", GroundEphemeris.ZERO);
            shader.getUniform("PlanetRadius").set(0f);
            shader.getUniform("Background").set(0f);
            shader.getUniform("SunTime").set((float) ((SpaceClient.time() % 24000) / 20));
            for (var sun : SpaceClient.suns(eye)) {
                var delta = sun.position().sub(eye);
                if (!SpaceView.visible(delta.length(), sun.radius() * 1.12)) continue;
                vector("DrawCenter", SpaceView.relative(frame, eye, sun.position()));
                vector("SunBody", delta);
                shader.getUniform("SunRadius").set((float) sun.radius());
                shader.getUniform("DrawRadius").set((float) sun.radius());
                mesh.bind();
                try {
                    RenderSystem.depthMask(false);
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    shader.getUniform("Halo").set(1f);
                    mesh.drawWithShader(
                            context.positionMatrix(), RenderSystem.getProjectionMatrix(), shader);
                    RenderSystem.depthMask(true);
                    RenderSystem.disableBlend();
                    shader.getUniform("Halo").set(0f);
                    mesh.drawWithShader(
                            context.positionMatrix(), RenderSystem.getProjectionMatrix(), shader);
                } finally {
                    VertexBuffer.unbind();
                }
            }
        } finally {
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

    private GroundSunRenderer() {}
}
