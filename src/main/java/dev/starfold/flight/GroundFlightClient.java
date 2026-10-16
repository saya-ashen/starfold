package dev.starfold.flight;

import dev.starfold.chart.GroundCamera;
import dev.starfold.chart.GroundCosmos;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGap;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.GroundView;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.OrbitAttitude;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.ground.GroundTerrain;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.probe.ProbeState;
import dev.starfold.sky.Space;

import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/** Live predictive vessel adapter. Never intercepts native placement/inventory/ground travel. */
public final class GroundFlightClient {
    private static ClientLevel level;
    private static GroundEphemeris.Model model;
    private static GroundSpace.Config mask;
    private static GroundDynamics.State state, previous;
    private static Quaterniond aim = new Quaterniond();
    private static long session, sequence, ack, lastTick;
    private static boolean controlled, waiting, heldG;
    private static int lastSync = -100;
    private static GroundFlightPackets.Seam seam;

    public static void teleported(Vec3 oldVelocity) {
        var p = Minecraft.getInstance().player;
        // A vessel is turned by the server as it is placed, not by its pilot: the arm does not sway
        // after it.
        if (p != null && controlled) {
            p.xBob = p.xBobO = p.getXRot();
            p.yBob = p.yBobO = p.getYRot();
        }
        var receipt = seam;
        seam = null;
        if (p == null || receipt == null || receipt.session() != session || !ready()) return;
        var feet = PlanetAccess.of(level).feet(state.planet(), receipt.pose());
        if (new Point(p.getX(), p.getY(), p.getZ()).sub(feet).length() > 1e-4) return;
        var v =
                ChartLabSpace.rotate(
                        new Point(oldVelocity.x, oldVelocity.y, oldVelocity.z), receipt.turns());
        p.setDeltaMovement(v.x(), v.y(), v.z());
    }

    private static CameraType oldCamera;
    private static float partial;
    private static double renderTime;
    private static PlanetShape.Pose renderPose;
    private static final LinkedHashMap<Long, GroundFlightPackets.Input> pending =
            new LinkedHashMap<>();

    public static boolean ready() {
        var mc = Minecraft.getInstance();
        return PlanetLevels.is(mc.level)
                && mc.player != null
                && !Space.holds(mc.player.getX(), mc.player.getZ())
                && mc.level == level
                && mc.player != null
                && state != null
                && model != null;
    }

    public static boolean controlled() {
        return ready() && controlled;
    }

    public static long session() {
        return ready() ? session : 0;
    }

    /** Setup metadata can precede the atomic hull carry out of region coordinates. */
    public static long retainedSession() {
        return level == Minecraft.getInstance().level ? session : 0;
    }

    public static int planet() {
        return state == null ? 0 : state.planet();
    }

    public static GroundEphemeris.Model model() {
        return model;
    }

    public static double time() {
        return controlled() ? renderTime : state.tick();
    }

    public static GroundDynamics.State state() {
        return state;
    }

    public static GroundSpace.Config mask() {
        return mask;
    }

    /** Drop prediction without applying a chart handoff or moving the player. */
    public static void region() {
        FlightClientHost.get().departing();
        reset();
    }

    private static void reset() {
        var mc = Minecraft.getInstance();
        if (oldCamera != null) mc.options.setCameraType(oldCamera);
        oldCamera = null;
        seam = null;
        level = null;
        model = null;
        mask = null;
        state = previous = null;
        session = sequence = ack = lastTick = 0;
        controlled = waiting = heldG = false;
        lastSync = -100;
        pending.clear();
        renderPose = null;
        renderTime = 0;
    }

    private static GroundDynamics.Terrain terrain() {
        var local = new GroundTerrain(level, Minecraft.getInstance().player, mask, state.planet());
        return new GroundDynamics.Terrain() {
            public boolean clear(PlanetShape.Pose p) {
                return local.clear(p);
            }

            public double clearance(PlanetShape.Pose p) {
                return local.clearance(p);
            }

            public boolean clear(int id, PlanetShape.Pose p) {
                return new GroundTerrain(level, Minecraft.getInstance().player, mask, id).clear(p);
            }

            public double clearance(int id, PlanetShape.Pose p) {
                return new GroundTerrain(level, Minecraft.getInstance().player, mask, id)
                        .clearance(p);
            }
        };
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        installClientAccess();
        registerSetup();
        registerState();
        registerClientLifecycle();
        registerHud(modBus);
    }

