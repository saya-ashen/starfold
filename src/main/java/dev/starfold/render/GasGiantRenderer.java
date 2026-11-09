package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GasAppearance;
import dev.starfold.chart.GasOcclusion;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceClientHost;
import dev.starfold.space.SpaceView;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** Physical catalog spheres; no PlanetProfile, native terrain storage or landing destination. */
public final class GasGiantRenderer {
    private static ShaderInstance shader;
    private static VertexBuffer mesh;

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent event) -> {
                    try {
                        event.registerShader(
                                new ShaderInstance(
                                        event.getResourceProvider(),
                                        ResourceLocation.parse("starfold:native_gas_giant"),
                                        DefaultVertexFormat.POSITION),
                                value -> {
                                    shader = value;
                                    GasLighting.initialize(value);
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
        double a = longitude * 2 * Math.PI / 64, b = latitude * Math.PI / 32;
        return new Point(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a));
    }

    private static void build() {
        var builder =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++) {
                var corners =
                        new Point[] {
                            vertex(x, y), vertex(x + 1, y), vertex(x + 1, y + 1), vertex(x, y + 1)
                        };
                for (int index : new int[] {0, 1, 2, 0, 2, 3}) {
                    var p = corners[index];
                    builder.addVertex((float) p.x(), (float) p.y(), (float) p.z());
                }
            }
        var data = builder.build();
        if (data == null) return;
        mesh = new VertexBuffer(VertexBuffer.Usage.STATIC);
        mesh.bind();
        try {
            mesh.upload(data);
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static void vector(String name, Point value) {
        shader.getUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }

    public static void draw(RenderFrame context) {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.GAS);
        try {
        if (!RendererGuard.pipelineSupported()
                || shader == null
                || RendererGuard.submerged(context.camera())) return;
        var entity = context.camera().getEntity();
        if (entity instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                        || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)))
            return;
        boolean region = SpaceClient.active();
        if (!region && !GroundClient.ready()) return;
        var model = region ? SpaceClientHost.get().ephemeris() : GroundClient.model();
        if (!GasOcclusion.present(model)) return;
        double tick = region ? SpaceClient.time() : GroundClient.time();
        var eye =
                region
                        ? SpaceClient.universeEye(context.camera().getPosition())
                        : GroundClient.universeEye();
        var pass = new RenderPassState(true, false, true);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            if (mesh == null) build();
            if (mesh == null) return;
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(() -> shader);
            if (region) SpaceClient.lens(shader);
            else GroundClient.lensUniforms(shader);
            for (int index : model.catalogIndices()) {
                var body = model.catalogBody(index);
                if (body.gas().isEmpty()) continue;
                var placed = model.frame(index, tick);
                double distance = placed.center().sub(eye).length();
                if (!SpaceView.visible(distance, body.radius())) continue;
                double scale = SpaceView.renderScale(distance);
                Matrix4f matrix;
                if (region) {
                    var placement = SpaceView.place(SpaceClient.frame(), eye, placed);
                    vector("DrawCenter", placement.center().scale(scale));
                    vector("ChartU", placement.u().scale(scale));
                    vector("ChartUp", placement.up().scale(scale));
                    vector("ChartV", placement.v().scale(scale));
                    vector("HomeCenter", GroundEphemeris.ZERO);
                    shader.getUniform("HomeRadius").set(0f);
                    vector("SkyColor", GroundEphemeris.ZERO);
                    vector("AtmosphereGrade", new Point(1, 1, 1));
                    shader.getUniform("BodyFade").set(1f);
                    matrix = context.positionMatrix();
                } else {
                    var home = GroundClient.home();
                    var orbit = GroundClient.orbit();
                    var axes = orbit.observerFrame;
                    vector("DrawCenter", PlanetRemoteRenderer.groundCenter(placed.center(), scale));
                    vector("ChartU", placed.inverseVector(home.vector(axes.u())).scale(scale));
                    vector("ChartUp", placed.inverseVector(home.vector(axes.up())).scale(scale));
                    vector("ChartV", placed.inverseVector(home.vector(axes.v())).scale(scale));
                    vector("HomeCenter", placed.local(home.center()));
                    shader.getUniform("HomeRadius")
                            .set((float) PlanetShape.coreRadius(GroundClient.planet()));
                    vector(
                            "SkyColor",
                            GroundAtmosphere.enabled()
                                    ? GroundAtmosphere.fog()
                                    : dev.starfold.chart.GroundSolar.sky(
                                            GroundClient.elevation(), GroundClient.darkness()));
                    vector("AtmosphereGrade", GroundAtmosphere.grade());
                    float rain =
                            net.minecraft.client.Minecraft.getInstance()
                                    .level
                                    .getRainLevel(
                                            context.tickCounter()
                                                    .getGameTimeDeltaPartialTick(false));
                    double haze =
                            GroundAtmosphere.enabled()
                                    ? 1
                                            - dev.starfold.sky.AtmosphereSettings.REMOTE_HAZE
                                                    * (1 - GroundAtmosphere.stars())
                                    : SpaceView.bodyFade(
                                            GroundClient.elevation(), GroundClient.darkness());
                    shader.getUniform("BodyFade").set((float) (haze * (1 - rain)));
                    matrix =
                            GroundClient.place(
                                    new Matrix4f(context.positionMatrix()),
                                    GroundClient.face(),
                                    context.camera().getPosition());
                }
                var gas = body.gas().orElseThrow();
                vector("EyeBody", placed.local(eye));
                vector("SunBody", placed.local(model.star(placed.center(), tick).position()));
                vector("SurfaceLow", GasAppearance.color(gas.lowColor()));
                vector("SurfaceHigh", GasAppearance.color(gas.highColor()));
                // Vanilla writes its reserved FogColor during drawWithShader.
                vector("GasFogColor", GasAtmosphereView.color(model, index, eye, tick));
                shader.getUniform("GasRadius").set((float) body.radius());
                shader.getUniform("GasPhase").set((float) GasAppearance.phase(gas.seed()));
                shader.getUniform("BandFrequency").set((float) GasAppearance.BANDS);
                shader.getUniform("BandWarp").set((float) GasAppearance.WARP);
                shader.getUniform("Ambient").set((float) GasAppearance.AMBIENT);
                shader.getUniform("Inside")
                        .set(eye.sub(placed.center()).length() < body.radius() ? 1f : 0f);
                GasLighting.apply(shader, model, placed, tick, index);
                mesh.bind();
                try {
                    mesh.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
                } finally {
                    VertexBuffer.unbind();
                }
            }
        } finally {
            try {
                GasLighting.clear(shader);
            } finally {
                try {
                    BufferUploader.invalidate();
                    com.mojang.blaze3d.platform.GlStateManager._glBindVertexArray(vertexArray);
                    com.mojang.blaze3d.platform.GlStateManager._glBindBuffer(
                            GL15.GL_ARRAY_BUFFER, arrayBuffer);
                    shader.clear();
                } finally {
                    pass.close();
                    com.mojang.blaze3d.shaders.ProgramManager.glUseProgram(program);
                }
            }
        }
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.GAS);
        }
    }

    private GasGiantRenderer() {}
}
