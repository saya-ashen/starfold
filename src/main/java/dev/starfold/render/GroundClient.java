package dev.starfold.render;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundCamera;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundMotion;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.GroundSpaceOptics;
import dev.starfold.chart.PlanetAir;
import dev.starfold.chart.PlanetChart;
import dev.starfold.chart.PlanetGap;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.ground.GroundPackets;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceAttitudeClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigationClient;
import dev.starfold.waypoint.WaypointClient;

import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Native movement/inventory remain untouched. This adapter only supplies render coordinates. */
public final class GroundClient {
    private static ClientLevel level;
    private static GroundEphemeris.Model model;
    private static long tick, received;
    private static GroundAir.Config air = GroundAir.DEFAULT;
    private static boolean repair = true, heldF8;

    public static void air(GroundAir.Config config) {
        air = config;
    }

    public static PlanetGap gap() {
        return new PlanetGap(shape(), Face.values()[face()], eye(), view(), repair, air, true);
    }

    /** The curved view of the observed planet from where the observer is. */
    public static PlanetOrbit orbit() {
        return new PlanetOrbit(shape(), pose(), eye(), view());
    }

    private static int source = -1;
    private static boolean derived, coarse, cover, stitch;

    public static void source(int face, boolean mesh) {
        source(face, mesh, false);
    }

    public static void source(int face, boolean mesh, boolean sky) {
        source = face;
        derived = mesh;
        coarse = sky;
        cover = false;
        stitch = false;
    }

    public static void cover(boolean value) {
        cover = value;
    }

    public static void stitch(boolean value) {
        stitch = value;
    }

    public static void sourceEnd() {
        source = -1;
        derived = false;
        coarse = false;
        cover = false;
        stitch = false;
    }

    private static int savedCoverage, savedCoverageSampler;
    private static boolean savedSamplerObjects;
    private static Point viewOffset = new Point(0, 0, 0);
    private static float partial;
    private static boolean frame, replaceNative, exteriorChosen;
    private static final Vector3f X = new Vector3f(), Y = new Vector3f(), Z = new Vector3f();

    public static boolean projectedFrame() {
        return frame;
    }

    public static boolean replacesNative() {
        return frame && replaceNative;
    }

    public static boolean active() {
        return PlanetLevels.is(Minecraft.getInstance().level);
    }

    private static ObserverFrame chartFrame;

    /** The lines a developer reads off the screen: shown only when asked for with -Dstarfold.debug=true. */
    public static final boolean DEBUG_HUD = Boolean.getBoolean("starfold.debug");

    /** The server's labelling of this client's continuous coordinates; storage==client until told otherwise. */
    public static void frame(ObserverFrame value) {
        chartFrame = value;
    }

    public static ObserverFrame frame() {
        if (chartFrame != null) return chartFrame;
        var p = Minecraft.getInstance().player;
        try {
            return p == null
                    ? ObserverFrame.identity(0, 0)
                    : ObserverFrame.identity(
                            access().storagePlanet(p.getX(), p.getZ()), access().face(p.getX()));
        } catch (IllegalArgumentException outside) {
            return ObserverFrame.identity(0, 0);
        }
    }

    /** Client position -> the root face's storage chart (the coordinates all planet math is written in). */
    public static Point storage(double x, double y, double z) {
        return frame().toStorage(new Point(x, y, z));
    }

    private static Point storage(net.minecraft.world.phys.Vec3 v) {
        return storage(v.x, v.y, v.z);
    }

    private static Point body() {
        var p = Minecraft.getInstance().player;
        return storage(p.getX(), p.getY(), p.getZ());
    }

    /** A canonical pose's feet in client coordinates, or null when that place is not drawn in this chart. */
    public static Point plane(int planet, PlanetShape.Pose pose) {
        return planePoint(planet, pose.face(), pose.point().sub(new Point(0, .9, 0)));
    }

    /** A point of a face's chart (y relative to the chart floor) in client coordinates, or null if not drawn. */
    public static Point planePoint(int planet, Face face, Point chart) {
        var f = frame();
        if (planet != f.planet()) return null;
        var at = access().origin(planet, face.ordinal()).add(chart);
        if (face.ordinal() == f.root()) return f.toClient(at);
        var root = access().middle(f.planet(), f.root());
        var image = access().image(root.x(), root.z(), at);
        return image == null ? null : f.toClient(image);
    }

