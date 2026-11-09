package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.sky.AtmosphereRim;
import dev.starfold.sky.AtmosphereScattering;
import dev.starfold.sky.AtmosphereSettings;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.worldgen.PlanetAtmosphere;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** Low-resolution, vertex-integrated scattering. No fullscreen pass or per-pixel ray marching. */
public final class GroundAtmosphere {
    private static ShaderInstance shader;
    private static GroundSkyMesh skyMesh;

    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final Point BLACK = new Point(0, 0, 0), WHITE = new Point(1, 1, 1);
    private static PlanetAtmosphere frameAir;
    private static Point frameEye, frameSun, frameFog, frameGrade;
    private static double frameRadius, frameTransmission, frameStrength;
    private static float frameStars = -1;

    /** Called before LevelRenderer, including its fog setup, not just AFTER_SKY. */
    static void beginFrame() {
        frameAir = null;
        frameFog = null;
        frameStars = -1;
    }

    private static void context() {
        if (GroundClient.projectedFrame() && frameAir != null) return;
        // Calls outside the projected render scope must never reuse a previous camera/world.
        beginFrame();
        frameAir = air();
        frameEye = eye();
        frameSun = sun();
        frameRadius = GroundClient.shape().radius();
        frameTransmission =
                dev.starfold.chart.GasOcclusion.sunlight(
                        GroundClient.model(), GroundClient.universeEye(), GroundClient.time());
        frameStrength = AtmosphereScattering.fogStrength(frameAir, GroundClient.eye().y());
        frameGrade = AtmosphereScattering.grade(frameAir, GroundClient.eye().y());
    }

