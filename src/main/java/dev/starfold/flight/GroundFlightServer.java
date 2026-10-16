package dev.starfold.flight;

import dev.starfold.chart.GroundCamera;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGuidance;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.GroundView;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.ground.GroundNavigationSave;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.GroundTerrain;
import dev.starfold.ground.ObserverFrames;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import dev.starfold.universe.PlanetPackets;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

/** Live flight owner above 160. Native Minecraft remains movement/inventory owner below it. */
public final class GroundFlightServer {
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static Predicate<Entity> clientControl = e -> false;

    private static final class Session {
        final long id;
        final GroundFlightPackets.Queue queue = new GroundFlightPackets.Queue();
        GroundDynamics.State state;
        boolean controlled;
        long lastSync = -100;

        Session(GroundDynamics.State state, boolean controlled) {
            this.state = state;
            this.controlled = controlled;
            long id = UUID.randomUUID().getMostSignificantBits();
            this.id = id == 0 ? 1 : id;
        }
    }

    private static java.util.function.Supplier<GroundSpace.Config> clientMask =
            () -> GroundSpace.DEFAULT_MASK;

    public static void clientMask(java.util.function.Supplier<GroundSpace.Config> supplier) {
        clientMask = supplier;
    }

    public static GroundSpace.Config mask(net.minecraft.world.level.Level level) {
        return level.isClientSide ? clientMask.get() : GroundClock.get((ServerLevel) level).mask;
    }

    /** Where a player is in the universe, or null without a session. */
    public static Point position(ServerPlayer player, GroundClock clock) {
        if (Space.in(player)) return null;
        var s = sessions.get(player.getUUID());
        return s == null || s.state.planet() >= clock.clock.model.count()
                ? null
                : GroundDynamics.position(clock.clock.model, s.state, s.state.tick());
    }

    /** Above this height over the nominal surface a vessel loads no chunks; the approach owners begin below it. */
    public static final double ALOFT = 2048;

    public static boolean aloft(ServerPlayer player) {
        if (Space.in(player)) return false;
        var s = sessions.get(player.getUUID());
        return s != null
                && s.controlled
                && (s.state.space() || s.state.local().point().y() > ALOFT);
    }

    public static long session(ServerPlayer player) {
        if (Space.in(player)) return 0;
        var s = sessions.get(player.getUUID());
        return s == null ? 0 : s.id;
    }

    public static void clientControl(Predicate<Entity> predicate) {
        clientControl = Objects.requireNonNull(predicate);
    }

    public static boolean controlled(Entity entity) {
        if (!PlanetLevels.is(entity.level())) return false;
        if (Space.in(entity)) return false;
        if (entity.level().isClientSide) return clientControl.test(entity);
        if (!(entity instanceof ServerPlayer p)) return false;
        var s = sessions.get(p.getUUID());
        return s != null && s.controlled;
    }

    public static boolean compatible(ServerPlayer player) {
        return (!PlanetLevels.is(player.level())
                        || player.connection.hasChannel(PlanetPackets.Profile.TYPE))
                && player.connection.hasChannel(GroundFlightPackets.Setup.TYPE)
                && player.connection.hasChannel(GroundFlightPackets.State.TYPE);
    }