    /** Quarter turns from a face's storage chart to client coordinates. */
    public static int planeTurns(Face face) {
        var f = frame();
        if (face.ordinal() == f.root()) return f.turns();
        var tile = new PlanetChart(shape(), Face.values()[f.root()]).tile(face);
        return tile == null ? f.turns() : f.turns() + tile.turn();
    }

    /** Model matrix of a mesh authored in a face's chart: storage origin relabelled into client coordinates. */
    public static Matrix4f place(Matrix4f base, int face, net.minecraft.world.phys.Vec3 camera) {
        var f = frame();
        var o = f.toClient(origin(face));
        return base.translate(
                        (float) (o.x() - camera.x),
                        (float) (o.y() - camera.y),
                        (float) (o.z() - camera.z))
                .rotateY((float) (-f.turns() * Math.PI / 2));
    }

    public static float chartYaw() {
        return (float) (-frame().turns() * Math.PI / 2);
    }

    /** Solid corner closure in client coordinates. It is not made of real blocks, so the client world cannot contain it.
     *  Decided on the plane itself: the three faces' squares and the wedge the unfolding leaves between them are one
     *  closed column, whichever face the observer's frame is rooted in. */
    static boolean masked(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (world != mc.level
                || mc.player == null
                || !active()
                || Space.holds(mc.player.getX(), mc.player.getZ())) return false;
        var config = GroundFlightServer.mask(world);
        int h = pos.getY() - GroundCoordinates.FLOOR;
        if (config.core() <= 0 || h < GroundSpace.MIN || h >= config.height()) return false;
        var f = frame();
        var storage = f.toStorage(pos);
        var o = access().origin(f.planet(), f.root());
        return Anomaly.closed(
                        PlanetShape.of(f.planet()), storage.getX() - (int) o.x(), config.core())
                && Anomaly.closed(
                        PlanetShape.of(f.planet()), storage.getZ() - (int) o.z(), config.core());
    }

    /** A frame packet alone is not an observer: teleport position, flight owner and interpolated eye must agree. */
    public static boolean ready() {
        var mc = Minecraft.getInstance();
        if (!active()
                || level != mc.level
                || model == null
                || mc.player == null
                || chartFrame == null) return false;
        var f = chartFrame;
        if (planet() != f.planet() || f.planet() >= model.count()) return false;
        var b = body();
        if (!inFrame(b, f) || !inFrame(storage(mc.player.getEyePosition(partial)), f)) return false;
        return PlanetAir.canSample(shape(), eye());
    }

    private static boolean inFrame(Point p, ObserverFrame f) {
        var access = access();
        return p.finite()
                && access.inChart(net.minecraft.core.BlockPos.containing(p.x(), p.y(), p.z()))
                && access.storagePlanet(p.x(), p.z()) == f.planet()
                && access.face(p.x()) == f.root()
                && shape().topology()
                        .inHalo(p.x() - origin(f.root()).x(), p.z() - origin(f.root()).z());
    }

    public static GroundEphemeris.Model model() {
        return model;
    }

    public static GroundEphemeris.Model ephemeris() {
        return level == Minecraft.getInstance().level ? model : null;
    }

    public static boolean solar() {
        return ready();
    }

    public static double simulationTime() {
        return time();
    }

    public static PlanetAccess access() {
        return PlanetAccess.of(Minecraft.getInstance().level);
    }

    public static int planet() {
        return GroundFlightClient.ready() ? GroundFlightClient.planet() : frame().planet();
    }

    /** Shape of the planet being observed. */
    public static PlanetShape shape() {
        return PlanetShape.of(planet());
    }

    public static int storageOffset() {
        return PlanetStorage.offset(planet());
    }

    public static int storageOffsetZ() {
        return PlanetStorage.offsetZ(planet());
    }

    public static net.minecraft.core.BlockPos local(net.minecraft.core.BlockPos p) {
        return p.offset(-storageOffset(), 0, -storageOffsetZ());
    }

