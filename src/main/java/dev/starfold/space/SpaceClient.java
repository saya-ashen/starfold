package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSpace;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HullVehicles;
import dev.starfold.ship.SableCarryClient;
import dev.starfold.ship.SpacePackets;
import dev.starfold.ship.ThrusterSoundClient;
import dev.starfold.sky.Space;
import dev.starfold.sky.WeatherClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Passive region observer. No chart projection, camera ownership or flight prediction. */
public final class SpaceClient {
    /** Told of every frame packet as it arrives; a development probe traces them. */
    public static java.util.function.Consumer<SpacePackets.Frame> frameListener = frame -> {};

    private static boolean arriving;
    private static ArrivalState.Phase phase = ArrivalState.Phase.CRUISE;
    private static float blend;
    private static int notice;

    public static ArrivalState.Phase phase() {
        return active() ? phase : ArrivalState.Phase.CRUISE;
    }

    public static float blend() {
        return active() ? blend : 0;
    }

    public static int notice() {
        return active() ? notice : 0;
    }

    private static ClientLevel level;
    private static final SpaceView.Frames frames = new SpaceView.Frames();
    private static long clientTick;
    private static ClientLevel viewLevel;
    private static SpacePackets.View view;
    private static long retainedSession;

    static void install(SpacePackets.Frame packet) {
        var mc = Minecraft.getInstance();
        if (level != mc.level) clear();
        if (!PlanetLevels.is(mc.level)) return;
        level = mc.level;
        arriving = packet.turning();
        phase = packet.phase();
        blend = packet.blend();
        notice = packet.notice();
        frameListener.accept(packet);
        frames.install(packet, eye(), renderTick(), SpaceClientHost.get().ephemeris(), time());
        if (frames.current() != null) GroundFlightClient.region();
    }

    public static void carried(SpacePackets.Frame packet) {
        frames.clear();
        install(packet);
    }

    private static Point eye() {
        var mc = Minecraft.getInstance();
        var p =
                mc.player == null
                        ? Vec3.ZERO
                        : mc.player.getEyePosition(
                                mc.getTimer().getGameTimeDeltaPartialTick(false));
        return new Point(p.x, p.y, p.z);
    }

    private static double renderTick() {
        return clientTick + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
    }

    /** Vanilla leaves ten to twenty minutes between tracks; the void gets its own track and short gaps. */
    private static final net.minecraft.sounds.Music MUSIC =
            new net.minecraft.sounds.Music(
                    net.minecraft.core.Holder.direct(
                            net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                                    net.minecraft.resources.ResourceLocation.parse(
                                            "starfold:music.space"))),
                    600,
                    2400,
                    true);

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.SelectMusicEvent event) -> {
                    if (present()) event.setMusic(MUSIC);
                });
        SableCarryClient.initialize();
        WeatherClient.initialize();
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            SpaceDustClient.initialize();
            ThrusterSoundClient.initialize();
        }
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) HelmClient.initialize();
        HullVehicles.initialize();
        StarfoldNetwork.clientReceiver(SpacePackets.Frame.TYPE, (packet, c) -> install(packet));
        StarfoldNetwork.clientReceiver(
                SpacePackets.View.TYPE,
                (packet, c) -> {
                    var mc = Minecraft.getInstance();
                    if (!PlanetLevels.is(mc.level)) return;
                    if (viewLevel != null && viewLevel != mc.level) {
                        SpaceClientHost.get().viewLost();
                        retainedSession = 0;
                    }
                    viewLevel = mc.level;
                    view = packet;
                    if (packet.session() != 0) retainedSession = packet.session();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                        clear());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) -> {
                    if (level != Minecraft.getInstance().level) clear();
                    clientTick++;
                });
    }

    private static void clear() {
        if (frames.current() != null || view != null) SpaceClientHost.get().viewLost();
        level = null;
        arriving = false;
        phase = ArrivalState.Phase.CRUISE;
        blend = 0;
        notice = 0;
        frames.clear();
        clientTick = 0;
        viewLevel = null;
        view = null;
        retainedSession = 0;
    }

    public static boolean arriving() {
        return active() && arriving;
    }

    public static boolean present() {
        var mc = Minecraft.getInstance();
        return PlanetLevels.is(mc.level)
                && mc.player != null
                && Space.holds(mc.player.getX(), mc.player.getZ());
    }

    public static Space.Frame frame() {
        var mc = Minecraft.getInstance();
        if (level != Minecraft.getInstance().level) clear();
        return PlanetLevels.is(mc.level)
                        && mc.player != null
                        && Space.holds(mc.player.getX(), mc.player.getZ())
                ? frames.sample(eye(), renderTick(), SpaceClientHost.get().ephemeris(), time())
                : null;
    }

    public static boolean active() {
        var frame = frame();
        var model = SpaceClientHost.get().ephemeris();
        return frame != null && model != null && frame.planet() < model.count();
    }

    /** Actual Level camera point, not a chart eye or a flight proxy. */
    public static Point universeEye(Vec3 levelCameraPosition) {
        if (!active()) return null;
        var frame = frame();
        return frame.universe(
                new Point(levelCameraPosition.x, levelCameraPosition.y, levelCameraPosition.z),
                SpaceClientHost.get().ephemeris(),
                time());
    }

    /** Sky time never borrows stale flight interpolation or the last chart render partial. */
    public static double time() {
        return SpaceClientHost.get()
                .skyTime(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
    }

    public static long retainedSession() {
        return viewLevel == Minecraft.getInstance().level ? retainedSession : 0;
    }

    public static long session() {
        return frame() != null && viewLevel == Minecraft.getInstance().level && view != null
                ? view.session()
                : 0;
    }

    public static GroundSpace.Config mask() {
        return session() != 0 ? view.mask() : GroundSpace.DEFAULT_MASK;
    }

    public static double sunlight() {
        if (!active()) return 1;
        return SpaceView.sunlight(
                SpaceClientHost.get().ephemeris(),
                universeEye(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()),
                time());
    }

    public static java.util.List<GroundEphemeris.Star> suns(Point eye) {
        var all = new java.util.ArrayList<>(SpaceClientHost.get().ephemeris().everyStar());
        all.sort(java.util.Comparator.comparingDouble(s -> s.position().sub(eye).length()));
        return all.subList(0, Math.min(SpaceClientHost.get().sunLimit(), all.size()));
    }

    public static void lens(net.minecraft.client.renderer.ShaderInstance shader) {
        shader.getUniform("SpaceLens").set(0f, 1f, 1f);
    }

    private SpaceClient() {}
}
