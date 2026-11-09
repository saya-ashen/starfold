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
    static final GroundRenderFrame renderFrame = new GroundRenderFrame();
    private static ClientLevel level;
    private static final GroundObserverState observer = new GroundObserverState();

    public static void air(GroundAir.Config config) {
        observer.air = config;
    }

    public static PlanetGap gap() {
        if (renderFrame.active) return renderFrame.frameGap;
        return new PlanetGap(shape(), Face.VALUES[face()], eye(), view(), observer.repair, observer.air, true);
    }

    /** The curved view of the observed planet from where the observer is. */
    public static PlanetOrbit orbit() {
        if (renderFrame.active) return renderFrame.frameOrbit;
        return new PlanetOrbit(shape(), pose(), eye(), view());
    }

    static void patchFade(float fade) {
        renderFrame.patchFade = fade;
    }

    static void lod(long key, float fade) {
        renderFrame.lodKey = key;
        renderFrame.lodFade = fade;
    }

    public static void source(int face, boolean mesh) {
        source(face, mesh, false);
    }

    public static void source(int face, boolean mesh, boolean sky) {
        renderFrame.source = face;
        renderFrame.derived = mesh;
        renderFrame.coarse = sky;
        renderFrame.patchFade = 1;
        renderFrame.cover = false;
        renderFrame.stitch = false;
        renderFrame.lodKey = -1;
        renderFrame.lodFade = 1;
    }

    public static void cover(boolean value) {
        renderFrame.cover = value;
    }

    public static void stitch(boolean value) {
        renderFrame.stitch = value;
    }

    public static void sourceEnd() {
        renderFrame.source = -1;
        renderFrame.patchFade = 1;
        renderFrame.derived = false;
        renderFrame.coarse = false;
        renderFrame.cover = false;
        renderFrame.stitch = false;
        renderFrame.lodKey = -1;
        renderFrame.lodFade = 1;
    }

    public static boolean projectedFrame() {
        return renderFrame.active;
    }

    public static boolean active() {
        return PlanetLevels.is(Minecraft.getInstance().level);
    }

    /**
     * The lines a developer reads off the screen: shown only when asked for with
     * -Dstarfold.debug=true.
     */
    public static final boolean DEBUG_HUD = Boolean.getBoolean("starfold.debug");

    /**
     * The server's labelling of this client's continuous coordinates; storage==client until told
     * otherwise.
     */
    public static void frame(ObserverFrame value) {
        observer.chartFrame = value;
    }

    public static ObserverFrame frame() {
        if (observer.chartFrame != null) return observer.chartFrame;
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

    /**
     * Client position -> the root face's storage chart (the coordinates all planet math is written
     * in).
     */
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

    /**
     * A canonical pose's feet in client coordinates, or null when that place is not drawn in this
     * chart.
     */
    public static Point plane(int planet, PlanetShape.Pose pose) {
        return planePoint(planet, pose.face(), pose.point().sub(new Point(0, .9, 0)));
    }

    /**
     * A point of a face's chart (y relative to the chart floor) in client coordinates, or null if
     * not drawn.
     */
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
        var tile = new PlanetChart(shape(), Face.VALUES[f.root()]).tile(face);
        return tile == null ? f.turns() : f.turns() + tile.turn();
    }

    /**
     * Model matrix of a mesh authored in a face's chart: storage origin relabelled into client
     * coordinates.
     */
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

    /**
     * Solid corner closure in client coordinates. It is not made of real blocks, so the client
     * world cannot contain it. Decided on the plane itself: the three faces' squares and the wedge
     * the unfolding leaves between them are one closed column, whichever face the observer's frame
     * is rooted in.
     */
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

    /**
     * A frame packet alone is not an observer: teleport position, flight owner and interpolated eye
     * must agree.
     */
    public static boolean ready() {
        if (renderFrame.active) return true;
        var mc = Minecraft.getInstance();
        if (!active()
                || level != mc.level
                || observer.model == null
                || mc.player == null
                || observer.chartFrame == null) return false;
        if (planet() != observer.chartFrame.planet()
                || observer.chartFrame.planet() >= observer.model.count()) return false;
        if (!observer.accepts(storage(mc.player.getPosition(renderFrame.partial)),
                storage(mc.player.getEyePosition(renderFrame.partial)), GroundFlightClient.controlled())) return false;
        return PlanetAir.canSample(shape(), eye());
    }

    public static GroundEphemeris.Model model() {
        return observer.model;
    }

    public static GroundEphemeris.Model ephemeris() {
        return level == Minecraft.getInstance().level ? observer.model : null;
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
        return observer.model.at(id, time());
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
        observer.model = value;
        observer.tick = epoch;
        observer.received = level.getGameTime();
    }

    public static double skyTime(float delta) {
        return level == Minecraft.getInstance().level && observer.model != null
                ? observer.tick + Math.max(0, level.getGameTime() - observer.received) + delta
                : 0;
    }

    public static double time() {
        return GroundFlightClient.controlled()
                ? GroundFlightClient.time()
                : observer.tick + Math.max(0, level.getGameTime() - observer.received) + renderFrame.partial;
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
        if (renderFrame.active) return renderFrame.frameEye;
        if (GroundFlightClient.controlled()) return GroundFlightClient.eye();
        var v = Minecraft.getInstance().player.getEyePosition(renderFrame.partial);
        return access().chart(storage(v));
    }

    public static GroundSpace.Pose pose() {
        if (renderFrame.active) return renderFrame.framePose;
        if (GroundFlightClient.controlled()) return GroundFlightClient.pose().renderPose();
        var p = Minecraft.getInstance().player.getPosition(renderFrame.partial);
        return access().body(storage(p)).renderPose();
    }

    public static GroundEphemeris.Frame home() {
        if (renderFrame.active) return renderFrame.frameHome;
        return observer.model.at(planet(), time());
    }

    public static Point universeEye() {
        if (renderFrame.active) return renderFrame.frameUniverseEye;
        var p = eye();
        var n = shape().direction(Face.VALUES[face()], p.x(), p.z());
        return home().world(n.scale(shape().radius() + p.y()));
    }

    /**
     * The suns drawn: the nearest SUNS of them. Beyond that a sun is a point among the stars, and a
     * universe has one for most of the cells a traveller has passed. Chosen again every second.
     */
    public static final int SUNS = 24;

    public static java.util.List<GroundEphemeris.Star> suns() {
        long now = level == null ? 0 : level.getGameTime();
        if (observer.sunsOf == observer.model && now - observer.sunsChosen < 20) return observer.suns;
        var all = new java.util.ArrayList<>(observer.model.visibleStars());
        if (all.size() > SUNS) {
            var eye = universeEye();
            all.sort(java.util.Comparator.comparingDouble(s -> s.position().sub(eye).length()));
            all.subList(SUNS, all.size()).clear();
        }
        observer.suns = java.util.List.copyOf(all);
        observer.sunsOf = observer.model;
        observer.sunsChosen = now;
        return observer.suns;
    }

    /** The sun that lights the observer: the nearest. */
    public static GroundEphemeris.Star star() {
        if (renderFrame.active) return renderFrame.frameStar;
        return observer.model.star(universeEye());
    }

    /**
     * How much of the sun reaches the observer past the planet they are reckoned from: a vessel
     * above the night side is still in sunlight.
     */
    public static double sunlight() {
        return GroundDaylight.sunlight(
                        universeEye(),
                        observer.model.at(planet(), time()).center(),
                        star().position(),
                        shape().radius())
                * GroundDaylight.parentShade(observer.model, planet(), universeEye(), time())
                * dev.starfold.chart.GasOcclusion.sunlight(observer.model, universeEye(), time());
    }

    public static double daylight() {
        return GroundSolar.daylight(elevation())
                * GroundDaylight.parentShade(observer.model, planet(), universeEye(), time())
                * dev.starfold.chart.GasOcclusion.sunlight(observer.model, universeEye(), time());
    }

    public static double elevation() {
        var eye = universeEye();
        var up = PlanetSpace.unit(eye.sub(observer.model.at(planet(), time()).center()));
        var d = star().position().sub(eye);
        return up.dot(d.length() < 1e-9 ? GroundEphemeris.Y : d.scale(1 / d.length()));
    }

    public static double darkness() {
        return GroundOrbit.weight(eye().y());
    }

    /**
     * Distances over which real blocks dissolve into the far terrain: {horizontal start, end,
     * vertical start, end}. Horizontally they end inside the loaded square; vertically before
     * vanilla's terrain fog would paint them in sky colour.
     */
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
        dev.starfold.ship.ShipMapClient.initialize();
        GroundSeams.clientMask(GroundClient::masked);
        GroundSeams.clientPlane(GroundClient::plane);
    }

    private static void registerChartFrame() {
        StarfoldNetwork.clientReceiver(
                GroundFlightPackets.Frame.TYPE, (packet, context) -> observer.chartFrame = packet.frame());

    }

    private static void registerDebugText() {
        // F3 names what each planet is drawn with: a planet seen dark or missing is told apart as
        // light or as mesh.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText event) -> {
                    if (ready() && GroundFlightClient.ready()) {
                        event.getLeft().add("");
                        event.getLeft().add("[Starfold] " + GroundRenderer.performance());
                        event.getLeft().add("[Starfold] " + GroundFlightClient.probeReading());
                        event.getLeft()
                                .add(
                                        "[Starfold] "
                                                + PlanetRemoteRenderer.probeReading()
                                                + String.format(
                                                        java.util.Locale.ROOT,
                                                        " sunlight %.2f",
                                                        sunlight()));
                        if(GroundViewClient.terrain()!=null)event.getLeft().add("[Starfold] " + dev.starfold.PlanetPlayers.climate(
                                dev.starfold.api.PlanetSeasons.reading(observer.model, planet(), universeEye(), time(), GroundViewClient.terrain().terrainPlan())).getString());
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
                        observer.model = packet.model();
                    }
                    if (observer.model == null
                            || level != net.minecraft.client.Minecraft.getInstance().level) return;
                    observer.tick = packet.tick();
                    observer.received = level.getGameTime();
                });
    }

    private static void registerClientTick() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (!active()) {
                        level = null;
                        observer.model = null;
                        observer.heldF8 = false;
                        observer.repair = true;
                        observer.air = GroundAir.DEFAULT;
                        return;
                    }
                    if (level != mc.level) {
                        level = mc.level;
                        observer.model = null;
                    }
                    boolean f8 =
                            mc.screen == null
                                    && mc.isWindowActive()
                                    && org.lwjgl.glfw.GLFW.glfwGetKey(
                                                    mc.getWindow().getWindow(),
                                                    org.lwjgl.glfw.GLFW.GLFW_KEY_F8)
                                            == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                    if (f8 && !observer.heldF8) observer.repair = !observer.repair;
                    observer.heldF8 = f8;
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
                                        if (DEBUG_HUD && ready()
                                                && !Minecraft.getInstance().getDebugOverlay().showDebugScreen()) {
                                            var mc = Minecraft.getInstance();
                                            g.drawString(
                                                    mc.font,
                                                    "Native chunk projection | face "
                                                            + face()
                                                            + " | native"
                                                            + " models/collision/interaction",
                                                    8,
                                                    8,
                                                    0xffffff,
                                                    true);
                                            g.drawString(
                                                    mc.font,
                                                    "Six-face adjacency / shared exterior / flight"
                                                        + " integration",
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
        GroundAtmosphere.beginFrame();
        renderFrame.active = false;
        renderFrame.partial = delta;
        if (SpaceClient.present()) return;
        if (!ready()
                || !RendererGuard.pipelineSupported()
                || camera.getEntity() != Minecraft.getInstance().player) return;
        // Move only the view, not the chart observer used for topology and coverage.
        var p = Minecraft.getInstance().player.getEyePosition(delta);
        var d = camera.getPosition().subtract(p);
        renderFrame.viewOffset =
                GroundFlightClient.controlled()
                        ? new Point(0, 0, 0)
                        : frame().vectorToStorage(new Point(d.x, d.y, d.z));
        // Chart axes as they lie in client coordinates (the frame may be turned), then into view
        // space.
        var u = frame().vectorToClient(new Point(1, 0, 0));
        var w = frame().vectorToClient(new Point(0, 0, 1));
        renderFrame.X.set((float) u.x(), 0, (float) u.z()).mulDirection(view);
        renderFrame.Y.set(0, 1, 0).mulDirection(view);
        renderFrame.Z.set((float) w.x(), 0, (float) w.z()).mulDirection(view);
        renderFrame.framePose = pose();
        renderFrame.frameEye = eye();
        renderFrame.frameHome = home();
        var shape = shape();
        var face = Face.VALUES[face()];
        renderFrame.frameUniverseEye = renderFrame.frameHome.world(
                shape.direction(face, renderFrame.frameEye.x(), renderFrame.frameEye.z()).scale(shape.radius() + renderFrame.frameEye.y()));
        renderFrame.frameStar = observer.model.star(renderFrame.frameUniverseEye);
        renderFrame.frameOrbit = new PlanetOrbit(shape, renderFrame.framePose, renderFrame.frameEye, view());
        renderFrame.frameGap = new PlanetGap(shape, face, renderFrame.frameEye, view(), observer.repair, observer.air, true);
        renderFrame.active = true;
        saveCoverageBinding();
    }

    public static void resetWorld() {
        end();
        observer.clear();
        level = null;
        renderFrame.clear();
    }

    public static void end() {
        if (renderFrame.active) restoreCoverageBinding();
        renderFrame.active = false;
        sourceEnd();
    }

    private static void saveCoverageBinding() {
        renderFrame.coverageBinding = new CoverageBinding();
    }

    private static void restoreCoverageBinding() {
        try { renderFrame.coverageBinding.close(); }
        finally { renderFrame.coverageBinding = null; }
    }

    public static void apply(ShaderInstance shader) {
        GroundProjectionUniforms.apply(shader);
    }

    /** Horizontal reach of the drawn terrain disk; the shader clips there. */
    public static double range(Point e, PlanetOrbit orbit, PlanetGap gap) {
        return e.y() > GroundOrbit.START
                ? GroundTransition.clip(e.y(), orbit.clip, view(), shape().radius())
                : GroundTransition.clip(e.y(), gap.clip, view(), shape().radius());
    }

    private GroundClient() {}
}