    public static net.minecraft.core.BlockPos storage(net.minecraft.core.BlockPos p) {
        return p.offset(storageOffset(), 0, storageOffsetZ());
    }

    public static Point origin(int face) {
        return access().origin(planet(), face);
    }

    public static GroundEphemeris.Frame planetFrame(int id) {
        return model.at(id, time());
    }

    public static double sunElevation() {
        return elevation();
    }

    /** Render-frame adapter; ground inventory/physics still belong to vanilla. */
    public static GroundMotion.State state() {
        var p = Minecraft.getInstance().player;
        var q =
                GroundFlightClient.controlled()
                        ? GroundFlightClient.chartAim()
                        : GroundCamera.capture(frame().yawToStorage(p.getYRot()), p.getXRot());
        return new GroundMotion.State(
                pose(), 0, p.onGround(), p.getAbilities().flying, false, false, q);
    }

    public static void lensUniforms(ShaderInstance shader) {
        var lens =
                GroundSpaceOptics.lens(
                        com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), eye().y());
        shader.getUniform("SpaceLens")
                .set((float) lens.weight(), (float) lens.x(), (float) lens.y());
    }

    public static void metadata(GroundEphemeris.Model value, long epoch) {
        level = Minecraft.getInstance().level;
        model = value;
        tick = epoch;
        received = level.getGameTime();
    }

    public static double skyTime(float delta) {
        return level == Minecraft.getInstance().level && model != null
                ? tick + Math.max(0, level.getGameTime() - received) + delta
                : 0;
    }

    public static double time() {
        return GroundFlightClient.controlled()
                ? GroundFlightClient.time()
                : tick + Math.max(0, level.getGameTime() - received) + partial;
    }

    public static int face() {
        return GroundFlightClient.controlled()
                ? GroundFlightClient.pose().face().ordinal()
                : access().face(body().x());
    }

    public static Point origin() {
        return origin(face());
    }

    public static Point eye() {
        if (GroundFlightClient.controlled()) return GroundFlightClient.eye();
        var v = Minecraft.getInstance().player.getEyePosition(partial);
        return access().chart(storage(v));
    }

    public static GroundSpace.Pose pose() {
        if (GroundFlightClient.controlled()) return GroundFlightClient.pose().renderPose();
        var p = Minecraft.getInstance().player.getPosition(partial);
        return access().body(storage(p)).renderPose();
    }

    public static GroundEphemeris.Frame home() {
        return model.at(planet(), time());
    }

    public static Point universeEye() {
        var p = eye();
        var n = shape().direction(Face.values()[face()], p.x(), p.z());
        return home().world(n.scale(shape().radius() + p.y()));
    }

    /** The suns drawn: the nearest SUNS of them. Beyond that a sun is a point among the stars, and a universe has one
     *  for most of the cells a traveller has passed. Chosen again every second. */
    public static final int SUNS = 24;

    private static java.util.List<GroundEphemeris.Star> suns = java.util.List.of();
    private static long sunsChosen = Long.MIN_VALUE;
    private static GroundEphemeris.Model sunsOf;

    public static java.util.List<GroundEphemeris.Star> suns() {
        long now = level == null ? 0 : level.getGameTime();
        if (sunsOf == model && now - sunsChosen < 20) return suns;
        var all = new java.util.ArrayList<>(model.everyStar());
        if (all.size() > SUNS) {
            var eye = universeEye();
            all.sort(java.util.Comparator.comparingDouble(s -> s.position().sub(eye).length()));
            all.subList(SUNS, all.size()).clear();
        }
        suns = java.util.List.copyOf(all);
        sunsOf = model;
        sunsChosen = now;
        return suns;
    }

    /** The sun that lights the observer: the nearest. */
    public static GroundEphemeris.Star star() {
        return model.star(universeEye());
    }

    /** How much of the sun reaches the observer past the planet they are reckoned from: a vessel above the night side is still in sunlight. */
    public static double sunlight() {
        return GroundDaylight.sunlight(
                        universeEye(),
                        model.at(planet(), time()).center(),
                        star().position(),
                        shape().radius())
                * GroundDaylight.parentShade(model, planet(), universeEye(), time());
    }

    public static double daylight() {
        return GroundSolar.daylight(elevation())
                * GroundDaylight.parentShade(model, planet(), universeEye(), time());
    }

    public static double elevation() {
        var eye = universeEye();
        var up = PlanetSpace.unit(eye.sub(model.at(planet(), time()).center()));
        var d = star().position().sub(eye);
        return up.dot(d.length() < 1e-9 ? GroundEphemeris.Y : d.scale(1 / d.length()));
    }

    public static double darkness() {
        return GroundOrbit.weight(eye().y());
    }

    /** Distances over which real blocks dissolve into the far terrain: {horizontal start, end, vertical start, end}.
     *  Horizontally they end inside the loaded square; vertically before vanilla's terrain fog would paint them in sky colour. */
    public static float[] fade(int far) {
        float edge = far - 20, fog = far - Math.clamp(far / 10f, 4f, 64f);
        return new float[] {Math.max(edge / 2, edge - 40), edge, Math.max(fog / 2, fog - 24), fog};
    }

    public static int view() {
        return Math.clamp(
                Minecraft.getInstance().options.getEffectiveRenderDistance() * 16, 32, 1024);
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        initializeNavigation(modBus);
        registerChartFrame();
        registerDebugText();
        initializeGroundView(modBus);
        registerSky();
        registerClientTick();
        GroundCelestials.initialize(modBus);
        registerDimensionEffects(modBus);
        registerHud(modBus);
    }

    private static void initializeNavigation(net.neoforged.bus.api.IEventBus modBus) {
        GroundFlightClient.initialize(modBus);
        SpaceClient.initialize();
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            HelmInputClient.initialize(modBus);
            SpaceAttitudeClient.initialize(modBus);
        }
        SpaceNavigationClient.initialize(modBus);
        WaypointClient.initialize(modBus);
        GroundSeams.clientMask(GroundClient::masked);
        GroundSeams.clientPlane(GroundClient::plane);
    }

    private static void registerChartFrame() {
        StarfoldNetwork.clientReceiver(
                GroundFlightPackets.Frame.TYPE, (packet, context) -> chartFrame = packet.frame());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
                    chartFrame = null;
                    PlanetSystem.identities(java.util.List.of());
                });
    }

    private static void registerDebugText() {
        // F3 names what each planet is drawn with: a planet seen dark or missing is told apart as
        // light or as mesh.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText event) -> {
                    if (ready() && GroundFlightClient.ready()) {
                        event.getLeft().add("");
                        event.getLeft().add("[Starfold] " + GroundFlightClient.probeReading());
                        event.getLeft()
                                .add(
                                        "[Starfold] "
                                                + PlanetRemoteRenderer.probeReading()
                                                + String.format(
                                                        java.util.Locale.ROOT,
                                                        " sunlight %.2f",
                                                        sunlight()));
                    }
                });
    }

    private static void initializeGroundView(net.neoforged.bus.api.IEventBus modBus) {
        GroundViewClient.initialize();
        GroundRenderer.initialize(modBus);
        StarfoldNetwork.clientReceiver(
                GroundPackets.Shapes.TYPE, (packet, context) -> packet.assign());
    }

    private static void registerSky() {
        StarfoldNetwork.clientReceiver(
                GroundPackets.Sky.TYPE,
                (packet, context) -> {
                    if (!active()) return;
                    // The time comes every second; the model with it only when it is new. Until a
                    // model has come there is nothing to time.
                    if (packet.model() != null) {
                        level = net.minecraft.client.Minecraft.getInstance().level;
                        model = packet.model();
                    }
                    if (model == null
                            || level != net.minecraft.client.Minecraft.getInstance().level) return;
                    tick = packet.tick();
                    received = level.getGameTime();
                });
    }

    private static void registerClientTick() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (!active()) {
                        level = null;
                        model = null;
                        exteriorChosen = false;
                        heldF8 = false;
                        repair = true;
                        air = GroundAir.DEFAULT;
                        return;
                    }
                    if (level != mc.level) {
                        level = mc.level;
                        model = null;
                    }
                    boolean f8 =
                            mc.screen == null
                                    && mc.isWindowActive()
                                    && org.lwjgl.glfw.GLFW.glfwGetKey(
                                                    mc.getWindow().getWindow(),
                                                    org.lwjgl.glfw.GLFW.GLFW_KEY_F8)
                                            == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                    if (f8 && !heldF8) repair = !repair;
                    heldF8 = f8;
                });
    }

    private static void registerDimensionEffects(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent event) ->
                        event.register(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "starfold:native_ground"),
                                new net.minecraft.client.renderer.DimensionSpecialEffects
                                        .OverworldEffects() {
                                    @Override
                                    public SkyType skyType() {
                                        return SkyType.NONE;
                                    }

                                    @Override
                                    public boolean renderClouds(
                                            net.minecraft.client.multiplayer.ClientLevel level,
                                            int ticks,
                                            float partial,
                                            com.mojang.blaze3d.vertex.PoseStack stack,
                                            double x,
                                            double y,
                                            double z,
                                            org.joml.Matrix4f modelView,
                                            org.joml.Matrix4f projection) {
                                        return PlanetLevels.is(level);
                                    }

                                    @Override
                                    public boolean renderSnowAndRain(
                                            net.minecraft.client.multiplayer.ClientLevel level,
                                            int ticks,
                                            float partial,
                                            net.minecraft.client.renderer.LightTexture light,
                                            double x,
                                            double y,
                                            double z) {
                                        return PlanetLevels.is(level);
                                    }

                                    @Override
                                    public boolean renderSky(
                                            net.minecraft.client.multiplayer.ClientLevel level,
                                            int ticks,
                                            float partial,
                                            org.joml.Matrix4f modelView,
                                            net.minecraft.client.Camera camera,
                                            org.joml.Matrix4f projection,
                                            boolean foggy,
                                            Runnable fog) {
                                        return SpaceClient.present();
                                    }

                                    @Override
                                    public boolean tickRain(
                                            net.minecraft.client.multiplayer.ClientLevel level,
                                            int ticks,
                                            net.minecraft.client.Camera camera) {
                                        return false;
                                    }
                                }));
    }

    private static void registerHud(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) ->
                        event.registerAboveAll(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "starfold:native_ground_client_hud"),
                                (g, d) -> {
                                    com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
                                    try {
                                        if (DEBUG_HUD && ready()) {
                                            var mc = Minecraft.getInstance();
                                            g.drawString(
                                                    mc.font,
                                                    "Native chunk projection | face "
                                                            + face()
                                                            + " | native models/collision/interaction",
                                                    8,
                                                    8,
                                                    0xffffff,
                                                    true);
                                            g.drawString(
                                                    mc.font,
                                                    "Six-face adjacency / shared exterior / flight integration",
                                                    8,
                                                    21,
                                                    0xffcc66,
                                                    true);
                                            g.drawString(
                                                    mc.font,
                                                    GroundRenderer.performance(),
                                                    8,
                                                    101,
                                                    0xaaddff,
                                                    true);
                                        }
                                    } finally {
                                        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
                                    }
                                }));
    }

    public static void begin(Camera camera, float delta, Matrix4f view) {
        frame = false;
        partial = delta;
        if (SpaceClient.present()) return;
        if (!ready()
                || !RendererGuard.pipelineSupported()
                || camera.getEntity() != Minecraft.getInstance().player) return;
        // Move only the view, not the chart observer used for topology and coverage.
        var p = Minecraft.getInstance().player.getEyePosition(delta);
        var d = camera.getPosition().subtract(p);
        viewOffset =
                GroundFlightClient.controlled()
                        ? new Point(0, 0, 0)
                        : frame().vectorToStorage(new Point(d.x, d.y, d.z));
        // Chart axes as they lie in client coordinates (the frame may be turned), then into view
        // space.
        var u = frame().vectorToClient(new Point(1, 0, 0));
        var w = frame().vectorToClient(new Point(0, 0, 1));
        X.set((float) u.x(), 0, (float) u.z()).mulDirection(view);
        Y.set(0, 1, 0).mulDirection(view);
        Z.set((float) w.x(), 0, (float) w.z()).mulDirection(view);
        frame = true;
        saveCoverageBinding();
        replaceNative = GroundSurfaceMesh.replaceNative(eye().y(), GroundRenderer.covered());
        exteriorChosen = GroundExteriorMesh.select(exteriorChosen, replaceNative, eye().y());
        replaceNative = exteriorChosen;
    }

    public static void end() {
        if (frame) restoreCoverageBinding();
        frame = false;
        replaceNative = false;
        sourceEnd();
    }

    private static void saveCoverageBinding() {
        int active = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE3);
        savedCoverage =
                org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        savedSamplerObjects =
                org.lwjgl.opengl.GL.getCapabilities().OpenGL33
                        || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sampler_objects;
        savedCoverageSampler =
                savedSamplerObjects
                        ? org.lwjgl.opengl.GL30.glGetIntegeri(
                                org.lwjgl.opengl.ARBSamplerObjects.GL_SAMPLER_BINDING, 3)
                        : 0;
        org.lwjgl.opengl.GL13.glActiveTexture(active);
    }

    private static void restoreCoverageBinding() {
        int active = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE3);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, savedCoverage);
        if (savedSamplerObjects)
            org.lwjgl.opengl.ARBSamplerObjects.glBindSampler(3, savedCoverageSampler);
        org.lwjgl.opengl.GL13.glActiveTexture(active);
    }

    public static void apply(ShaderInstance shader) {
        if (!GroundShaderSource.supported(shader.getVertexProgram().getName())) return;
        boolean projected = frame && ready();
        ShaderUniforms.set(shader, "CPNativeActive", projected ? 1 : 0);
        if (!projected) return;
        var e = eye();
        var gap = gap();
        ShaderUniforms.set(
                shader, "CPNativeGapCorner", (float) gap.corner.x(), (float) gap.corner.z());
        ShaderUniforms.set(shader, "CPNativeGapSigns", (float) gap.sx, (float) gap.sz);
        ShaderUniforms.set(
                shader,
                "CPNativeGapLimits",
                (float) gap.strength,
                (float) gap.outer,
                (float) GroundTransition.clip(e.y(), gap.clip, view()));
        var observer = Face.values()[face()];
        var owner = Face.values()[source < 0 ? face() : source];
        var tile = gap.chart.tile(owner);
        set(shader, "CPNativeSourcePlot", origin(owner.ordinal()).sub(origin()));
        set(shader, "CPNativeSun", faceLocal(observer, home().local(star().position())));
        set(shader, "CPNativeSourceNormal", faceLocal(observer, owner.normal));
        set(shader, "CPNativeSourceU", faceLocal(observer, owner.u));
        set(shader, "CPNativeSourceV", faceLocal(observer, owner.v));
        var flat = tile == null ? new Point(0, 0, 0) : tile.point(new Point(0, 0, 0));
        set(shader, "CPNativeFlatOrigin", flat);
        set(
                shader,
                "CPNativeFlatU",
                tile == null ? new Point(1, 0, 0) : tile.point(new Point(1, 0, 0)).sub(flat));
        set(
                shader,
                "CPNativeFlatV",
                tile == null ? new Point(0, 0, 1) : tile.point(new Point(0, 0, 1)).sub(flat));
        String name = shader.getVertexProgram().getName().replace("minecraft:", "");
        boolean terrain =
                name.equals("rendertype_solid")
                        || name.equals("rendertype_cutout")
                        || name.equals("rendertype_cutout_mipped")
                        || name.equals("rendertype_translucent")
                        || name.equals("rendertype_translucent_moving_block")
                        || name.equals("rendertype_tripwire");
        ShaderUniforms.set(shader, "CPNativeTerrain", terrain ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeDerived", derived ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeCoarse", coarse ? 1 : 0);
        var mask = GroundFlightServer.mask(Minecraft.getInstance().level);
        ShaderUniforms.set(
                shader,
                "CPNativeMask",
                mask.core(),
                mask.height(),
                GroundRenderer.replacesNative() ? 1 : 0);
        int corners = GroundRenderer.realCorners(owner.ordinal());
        ShaderUniforms.set(
                shader,
                "CPNativeClosure",
                (corners & 1) != 0 ? 1 : 0,
                (corners & 2) != 0 ? 1 : 0,
                (corners & 4) != 0 ? 1 : 0,
                (corners & 8) != 0 ? 1 : 0);
        ShaderUniforms.set(
                shader, "CPNativeLowFrequency", (float) GroundPipeline.lowFrequency(e.y()));
        ShaderUniforms.set(shader, "CPNativeCover", cover ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeFace", owner.ordinal());
        // Below the range extension the view edge is still vanilla fog, so only the vertical
        // handover applies there.
        var fade = fade(view());
        ShaderUniforms.set(shader, "CPNativeFade", fade[0], fade[1], fade[2], fade[3]);
        ShaderUniforms.set(shader, "CPNativeFadeMix", (float) GroundTransition.fade(e.y()));
        ShaderUniforms.set(
                shader, "CPNativeVolume", GroundRenderer.nativeVolume(owner.ordinal()) ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeSelection", GroundRenderer.selectionReady() ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeStitch", stitch ? 1 : 0);
        if (cover || stitch || terrain && GroundRenderer.selectionReady()) {
            int active =
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE3);
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, GroundRenderer.coverageTexture());
            if (org.lwjgl.opengl.GL.getCapabilities().OpenGL33
                    || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sampler_objects)
                org.lwjgl.opengl.ARBSamplerObjects.glBindSampler(3, 0);
            org.lwjgl.opengl.GL13.glActiveTexture(active);
            ShaderUniforms.setInt(shader, "CPNativeCoverage", 3);
        }
        applyViewUniforms(shader, e, gap);
    }

    private static void applyViewUniforms(ShaderInstance shader, Point e, PlanetGap gap) {
        set(shader, "CPNativeEye", e);
        set(shader, "CPNativeViewOffset", viewOffset);
        set(shader, "CPNativeWorldX", X);
        set(shader, "CPNativeWorldY", Y);
        set(shader, "CPNativeWorldZ", Z);
        ShaderUniforms.set(shader, "CPNativeSize", shape().size());
        ShaderUniforms.set(
                shader,
                "CPNativeAirLimits",
                (float) gap.air.weight,
                (float) shape().radius(),
                (float) gap.air.offset.x(),
                (float) gap.air.offset.z());
        set(shader, "CPNativeAirNormal", gap.air.normal);
        set(shader, "CPNativeAirU", gap.air.basisU);
        set(shader, "CPNativeAirV", gap.air.basisV);
        var orbit = orbit();
        var face = Face.values()[face()];
        set(shader, "CPNativeOrbitNormal", faceLocal(face, orbit.normal));
        set(shader, "CPNativeOrbitU", faceLocal(face, orbit.basisU));
        set(shader, "CPNativeOrbitV", faceLocal(face, orbit.basisV));
        set(shader, "CPNativeOrbitOffset", orbit.offset);
        ShaderUniforms.set(
                shader,
                "CPNativeOrbitLimits",
                (float) orbit.weight,
                (float) shape().radius(),
                (float) GroundTransition.clip(e.y(), orbit.clip, view()),
                (float) orbit.minimumFacing);
        double range =
                e.y() > GroundOrbit.START
                        ? GroundTransition.clip(e.y(), orbit.clip, view())
                        : GroundTransition.clip(e.y(), gap.clip, view());
        ShaderUniforms.set(shader, "CPNativeRange", (float) (range * .75), (float) range);
        var lens =
                GroundSpaceOptics.lens(
                        com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), e.y());
        ShaderUniforms.set(
                shader, "CPNativeLens", (float) lens.weight(), (float) lens.x(), (float) lens.y());
    }

    private static Point faceLocal(Face face, Point p) {
        return new Point(p.dot(face.u), p.dot(face.normal), p.dot(face.v));
    }

    private static void set(ShaderInstance s, String n, Point p) {
        ShaderUniforms.set(s, n, (float) p.x(), (float) p.y(), (float) p.z());
    }

    private static void set(ShaderInstance s, String n, Vector3f p) {
        ShaderUniforms.set(s, n, p.x, p.y, p.z);
    }

    private GroundClient() {}
}