    public static void initialize(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent e) -> {
                    try {
                        e.registerShader(
                                new ShaderInstance(
                                        e.getResourceProvider(),
                                        ResourceLocation.parse("starfold:atmosphere_color"),
                                        DefaultVertexFormat.POSITION_COLOR),
                                s -> {
                                    shader = s;
                                    shader.getUniform("DepthFar").set(ReversedDepth.ACTIVE ? 0f : 1f);
                                    clear();
                                });
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
    }

    public static void clear() {
        beginFrame();
        if (skyMesh != null) {
            skyMesh.close();
            skyMesh = null;
        }
    }

    public static boolean enabled() {
        return RendererGuard.pipelineSupported() && GroundClient.ready() && !SpaceClient.present();
    }

    public static PlanetAtmosphere air() {
        var profile = GroundViewClient.profile();
        return profile != null && GroundViewClient.cachedPlanet() == GroundClient.planet()
                ? profile.generation().environment().atmosphere()
                : GroundClient.model().body(GroundClient.planet()).atmosphere();
    }

    private static Point eye() {
        return GroundClient.universeEye().sub(GroundClient.home().center());
    }

    private static Point sun() {
        return PlanetSpace.unit(GroundClient.star().position().sub(GroundClient.universeEye()));
    }

    /** Convert the actual camera ray, not the player's aim, through the curved observer frame. */
    private static Point view() {
        var v = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
        var chart = GroundClient.frame().vectorToStorage(new Point(v.x(), v.y(), v.z()));
        return GroundClient.home().vector(GroundClient.orbit().observerFrame.world(chart));
    }

    public static Point fog() {
        context();
        if (frameFog == null)
            frameFog = AtmosphereScattering.display(
                    AtmosphereScattering.fog(
                            frameAir, frameRadius, frameEye, view(), frameSun, frameTransmission));
        return frameFog;
    }

    public static double strength() {
        context();
        return frameStrength;
    }

    public static float stars() {
        context();
        if (frameStars < 0) {
            // Sample zenith, not the camera aim: looking down must not make stars pop.
            var zenith = AtmosphereScattering.sky(
                    frameAir, frameRadius, frameEye, PlanetSpace.unit(frameEye), frameSun,
                    frameTransmission);
            frameStars = (float) AtmosphereScattering.stars(zenith);
        }
        return frameStars;
    }

    public static Point grade() {
        if (!enabled()) return WHITE;
        context();
        return frameGrade;
    }

    private static Matrix4f skyMatrix(RenderFrame c) {
        return new Matrix4f(c.positionMatrix())
                .m30(0)
                .m31(0)
                .m32(0)
                .rotateY(GroundClient.chartYaw())
                .rotate(
                        new org.joml.Quaternionf(
                                GroundClient.orbit().observerFrame.rotation().conjugate()))
                .rotate(new org.joml.Quaternionf(GroundClient.home().rotation().conjugate()));
    }

    public static void drawSky(RenderFrame c) {
        if (!enabled()
                || shader == null
                || RendererGuard.submerged(c.camera()))
            return;
        // Match 1.21.1 LevelRenderer.doesMobEffectBlockSky for this newly added sky pass.
        var entity = c.camera().getEntity();
        if (entity instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                        || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)))
            return;
        if (skyMesh == null) skyMesh = new GroundSkyMesh();
        skyMesh.begin();
        context();
        var eye = frameEye;
        double highAltitude =
                AtmosphereRim.highAltitudeWeight(eye.length() - frameRadius);
        var sun = frameSun;
        double gasTransmission = frameTransmission;
        var air = frameAir;
        boolean black = air.density() == 0 || highAltitude == 1 || gasTransmission == 0;
        var up = PlanetSpace.unit(eye);
        var east = AtmosphereScattering.horizon(up, new Point(1, 0, 0));
        var north = up.cross(east);
        for (int i = 0; i < GroundSkyMesh.VERTICES; i++) {
            var d = GroundSkyMesh.direction(i, east, up, north);
            var color = black ? BLACK :
                    AtmosphereScattering.display(
                                    AtmosphereScattering.sky(
                                            air, frameRadius, eye, d, sun, gasTransmission))
                            .scale(1 - highAltitude);
            skyMesh.vertex(d, color);
        }
        skyMesh.upload();
        draw(null, skyMatrix(c), true);
        if (highAltitude > 0) {
            // The thin current-body limb replaces the broad background, even before the native
            // terrain hands over to a complete exterior mesh. Work in universe axes like skyMatrix.
            var home = GroundClient.home();
            rim(
                    c,
                    GroundClient.model(),
                    GroundClient.planet(),
                    GroundClient.time(),
                    home.local(GroundClient.universeEye()),
                    skyMatrix(c),
                    eye.scale(-1),
                    home.inverseVector(new Point(1, 0, 0)),
                    home.inverseVector(new Point(0, 1, 0)),
                    home.inverseVector(new Point(0, 0, 1)),
                    GroundClient.shape().radius());
        }
    }

    /** Remote/region bodies follow their meshes; the native current-body limb belongs to AFTER_SKY. */
    public static void rim(
            RenderFrame c,
            GroundEphemeris.Model model,
            int planet,
            double time,
            Point bodyEye,
            Matrix4f matrix,
            Point center,
            Point u,
            Point up,
            Point v,
            double radius) {
        if (!RendererGuard.pipelineSupported() || shader == null) return;
        double distance = bodyEye.length();
        double passWeight = 1;
        if (!SpaceClient.active() && planet == GroundClient.planet()) {
            passWeight =
                    AtmosphereRim.currentPassWeight(
                            distance - radius,
                            c.event().getStage()
                                    == net.neoforged.neoforge.client.event.RenderLevelStageEvent
                                            .Stage.AFTER_SKY);
            if (passWeight == 0) return;
        }
        var air = model.body(planet).atmosphere();
        int n = rimSegments(air.density(), distance, radius);
        if (n == 0) return;
        var normal = PlanetSpace.unit(bodyEye);
        var a = AtmosphereScattering.horizon(normal, new Point(1, 0, 0));
        var b = normal.cross(a);
        var placed = model.at(planet, time);
        var sunBody = placed.local(model.star(placed.center(), time).position());
        var light = PlanetSpace.unit(sunBody);
        var gasShadows = dev.starfold.chart.GasOcclusion.relative(model, time, placed, -1);
        int bands = AtmosphereSettings.RIM_BANDS;
        var points = new Point[(n + 1) * (bands + 1)];
        var colors = new Point[points.length];
        for (int j = 0; j <= bands; j++) {
            var section =
                    AtmosphereRim.section(
                            radius, distance, AtmosphereRim.height(radius) * j / bands);
            var origin = normal.scale(section.axial());
            for (int i = 0; i <= n; i++) {
                double angle = i * Math.PI * 2 / n;
                var p =
                        origin.add(a.scale(Math.cos(angle) * section.radial()))
                                .add(b.scale(Math.sin(angle) * section.radial()));
                int index = j * (n + 1) + i;
                points[index] = center.add(new Point(p.dot(u), p.dot(up), p.dot(v)));
                colors[index] =
                        AtmosphereScattering.display(
                                AtmosphereScattering.sky(
                                        air,
                                        radius,
                                        bodyEye,
                                        p.sub(bodyEye),
                                        light,
                                        dev.starfold.chart.GasOcclusion.transmission(
                                                gasShadows, p, sunBody)));
            }
        }
        var out =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int j = 0; j < bands; j++)
            for (int i = 0; i < n; i++) {
                int k = j * (n + 1) + i;
                for (int index : new int[] {k, k + 1, k + n + 2, k, k + n + 2, k + n + 1})
                    vertex(
                            out,
                            points[index],
                            colors[index],
                            (float)
                                    (passWeight
                                            * AtmosphereRim.opacity(
                                                    (double) (index / (n + 1)) / bands)));
            }
        draw(out, matrix, false);
    }

    static int rimSegments(double density, double distance, double radius) {
        if (density == 0
                || distance <= radius + AtmosphereSettings.SCALE_HEIGHT
                || radius / distance < AtmosphereSettings.MIN_RIM_ANGULAR_RADIUS) return 0;
        return (int) Math.clamp(
                Math.ceil(radius / distance * AtmosphereSettings.RIM_SEGMENTS
                        * AtmosphereSettings.RIM_ANGULAR_RESOLUTION),
                AtmosphereSettings.MIN_RIM_SEGMENTS, AtmosphereSettings.RIM_SEGMENTS);
    }

    private static void vertex(BufferBuilder b, Point p, Point color, float alpha) {
        b.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                .setColor((float) color.x(), (float) color.y(), (float) color.z(), alpha);
    }

    private static void draw(BufferBuilder out, Matrix4f matrix, boolean background) {
        var data = out == null ? null : out.build();
        if (data == null && !background) return;
        var pass = new RenderPassState(true, true, false);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            if (background) RenderSystem.disableBlend();
            else {
                RenderSystem.enableBlend();
                // Bounded limb coverage: adding display RGB to sky RGB clips into a white ring.
                RenderSystem.defaultBlendFunc();
            }
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(() -> shader);
            if (SpaceClient.active()) SpaceClient.lens(shader);
            else GroundClient.lensUniforms(shader);
            shader.getUniform("Background").set(background ? 1f : 0f);
            // Immediate rim and persistent sky use the same uniforms and state boundary.
            RenderSystem.getModelViewMatrix().set(matrix);
            if (background) skyMesh.drawWithShader(matrix, shader);
            else BufferUploader.drawWithShader(data);
        } finally {
            RenderSystem.getModelViewMatrix().set(MODEL_VIEW);

            // drawWithShader clears its program. Reset ShaderInstance's last-program cache even
            // on an exceptional draw, then restore the actual binding, not just the selected
            // shader.
            // Leave the cache invalidated so the next apply cannot skip its required bind.
            try {
                shader.clear();
            } finally {
                pass.close();
                com.mojang.blaze3d.shaders.ProgramManager.glUseProgram(program);
            }
        }
    }

    private GroundAtmosphere() {}
}