    public static void initialize() {
        StarfoldNetwork.playS2C(GroundFlightPackets.Seam.TYPE, GroundFlightPackets.Seam.CODEC);
        StarfoldNetwork.playS2C(GroundFlightPackets.Frame.TYPE, GroundFlightPackets.Frame.CODEC);
        StarfoldNetwork.playS2C(GroundFlightPackets.Setup.TYPE, GroundFlightPackets.Setup.CODEC);
        StarfoldNetwork.playS2C(GroundFlightPackets.State.TYPE, GroundFlightPackets.State.CODEC);
        StarfoldNetwork.playC2S(GroundFlightPackets.Input.TYPE, GroundFlightPackets.Input.CODEC);
        StarfoldNetwork.playC2S(GroundFlightPackets.Sync.TYPE, GroundFlightPackets.Sync.CODEC);
        StarfoldNetwork.serverReceiver(
                GroundFlightPackets.Input.TYPE,
                (packet, c) -> {
                    var p = ((net.minecraft.server.level.ServerPlayer) c.player());
                    var s = sessions.get(p.getUUID());
                    if (!Space.in(p)
                            && s != null
                            && s.controlled
                            && PlanetLevels.is(p.level())
                            && packet.session() == s.id)
                        s.queue.offer(packet, p.level().getGameTime());
                });
        StarfoldNetwork.serverReceiver(
                GroundFlightPackets.Sync.TYPE,
                (packet, c) -> {
                    var p = ((net.minecraft.server.level.ServerPlayer) c.player());
                    var s = sessions.get(p.getUUID());
                    if (!Space.in(p)
                            && s != null
                            && PlanetLevels.is(p.level())
                            && packet.session() == s.id
                            && p.level().getGameTime() - s.lastSync >= 20) {
                        s.lastSync = p.level().getGameTime();
                        restart(p, GroundClock.get(p.serverLevel()));
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                cpEvent) -> {
                    var handler =
                            ((net.minecraft.server.level.ServerPlayer) cpEvent.getEntity())
                                    .connection;
                    var server = handler.player.getServer();
                    detach(handler.player);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent cpEvent) -> {
                    var server = cpEvent.getServer();
                    for (var p : server.getPlayerList().getPlayers()) checkpoint(p);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent) -> {
                    var server = cpEvent.getServer();
                    sessions.clear();
                });
    }

    /** A chart state only; region residents have no state to capture. */
    public static GroundDynamics.State capture(ServerPlayer player, GroundClock clock) {
        if (Space.in(player)) return null;
        var v = player.getDeltaMovement();
        var pose =
                PlanetAccess.of(player.level())
                        .body(new Point(player.getX(), player.getY(), player.getZ()));
        var q = GroundCamera.capture(player.getYRot(), player.getXRot());
        var s = sessions.get(player.getUUID());
        var options = s == null ? GroundDynamics.OFF : s.state.guidance();
        return GroundDynamics.local(
                clock.clock.tick(),
                PlanetSystem.planet(player),
                pose,
                new Point(v.x, v.y, v.z),
                q,
                player.getAbilities().flying,
                options);
    }

    /** Prepare before any teleport or return-book mutation; never reset a damaged/occupied entry. */
    public static GroundDynamics.State entry(
            ServerPlayer player, ServerLevel level, GroundClock clock) {
        if (Space.in(player)) return null;
        var saved = GroundNavigationSave.get(level).find(player.getUUID());
        if (saved == null) return null;
        // The body-sized occupancy check needs the real blocks: load the few owners under a saved
        // ground pose
        // now, rather than reading an unloaded chunk as solid and refusing a valid restore.
        if (!saved.space()) {
            var access = PlanetAccess.of(level);
            var target = level;
            var feet = access.feet(saved.planet(), saved.local());
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    var query =
                            net.minecraft.core.BlockPos.containing(
                                    feet.x() + dx * 16, feet.y(), feet.z() + dz * 16);
                    if (!access.inChart(query)) continue;
                    var owner = access.address(query).owner();
                    target.getChunk(owner.getX() >> 4, owner.getZ() >> 4);
                }
        }
        return GroundDynamics.resume(
                clock.clock.model,
                GroundTerrain.of(level, player, clock.mask),
                saved,
                clock.clock.tick());
    }

    public static void enter(ServerPlayer player, GroundClock clock, GroundDynamics.State saved) {
        if (Space.in(player)) {
            region(player);
            return;
        }
        var state = saved == null ? capture(player, clock) : saved;
        boolean controlled =
                state.space()
                        || state.local().point().y() > GroundDynamics.HANDOFF && state.flying();
        var session = new Session(state, controlled);
        sessions.put(player.getUUID(), session);
        if (saved != null) apply(player, clock, session, true);
        checkpoint(player);
        setup(player, clock, session);
    }