    private static void installClientAccess() {
        GroundFlightServer.clientControl(e -> e == Minecraft.getInstance().player && controlled());
        GroundFlightServer.clientMask(() -> ready() ? mask : GroundSpace.DEFAULT_MASK);
        StarfoldNetwork.clientReceiver(
                GroundFlightPackets.Seam.TYPE,
                (packet, c) -> {
                    if (ready() && packet.session() == session && !controlled) seam = packet;
                });
    }

    private static void registerSetup() {
        StarfoldNetwork.clientReceiver(
                GroundFlightPackets.Setup.TYPE,
                (packet, c) -> {
                    if (!PlanetLevels.is(net.minecraft.client.Minecraft.getInstance().level))
                        return;
                    if (level != net.minecraft.client.Minecraft.getInstance().level) reset();
                    level = net.minecraft.client.Minecraft.getInstance().level;
                    model = packet.model();
                    mask = packet.mask();
                    boolean handoff = controlled && !packet.controlled(),
                            underWay = controlled && packet.controlled();
                    state = previous = packet.state();
                    session = packet.session();
                    sequence = ack = 0;
                    lastTick = state.tick();
                    controlled = packet.controlled();
                    waiting = false;
                    pending.clear();
                    // A vessel under way keeps the aim its pilot gave it: a new planet to reckon
                    // from is no reason to turn it.
                    if (!handoff && !underWay)
                        aim = GroundDynamics.orientation(model, state, state.tick());
                    renderTime = state.tick();
                    renderPose = null;
                    FlightClientHost.get().sessionBound(level, session);
                    FlightClientHost.get().atmosphere(packet.air());
                    FlightClientHost.get().metadata(model, state.tick());
                    if (controlled) apply();
                    else if (handoff) handoff();
                });
    }

    private static void registerState() {
        StarfoldNetwork.clientReceiver(
                GroundFlightPackets.State.TYPE,
                (packet, c) -> {
                    if (!ready()
                            || packet.session() != session
                            || packet.ack() < ack
                            || packet.state().tick() < lastTick) return;
                    if (packet.ack() > sequence) {
                        sync();
                        return;
                    }
                    ack = packet.ack();
                    lastTick = packet.state().tick();
                    pending.entrySet().removeIf(e -> e.getKey() <= ack);
                    var corrected = packet.state();
                    boolean nextControlled = packet.controlled();
                    try {
                        if (nextControlled)
                            for (var input : pending.values()) {
                                if (!corrected.space()
                                        && corrected.local().point().y() <= GroundDynamics.HANDOFF)
                                    break;
                                var predicted =
                                        GroundDynamics.step(
                                                model,
                                                terrain(),
                                                corrected,
                                                input.motion(),
                                                input.toggle());
                                if (predicted.planet() != state.planet()) {
                                    waiting = true;
                                    break;
                                }
                                corrected = predicted;
                            }
                    } catch (IllegalArgumentException | IllegalStateException failure) {
                        sync();
                        return;
                    }
                    boolean handoff = controlled && !nextControlled;
                    double error =
                            GroundDynamics.position(model, corrected, corrected.tick())
                                    .sub(GroundDynamics.position(model, state, state.tick()))
                                    .length();
                    if (error > 2
                            || corrected.space() != state.space()
                            || Math.abs(corrected.tick() - state.tick()) > 1) previous = corrected;
                    state = corrected;
                    controlled = nextControlled;
                    renderPose = null;
                    if (!controlled) {
                        if (handoff) handoff();
                        else aim = GroundDynamics.orientation(model, state, state.tick());
                    } else apply();
                });
    }