    /** Vanilla's saved position wins unless navigation owned the saved movement. Explicit entry is unchanged. */
    private static void login(ServerPlayer player, GroundClock clock) {
        var saved = GroundNavigationSave.get(player.serverLevel()).find(player.getUUID());
        if (restores(saved)) enter(player, clock, entry(player, player.serverLevel(), clock));
        else site(player);
    }

    public static boolean restores(GroundDynamics.State saved) {
        return saved != null
                && (saved.space()
                        || saved.local().point().y() > GroundDynamics.HANDOFF && saved.flying());
    }

    private static void setup(ServerPlayer player, GroundClock clock, Session s) {
        ObserverFrames.announce(player);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player,
                new GroundFlightPackets.Setup(
                        s.id, clock.clock.model, clock.mask, s.state, s.controlled, clock.air));
        FlightHost.get().targetSync(player);
    }

    public static void restart(ServerPlayer player, GroundClock clock) {
        if (Space.in(player)) {
            region(player);
            return;
        }
        var old = sessions.get(player.getUUID());
        if (old == null) return;
        var next = new Session(old.state, old.controlled);
        next.lastSync = old.lastSync;
        sessions.put(player.getUUID(), next);
        setup(player, clock, next);
    }

    public static boolean underWay() {
        for (var s : sessions.values()) if (s.controlled) return true;
        return false;
    }

    public static void tick(ServerLevel level, GroundClock clock) {
        for (var player : List.copyOf(level.players()))
            try {
                if (Space.in(player)) {
                    region(player);
                    continue;
                }
                if (player.isSpectator() || !compatible(player)) {
                    FlightHost.get().leave(player);
                    continue;
                }
                // The dead stay where they fell until they are reborn; they have no journey to go
                // on with.
                if (!player.isAlive()) continue;
                boolean permit = FlightPermit.sync(player);
                var s = sessions.get(player.getUUID());
                if (s == null) {
                    login(player, clock);
                    continue;
                }
                if (s.state.tick() == clock.clock.tick()) continue;
                if (!s.controlled) {
                    GroundSeams.moved(player);
                    s.state = capture(player, clock);
                    // Only someone flying is taken over as a vessel: on foot, a mountain or a tower
                    // above the handoff is still ground.
                    if (s.state.local().point().y() > GroundDynamics.HANDOFF && s.state.flying()) {
                        var next = new Session(s.state, true);
                        sessions.put(player.getUUID(), next);
                        s = next;
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                        setup(player, clock, s);
                    }
                } else {
                    if (s.state.tick() + 1 != clock.clock.tick())
                        throw new IllegalStateException("Native flight epoch mismatch");
                    var input = s.queue.poll();
                    var aim =
                            GroundDynamics.orientation(clock.clock.model, s.state, s.state.tick());
                    var motion =
                            input == null
                                    ? PlanetMotion.Input.idle(s.queue.ack(), aim)
                                    : input.motion();
                    // Without the permit a vessel has no thrust: it hangs where it is, or coasts in
                    // space, until the permit is back.
                    if (!permit) {
                        motion = PlanetMotion.Input.idle(motion.sequence(), motion.orientation());
                        input = null;
                    }
                    int priorPlanet = s.state.planet();
                    s.state =
                            GroundDynamics.step(
                                    clock.clock.model,
                                    GroundTerrain.of(level, player, clock.mask),
                                    s.state,
                                    motion,
                                    input != null && input.toggle());
                    boolean landing =
                            !s.state.space()
                                    && s.state.local().point().y() <= GroundDynamics.HANDOFF;
                    if (landing)
                        s.state =
                                GroundDynamics.local(
                                        s.state.tick(),
                                        s.state.planet(),
                                        s.state.local(),
                                        GroundDynamics.chartVelocity(s.state),
                                        GroundView.upright(GroundDynamics.chartAim(s.state)),
                                        s.state.flying() && permit,
                                        s.state.guidance());
                    apply(player, clock, s, landing);
                    if (landing
                            || player.serverLevel() != level
                            || s.state.planet() != priorPlanet) {
                        var next = new Session(s.state, !landing);
                        sessions.put(player.getUUID(), next);
                        s = next;
                        setup(player, clock, s);
                    }
                }
                checkpoint(player);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player,
                        new GroundFlightPackets.State(s.id, s.queue.ack(), s.state, s.controlled));
            } catch (IllegalArgumentException | IllegalStateException error) {
                player.sendSystemMessage(
                        Component.translatable(
                                "message.starfold.flight_stopped",
                                FlightHost.get().failure(error)));
                FlightHost.get().leave(player);
            }
    }

    private static PlanetShape.Pose view(GroundClock clock, GroundDynamics.State state) {
        return state.space()
                ? GroundTopology.locate(
                        PlanetShape.of(state.planet()),
                        clock.clock.model.at(state.planet(), state.tick()).local(state.position()))
                : state.local();
    }

    private static void apply(ServerPlayer player, GroundClock clock, Session s, boolean handoff) {
        var access = PlanetAccess.of(player.level());
        var pose = view(clock, s.state);
        var feet = access.feet(s.state.planet(), pose);
        var old = access.face(player.getX());
        boolean planetChanged = PlanetSystem.planet(player) != s.state.planet();
        var frame = PlanetOrbit.frame(PlanetShape.of(s.state.planet()), pose);
        var aim =
                frame.rotation()
                        .conjugate()
                        .mul(
                                clock.clock
                                        .model
                                        .at(s.state.planet(), s.state.tick())
                                        .rotation()
                                        .conjugate())
                        .mul(GroundDynamics.orientation(clock.clock.model, s.state, s.state.tick()))
                        .normalize();
        float yaw = (float) GroundCamera.yaw(aim, player.getYRot()),
                pitch = (float) GroundCamera.pitch(aim);
        if (planetChanged) {
            FlightHost.get().viewLost(player);
        }
        // Keep the observer's client coordinates continuous when this place is drawn in their
        // chart.
        ObserverFrames.relocate(player, feet);
        // Synchronize position within the same level, without dimension travel.
        if (old != pose.face().ordinal() || planetChanged || handoff)
            player.connection.teleport(feet.x(), feet.y(), feet.z(), yaw, pitch);
        else {
            player.setPos(feet.x(), feet.y(), feet.z());
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
        player.fallDistance = 0;
        player.getAbilities().flying = s.state.flying();
        if (handoff && !s.state.space()) {
            var velocity = GroundDynamics.chartVelocity(s.state);
            player.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
            player.onUpdateAbilities();
        } else player.setDeltaMovement(Vec3.ZERO);
        player.serverLevel().getChunkSource().move(player);
    }

    public static void checkpoint(ServerPlayer player) {
        if (Space.in(player)) {
            region(player);
            return;
        }
        var s = sessions.get(player.getUUID());
        if (s != null && PlanetLevels.is(player.level())) {
            if (!s.controlled) s.state = capture(player, GroundClock.get(player.serverLevel()));
            GroundNavigationSave.get(player.serverLevel()).put(player.getUUID(), s.state);
        }
    }

    public static void detach(ServerPlayer player) {
        checkpoint(player);
        sessions.remove(player.getUUID());
        FlightHost.get().viewLost(player);
        // Vanilla writes the player's own data on disconnect. Write the navigation record with it,
        // so an unclean stop
        // can never pair a fresh player file with a flight record from the last autosave.
        if (PlanetLevels.is(player.level())) {
            player.serverLevel().getDataStorage().save();
        }
    }

    /** A death ends the journey it happened on: the session and its saved place are given up. */
    public static void forget(ServerPlayer player) {
        sessions.remove(player.getUUID());
        FlightHost.get().viewLost(player);
        if (PlanetLevels.is(player.level()))
            GroundNavigationSave.get(player.serverLevel()).remove(player.getUUID());
    }

    /** Give up stale navigation as well as live prediction; vanilla saves the region position. */
    public static void region(ServerPlayer player) {
        var old = sessions.remove(player.getUUID());
        FlightHost.get().viewLost(player);
        GroundNavigationSave.get(player.serverLevel()).remove(player.getUUID());
        if (old != null) FlightPermit.revoke(player);
    }

    public static void site(ServerPlayer player) {
        long start = ArrivalProbe.start();
        try {
            if (Space.in(player)) {
                region(player);
                return;
            }
            var clock = GroundClock.get(player.serverLevel());
            var next = new Session(capture(player, clock), false);
            sessions.put(player.getUUID(), next);
            checkpoint(player);
            setup(player, clock, next);
        } finally {
            ArrivalProbe.end("site", start);
        }
    }

    public static int orbit(ServerPlayer player) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        var s = sessions.get(player.getUUID());
        if (s == null) return 0;
        var clock = GroundClock.get(player.serverLevel());
        var at = view(clock, s.state);
        var pose = new PlanetShape.Pose(at.face(), new Point(at.point().x(), 4096, at.point().z()));
        if (!new GroundTerrain(player.serverLevel(), player, clock.mask).clear(pose)) return 0;
        var aim = s.state.space() ? new org.joml.Quaterniond() : GroundDynamics.chartAim(s.state);
        var state =
                GroundDynamics.local(
                        clock.clock.tick(),
                        PlanetSystem.planet(player),
                        pose,
                        GroundEphemeris.ZERO,
                        aim,
                        true,
                        s.state.guidance());
        var next = new Session(state, true);
        sessions.put(player.getUUID(), next);
        apply(player, clock, next, true);
        checkpoint(player);
        setup(player, clock, next);
        return 1;
    }

    public static int guidance(ServerPlayer player, int field, boolean on) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        var s = sessions.get(player.getUUID());
        if (s == null) return 0;
        var g = s.state.guidance();
        var options =
                new GroundGuidance.Options(
                        g.target(),
                        field == 0 ? on : g.relativeBrake(),
                        field == 1 ? on : g.landing());
        s.state = GroundDynamics.guidance(s.state, options);
        checkpoint(player);
        restart(player, GroundClock.get(player.serverLevel()));
        player.sendSystemMessage(
                Component.translatable(
                        "message.starfold.guidance",
                        PlanetSystem.name(options.target()),
                        Component.translatable(
                                options.relativeBrake() ? "options.on" : "options.off"),
                        Component.translatable(options.landing() ? "options.on" : "options.off")));
        return 1;
    }

    public static int target(ServerPlayer player, int target) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        var s = sessions.get(player.getUUID());
        if (s == null) return 0;
        var clock = GroundClock.get(player.serverLevel());
        if (target < 0 || target >= clock.clock.model.count()) {
            player.sendSystemMessage(Component.translatable("message.starfold.no_planet"));
            return 0;
        }
        try {
            FlightHost.get().targetCleared(player);
        } catch (IllegalArgumentException | IllegalStateException error) {
            player.sendSystemMessage(
                    Component.translatable(
                            "message.starfold.mark.read_failed", FlightHost.get().failure(error)));
            return 0;
        }
        var g = s.state.guidance();
        s.state =
                GroundDynamics.guidance(
                        s.state,
                        new GroundGuidance.Options(target, g.relativeBrake(), g.landing()));
        checkpoint(player);
        restart(player, clock);
        player.sendSystemMessage(
                Component.translatable("message.starfold.target", PlanetSystem.name(target)));
        return 1;
    }

    private GroundFlightServer() {}
}