    private static void registerClientLifecycle() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                        reset());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (level != mc.level) {
                        reset();
                        return;
                    }
                    if (!ready() || mc.isPaused()) return;
                    boolean play =
                            mc.screen == null
                                    && (mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed()
                                            || ProbeState.active());
                    boolean
                            g =
                                    play
                                            && GLFW.glfwGetKey(
                                                            mc.getWindow().getWindow(),
                                                            GLFW.GLFW_KEY_G)
                                                    == GLFW.GLFW_PRESS,
                            permit = mc.player.getAbilities().mayfly,
                            toggle = g && !heldG && permit;
                    heldG = g;
                    if (!permit) play = false;
                    if (!controlled) {
                        if (toggle) {
                            mc.player.getAbilities().flying = !mc.player.getAbilities().flying;
                            mc.player.onUpdateAbilities();
                        }
                        if (oldCamera != null) {
                            mc.options.setCameraType(oldCamera);
                            oldCamera = null;
                        }
                        return;
                    }
                    if (oldCamera == null) oldCamera = mc.options.getCameraType();
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    if (waiting) {
                        sync();
                        return;
                    }
                    if (pending.size() >= 24) {
                        sync();
                        return;
                    }
                    var o = mc.options;
                    float f = play ? (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0) : 0,
                            l =
                                    play
                                            ? (o.keyLeft.isDown() ? 1 : 0)
                                                    - (o.keyRight.isDown() ? 1 : 0)
                                            : 0,
                            r =
                                    play
                                            ? (o.keyJump.isDown() ? 1 : 0)
                                                    - (o.keyShift.isDown() ? 1 : 0)
                                            : 0;
                    var q = aim;
                    var motion =
                            new PlanetMotion.Input(
                                    ++sequence,
                                    q.x,
                                    q.y,
                                    q.z,
                                    q.w,
                                    f,
                                    l,
                                    r,
                                    play && OrbitFlightControls.direct(),
                                    play && o.keySprint.isDown());
                    var packet = new GroundFlightPackets.Input(session, motion, toggle);
                    pending.put(sequence, packet);
                    previous = state;
                    try {
                        var predicted =
                                GroundDynamics.step(model, terrain(), state, motion, toggle);
                        if (predicted.planet() != state.planet()) {
                            waiting = true;
                            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
                            return;
                        }
                        state = predicted;
                        renderPose = null;
                        apply();
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
                    } catch (IllegalArgumentException | IllegalStateException failure) {
                        sequence--;
                        pending.remove(packet.motion().sequence());
                        sync();
                    }
                });
    }

    private static void registerHud(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) ->
                        event.registerAboveAll(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "starfold:native_ground_flight_client_hud"),
                                (g, t) -> {
                                    com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
                                    try {
                                        if (!ready()
                                                || !FlightClientHost.get().viewReady()
                                                || Minecraft.getInstance().options.hideGui) return;
                                        var mc = Minecraft.getInstance();
                                        if (FlightClientHost.get().diagnosticHud()) {
                                            debugHud(g, mc);
                                            return;
                                        }
                                        // On the ground the screen is the game's own; a vessel
                                        // shows where it is going and how fast.
                                        if (!controlled) return;
                                        if (waiting) {
                                            g.drawString(
                                                    mc.font,
                                                    I18n.get("hud.starfold.syncing"),
                                                    8,
                                                    8,
                                                    0xffffff,
                                                    true);
                                            return;
                                        }
                                        for (int line = 0; line < HUD.length; line++)
                                            g.drawString(
                                                    mc.font,
                                                    hudLine(line),
                                                    8,
                                                    8 + line * 11,
                                                    line == 1 && hudDanger() ? 0xff7777 : HUD[line],
                                                    true);
                                    } finally {
                                        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
                                    }
                                }));
    }

    private static final int[] HUD = {0xffdd88, 0xbbffbb, 0xbbddff, 0xaaaaaa};

    private static boolean hudDanger() {
        return GroundDynamics.reading(model, terrain(), state, state.tick()).danger();
    }

    /** The four lines a vessel shows: its target, its height and speed, where the target lies, and the keys. */
    static String hudLine(int line) {
        var read = GroundDynamics.reading(model, terrain(), state, state.tick());
        String name = PlanetSystem.name(state.guidance().target());
        return switch (line) {
            case 0 ->
                    I18n.get(
                            "hud.starfold.target",
                            name,
                            distance(read.altitude()),
                            String.format(Locale.ROOT, "%+.0f", read.closing() * 20));
            case 1 ->
                    I18n.get(
                                    "hud.starfold.height",
                                    distance(read.clearance()),
                                    String.format(Locale.ROOT, "%.0f", read.relativeSpeed() * 20))
                            + (state.guidance().relativeBrake()
                                    ? I18n.get("hud.starfold.brake")
                                    : "")
                            + (state.guidance().landing() ? I18n.get("hud.starfold.landing") : "")
                            + (read.danger() ? I18n.get("hud.starfold.danger") : "");
            case 2 -> {
                var position = GroundDynamics.position(model, state, state.tick());
                var target =
                        model.at(state.guidance().target(), state.tick()).center().sub(position);
                var direction =
                        GroundDynamics.orientation(model, state, state.tick())
                                .conjugate()
                                .transform(
                                        new org.joml.Vector3d(target.x(), target.y(), target.z()));
                double bearing = Math.toDegrees(Math.atan2(direction.x, direction.z)),
                        pitch =
                                Math.toDegrees(
                                        Math.atan2(
                                                direction.y, Math.hypot(direction.x, direction.z)));
                yield I18n.get(
                                "hud.starfold.bearing",
                                I18n.get(bearing >= 0 ? "hud.starfold.left" : "hud.starfold.right"),
                                String.format(Locale.ROOT, "%.0f", Math.abs(bearing)),
                                I18n.get(pitch >= 0 ? "hud.starfold.up" : "hud.starfold.down"),
                                String.format(Locale.ROOT, "%.0f", Math.abs(pitch)))
                        + (direction.z < 0 ? I18n.get("hud.starfold.behind") : "");
            }
            default -> I18n.get("hud.starfold.keys");
        };
    }

    /** Blocks, or thousands of them from ten thousand up. */
    public static String distance(double blocks) {
        return Math.abs(blocks) >= 10000
                ? String.format(Locale.ROOT, "%.1fk", blocks / 1000)
                : String.format(Locale.ROOT, "%.0f", blocks);
    }

    private static void debugHud(net.minecraft.client.gui.GuiGraphics g, Minecraft mc) {
        var read = GroundDynamics.reading(model, terrain(), state, state.tick());
        g.drawString(
                mc.font,
                net.minecraft.client.resources.language.I18n.get(
                        "hud.starfold.flight_target",
                        PlanetSystem.name(state.guidance().target()),
                        String.format(Locale.ROOT, "%.1f", read.altitude()),
                        String.format(Locale.ROOT, "%.1f", read.relativeSpeed() * 20),
                        String.format(Locale.ROOT, "%+.1f", read.closing() * 20)),
                8,
                36,
                0xffdd88,
                true);
        g.drawString(
                mc.font,
                net.minecraft.client.resources.language.I18n.get(
                        controlled
                                ? (waiting ? "hud.starfold.syncing" : "hud.starfold.keys")
                                : "hud.starfold.ground_keys"),
                8,
                49,
                0xffffff,
                true);
        g.drawString(
                mc.font,
                net.minecraft.client.resources.language.I18n.get(
                        "hud.starfold.flight_clearance",
                        String.format(Locale.ROOT, "%.1f", read.clearance()),
                        String.format(Locale.ROOT, "%.1f", read.descent() * 20),
                        read.danger()
                                ? net.minecraft.client.resources.language.I18n.get(
                                        "hud.starfold.danger")
                                : "",
                        net.minecraft.client.resources.language.I18n.get(
                                state.guidance().relativeBrake() ? "options.on" : "options.off"),
                        net.minecraft.client.resources.language.I18n.get(
                                state.guidance().landing() ? "options.on" : "options.off")),
                8,
                62,
                read.danger() ? 0xff7777 : 0xbbffbb,
                true);
        var position = GroundDynamics.position(model, state, state.tick());
        var target = model.at(state.guidance().target(), state.tick()).center().sub(position);
        var direction =
                GroundDynamics.orientation(model, state, state.tick())
                        .conjugate()
                        .transform(new org.joml.Vector3d(target.x(), target.y(), target.z()));
        double bearing = Math.toDegrees(Math.atan2(direction.x, direction.z)),
                pitch =
                        Math.toDegrees(
                                Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        g.drawString(
                mc.font,
                net.minecraft.client.resources.language.I18n.get(
                        "hud.starfold.flight_bearing",
                        PlanetSystem.name(state.guidance().target()),
                        net.minecraft.client.resources.language.I18n.get(
                                bearing >= 0 ? "hud.starfold.left" : "hud.starfold.right"),
                        String.format(Locale.ROOT, "%.1f", Math.abs(bearing)),
                        net.minecraft.client.resources.language.I18n.get(
                                pitch >= 0 ? "hud.starfold.up" : "hud.starfold.down"),
                        String.format(Locale.ROOT, "%.1f", Math.abs(pitch)),
                        direction.z < 0
                                ? net.minecraft.client.resources.language.I18n.get(
                                        "hud.starfold.behind")
                                : ""),
                8,
                88,
                0xbbddff,
                true);
        g.drawString(
                mc.font,
                Double.isFinite(read.etaSeconds())
                        ? net.minecraft.client.resources.language.I18n.get(
                                "hud.starfold.flight_eta",
                                String.format(Locale.ROOT, "%.1f", read.etaSeconds()))
                        : net.minecraft.client.resources.language.I18n.get(
                                "hud.starfold.flight_eta_unknown"),
                8,
                75,
                0xbbddff,
                true);
    }

    private static void sync() {
        waiting = true;
        var p = Minecraft.getInstance().player;
        if (p != null && session != 0 && p.tickCount - lastSync >= 20) {
            lastSync = p.tickCount;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new GroundFlightPackets.Sync(session));
        }
    }

    private static PlanetShape.Pose proxy(GroundDynamics.State s, double time) {
        return s.space()
                ? GroundTopology.locate(
                        PlanetShape.of(s.planet()), model.at(s.planet(), time).local(s.position()))
                : s.local();
    }

    private static void apply() {
        var p = Minecraft.getInstance().player;
        if (p == null || !controlled) return;
        // Client coordinates are continuous across faces; a place the chart cannot draw yet waits
        // for the server's frame.
        var at = FlightClientHost.get().chartPosition(state.planet(), proxy(state, state.tick()));
        if (at == null) return;
        boolean jump = new Point(p.getX(), p.getY(), p.getZ()).sub(at).length() > 16;
        p.setPos(at.x(), at.y(), at.z());
        if (jump) {
            p.xo = p.xOld = at.x();
            p.yo = p.yOld = at.y();
            p.zo = p.zOld = at.z();
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        p.getAbilities().flying = true;
    }

    private static void handoff() {
        var p = Minecraft.getInstance().player;
        if (p == null || state.space()) return;
        var feet = FlightClientHost.get().chartPosition(state.planet(), state.local());
        if (feet == null) return;
        int turns = FlightClientHost.get().chartTurns(state.local().face());
        p.setPos(feet.x(), feet.y(), feet.z());
        p.xo = p.xOld = feet.x();
        p.yo = p.yOld = feet.y();
        p.zo = p.zOld = feet.z();
        var velocity = ChartLabSpace.rotate(GroundDynamics.chartVelocity(state), turns);
        p.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
        var q = GroundCamera.landing(model, state.planet(), state.local(), aim, state.tick());
        p.setYRot((float) GroundCamera.yaw(q, p.getYRot() - turns * 90f) + turns * 90f);
        p.setXRot((float) GroundCamera.pitch(q));
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
        p.getAbilities().flying = state.flying();
    }

    /** Dev probe only: point the vessel at its guidance target's centre, keeping the current planet's up (a pilot's manual aim). */
    public static boolean aimAtTarget() {
        return controlled()
                && !waiting
                && probeAim(model.at(state.guidance().target(), state.tick()).center());
    }

    /** Dev probe only: aim at a celestial position without changing the observer or simulation. */
    public static boolean probeAim(Point target) {
        if (!controlled() || waiting) return false;
        var position = GroundDynamics.position(model, state, state.tick());
        var forward = target.sub(position);
        var up = position.sub(model.at(state.planet(), state.tick()).center());
        var f = new org.joml.Vector3d(forward.x(), forward.y(), forward.z()).normalize();
        var u = new org.joml.Vector3d(up.x(), up.y(), up.z()).normalize();
        var left = new org.joml.Vector3d(u).cross(f);
        if (left.length() < 1e-6)
            left =
                    new org.joml.Vector3d(
                                    Math.abs(f.y) < .9
                                            ? new org.joml.Vector3d(0, 1, 0)
                                            : new org.joml.Vector3d(1, 0, 0))
                            .cross(f);
        left.normalize();
        u = new org.joml.Vector3d(f).cross(left).normalize();
        aim = new Quaterniond().setFromNormalized(new org.joml.Matrix3d(left, u, f));
        return true;
    }

    /** Dev probe only: the navigation HUD readings as one line. */
    /** Where on the observer's planet the guidance target stands overhead, now or so many ticks on: both planets move
     *  and turn, so whoever climbs to it climbs from where it will be. Null when the target is the planet underfoot. */
    public static PlanetShape.Pose under(long ahead) {
        if (!ready() || state.guidance().target() == state.planet()) return null;
        long tick = state.tick() + ahead;
        var shape = PlanetShape.of(state.planet());
        return GroundTopology.locate(
                shape,
                PlanetSpace.unit(
                                model.at(state.planet(), tick)
                                        .local(model.at(state.guidance().target(), tick).center()))
                        .scale(shape.radius()));
    }

    public static String probeReading() {
        if (!ready() || !FlightClientHost.get().viewReady()) return "flight not ready";
        var read = GroundDynamics.reading(model, terrain(), state, state.tick());
        // Sun-planet-observer angle of the guidance target: 0 looks at its noon, 180 at its
        // midnight.
        var centre = model.at(state.guidance().target(), state.tick()).center();
        var eye = GroundDynamics.position(model, state, state.tick()).sub(centre);
        var sun = model.star(centre).position().sub(centre);
        double phase =
                Math.toDegrees(
                        Math.acos(Math.clamp(eye.dot(sun) / (eye.length() * sun.length()), -1, 1)));
        var spot = under(0);
        String under =
                spot == null
                        ? ""
                        : String.format(
                                Locale.ROOT,
                                "under face %d %.0f %.0f ",
                                spot.face().ordinal(),
                                spot.point().x(),
                                spot.point().z());
        return under
                + String.format(Locale.ROOT, "phase %.0f ", phase)
                + String.format(
                        Locale.ROOT,
                        "controlled %s space %s planet %d target %d alt %.1f rel %.2f closing %.2f clearance %.1f brake %s land %s",
                        controlled,
                        state.space(),
                        state.planet(),
                        state.guidance().target(),
                        read.altitude(),
                        read.relativeSpeed() * 20,
                        read.closing() * 20,
                        read.clearance(),
                        state.guidance().relativeBrake(),
                        state.guidance().landing());
    }

    public static double probeAltitude() {
        return ready() && FlightClientHost.get().viewReady()
                ? GroundDynamics.reading(model, terrain(), state, state.tick()).altitude()
                : Double.NaN;
    }

    public static boolean turn(LocalPlayer player, double x, double y) {
        if (!controlled()) return false;
        aim = OrbitAttitude.turn(aim, x * .15, y * .15);
        return true;
    }

    private static void prepare(float delta) {
        if (!controlled()) return;
        partial = Math.clamp(delta, 0, 1);
        renderTime = previous.tick() + (state.tick() - previous.tick()) * partial;
        var a = GroundDynamics.position(model, previous, previous.tick());
        var b = GroundDynamics.position(model, state, state.tick());
        var world = a.add(b.sub(a).scale(partial));
        renderPose =
                GroundTopology.locate(
                        PlanetShape.of(state.planet()),
                        model.at(state.planet(), renderTime).local(world));
    }

    public static PlanetShape.Pose pose() {
        if (renderPose == null) prepare(partial);
        return renderPose;
    }

    public static Point eye() {
        var p = pose().point();
        double blend = GroundGap.smooth(GroundCosmos.FREE, GroundCosmos.ALIGN_END, p.y());
        return p.add(
                new Point(
                        0, (Minecraft.getInstance().player.getEyeHeight() - .9) * (1 - blend), 0));
    }

    public static Quaterniond chartAim() {
        return PlanetOrbit.frame(PlanetShape.of(state.planet()), pose())
                .rotation()
                .conjugate()
                .mul(model.at(state.planet(), renderTime).rotation().conjugate())
                .mul(aim)
                .normalize();
    }

    public static Point camera(Camera camera, float delta) {
        if (!controlled()) return null;
        prepare(delta);
        camera.rotation()
                .set(
                        new Quaterniond()
                                .rotationY(
                                        -FlightClientHost.get().chartTurns(pose().face())
                                                * Math.PI
                                                / 2)
                                .mul(GroundView.camera(chartAim())));
        camera.getLookVector().set(0, 0, -1).rotate(camera.rotation());
        camera.getUpVector().set(0, 1, 0).rotate(camera.rotation());
        camera.getLeftVector().set(-1, 0, 0).rotate(camera.rotation());
        var at = FlightClientHost.get().chartPoint(state.planet(), pose().face(), eye());
        return at;
    }

    private GroundFlightClient() {}
}
