package dev.starfold;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.starfold.dev.DevBoardingProbe;
import dev.starfold.flight.AnomalyZone;
import dev.starfold.flight.FlightHost;
import dev.starfold.flight.FlightPermit;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.ground.*;
import dev.starfold.ground.GroundPackets;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.GroundTickets;
import dev.starfold.ground.ImageStream;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetCore;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.render.GroundViewServer;
import dev.starfold.render.PlanetRemoteServer;
import dev.starfold.ship.AircraftDock;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.DrillClearance;
import dev.starfold.ship.HullVehicles;
import dev.starfold.ship.SableBoarding;
import dev.starfold.ship.SableBoardingPacket;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.ShipHost;
import dev.starfold.ship.SpacePackets;
import dev.starfold.ship.StarterShip;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.sky.SkyHost;
import dev.starfold.space.SpaceApproach;
import dev.starfold.space.SpaceDust;
import dev.starfold.space.SpaceHost;
import dev.starfold.space.SpaceServer;
import dev.starfold.space.SpaceSpeed;
import dev.starfold.space.SpaceSpeedServer;
import dev.starfold.space.SpaceView;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.universe.Universe;
import dev.starfold.waypoint.ShipGuide;
import dev.starfold.waypoint.WaypointSave;
import dev.starfold.waypoint.WaypointServer;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/** Brings the server side of every subsystem up in a fixed order, and owns the per-tick order between them and the /starfold command tree. */
public final class StarfoldServer {
    private StarfoldServer() {}

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        installFlightHost();
        installGroundHost();
        installShipHost();
        installSkyHost();
        installSpaceHost();
        initializeServerSystems(modBus);
        registerPlanetLifecycle();
        initializePlayerSystems();
        registerTicketsAndCleanup();
        registerSleeping();
        registerClockTicks();
        registerCommands();
    }

    private static void installFlightHost() {
        FlightHost.install(
                new FlightHost() {
                    @Override
                    public void viewLost(net.minecraft.server.level.ServerPlayer player) {
                        GroundViewServer.remove(player);
                    }

                    @Override
                    public void targetSync(net.minecraft.server.level.ServerPlayer player) {
                        WaypointServer.send(player);
                    }

                    @Override
                    public void targetCleared(net.minecraft.server.level.ServerPlayer player) {
                        WaypointSave.get(player.serverLevel()).clear(player.getUUID());
                    }

                    @Override
                    public Component failure(RuntimeException error) {
                        return WaypointServer.failure(error);
                    }

                    @Override
                    public int leave(net.minecraft.server.level.ServerPlayer player) {
                        return PlanetPlayers.leave(player);
                    }
                });
    }

    private static void installGroundHost() {
        GroundHost.install(
                new GroundHost() {
                    @Override
                    public void afterDeath(net.minecraft.server.level.ServerPlayer player) {
                        PlanetPlayers.afterDeath(player);
                    }

                    @Override
                    public void viewLost(net.minecraft.server.level.ServerPlayer player) {
                        GroundViewServer.remove(player);
                    }

                    @Override
                    public void resited(net.minecraft.server.level.ServerPlayer player) {
                        GroundFlightServer.site(player);
                    }

                    @Override
                    public void regionSync(net.minecraft.server.level.ServerPlayer player) {
                        SpaceServer.sync(player);
                    }

                    @Override
                    public boolean controlled(net.minecraft.world.entity.Entity entity) {
                        return GroundFlightServer.controlled(entity);
                    }

                    @Override
                    public boolean aloft(net.minecraft.server.level.ServerPlayer player) {
                        return GroundFlightServer.aloft(player);
                    }

                    @Override
                    public boolean carrying(net.minecraft.server.level.ServerPlayer player) {
                        return SableFrames.carrying(player);
                    }

                    @Override
                    public void clearArrival() {
                        SpaceApproach.clear();
                    }
                });
    }

    private static void installShipHost() {
        ShipHost.install(
                new ShipHost() {
                    @Override
                    public double minimumSpeed() {
                        return SpaceSpeed.V_MIN;
                    }

                    @Override
                    public SpeedCaps speedCaps(dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        var caps = SpaceSpeedServer.caps(body);
                        return new SpeedCaps(caps.cruise(), caps.boost());
                    }

                    @Override
                    public boolean externalRoll(
                            net.minecraft.world.level.Level level,
                            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        return SpaceServer.rollControl(level, body);
                    }

                    @Override
                    public void pilotReleased(net.minecraft.server.level.ServerPlayer player) {
                        SpaceServer.releaseHelm(player);
                    }

                    @Override
                    public void releaseArrival(ServerLevel level) {
                        SpaceApproach.release(level);
                    }

                    @Override
                    public void advanceSurroundings(
                            ServerLevel level,
                            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
                        SpaceServer.step(level, container);
                    }

                    @Override
                    public dev.starfold.sky.Space.Frame departureFrame(
                            ServerLevel level,
                            dev.starfold.sky.Space.Motion motion,
                            org.joml.Quaterniond orientation,
                            dev.starfold.chart.GroundEphemeris.Model model,
                            int planet,
                            long tick) {
                        return SpaceServer.entering(
                                level, motion, orientation, model, planet, tick);
                    }

                    @Override
                    public void hullDeparted(ServerLevel level, int planet, String id) {
                        SpaceServer.departed(level, planet, id);
                    }

                    @Override
                    public dev.starfold.sky.Space.Frame surroundingsFrame(ServerLevel level) {
                        return SpaceServer.frame(level);
                    }

                    @Override
                    public boolean landingLoaded(
                            ServerLevel level,
                            dev.starfold.geometry.BentStripGeometry.Point anchor) {
                        return SpaceApproach.full(level, anchor);
                    }

                    @Override
                    public boolean landingReady(double height, boolean full) {
                        return SpaceApproach.ready(height, full);
                    }

                    @Override
                    public net.minecraft.world.level.ChunkPos landingColumn(
                            dev.starfold.geometry.BentStripGeometry.Point anchor) {
                        return SpaceApproach.column(anchor);
                    }

                    @Override
                    public void hullEnteredChart(ServerLevel level, String id) {
                        SpaceServer.enteredChart(level, id);
                    }

                    @Override
                    public void hullArrived(
                            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        SpaceApproach.arrived(level, body);
                    }

                    @Override
                    public SpacePackets.Frame carriedFrame(
                            net.minecraft.server.level.ServerPlayer player) {
                        return SpaceServer.packet(player);
                    }
                });
    }

    private static void installSkyHost() {
        SkyHost.install(
                new SkyHost() {
                    @Override
                    public boolean underWay() {
                        return GroundFlightServer.underWay();
                    }

                    @Override
                    public double sunlight(
                            ServerLevel level,
                            net.minecraft.core.BlockPos pos,
                            GroundEphemerisSave clock) {
                        var eye =
                                SpaceServer.frame(level)
                                        .universe(
                                                new dev.starfold.geometry.BentStripGeometry.Point(
                                                        pos.getX() + .5,
                                                        pos.getY() + .5,
                                                        pos.getZ() + .5),
                                                clock.model,
                                                clock.tick());
                        return SpaceView.sunlight(clock.model, eye, clock.tick());
                    }
                });
    }

    private static void installSpaceHost() {
        SpaceHost.install(
                new SpaceHost() {
                    @Override
                    public void targetSync(
                            net.minecraft.server.level.ServerPlayer player, long session) {
                        WaypointServer.send(player, session);
                    }
                });
    }

    private static void initializeServerSystems(net.neoforged.bus.api.IEventBus modBus) {
        Universe.grown =
                (level, system, updateClock) -> {
                    GroundServer.system(system);
                    var clock = updateClock.get();
                    for (var player : level.players()) {
                        GroundServer.shapes(player);
                        GroundFlightServer.restart(player, clock);
                    }
                };
        if (net.neoforged.fml.ModList.get().isLoaded("create")
                && net.neoforged.fml.ModList.get().isLoaded("sable")) DrillClearance.initialize();
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) HullVehicles.initializeServer();
        SableFrames.initialize();
        SpaceServer.initialize();
        StarfoldNetwork.playS2C(SableBoardingPacket.TYPE, SableBoardingPacket.CODEC);
        DevBoardingProbe.initialize();
        SableBoarding.initialize();
        Thrusters.initialize(modBus);
        Airstair.initialize(modBus);
        AircraftDock.initialize(modBus);
        SpaceDust.initialize(modBus);
        PlanetChunkGenerator.initialize(modBus);
        Anomaly.initialize(modBus);
        PlanetCore.initialize(modBus);
        FlightPermit.initialize(modBus);
        StarfoldNetwork.playS2C(GroundPackets.Sky.TYPE, GroundPackets.Sky.CODEC);
        StarfoldNetwork.playS2C(GroundPackets.Shapes.TYPE, GroundPackets.Shapes.CODEC);
    }

    private static void registerPlanetLifecycle() {
        // A system's planets take their saved sizes before any of its chunks is addressed.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Load cpEvent) -> {
                    if (!(cpEvent.getLevel()
                            instanceof net.minecraft.server.level.ServerLevel level)) return;
                    var server = level.getServer();
                    if (level.getChunkSource().getGenerator()
                            instanceof SystemChunkGenerator system) {
                        system.materialize(level.getSeed());
                        Universe.bind(level, system);
                        GroundServer.system(system);
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent) -> {
                    var server = cpEvent.getServer();
                    PlanetSystem.reset();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
                                cpEvent) -> {
                    var handler =
                            ((net.minecraft.server.level.ServerPlayer) cpEvent.getEntity())
                                    .connection;
                    var server = handler.player.getServer();
                    GroundServer.shapes(handler.player);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post cpEvent) -> {
                    var server = cpEvent.getServer();
                    if (server.getTickCount() % 20 == 0) PlanetPlayers.arrive(server);
                });
        // Completed player deaths are handled at ServerPlayer.die TAIL by GroundDeathMixin.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                cpEvent) -> {
                    var handler =
                            ((net.minecraft.server.level.ServerPlayer) cpEvent.getEntity())
                                    .connection;
                    var server = handler.player.getServer();
                    GroundServer.forget(handler.player.getUUID());
                });
    }

    private static void initializePlayerSystems() {
        GroundFlightServer.initialize();
        WaypointServer.initialize();
        ShipGuide.initialize();
        StarterShip.initialize();
        GroundViewServer.initialize();
        PlanetRemoteServer.initialize();
    }

    private static void registerTicketsAndCleanup() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Pre cpEvent) -> {
                    if (!(cpEvent.getLevel()
                            instanceof net.minecraft.server.level.ServerLevel level)) return;
                    if (PlanetLevels.is(level)) GroundTickets.update(level);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent cpEvent) -> {
                    var server = cpEvent.getServer();
                    GroundServer.clear();
                    GroundTickets.clear();
                    ImageStream.clear();
                    AnomalyZone.clear();
                });
    }

    private static void registerSleeping() {
        // A bed on a planet is slept in while it is night where the bed stands, whatever the
        // level's clock says.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(PlanetPlayers::canSleep);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent event) -> {
                    var entity = event.getEntity();
                    if (!(entity instanceof net.minecraft.world.entity.player.Player)
                            || event.getProblem()
                                    == net.minecraft.world.entity.player.Player.BedSleepingProblem
                                            .NOT_POSSIBLE_HERE) return;
                    entity.getSleepingPos()
                            .ifPresent(
                                    pos -> {
                                        if (GroundDaylight.kept(entity.level(), pos))
                                            event.setContinueSleeping(
                                                    !GroundDaylight.day(
                                                            (ServerLevel) entity.level(), pos));
                                    });
                });
    }

    private static void registerClockTicks() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Pre cpEvent) -> {
                    var server = cpEvent.getServer();
                    var a = server.getLevel(PlanetLevels.PLANET);
                    try {
                        if (a != null && PlanetSystem.multiple(a)) GroundClock.get(a).advance();
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        for (var p : server.getPlayerList().getPlayers())
                            if (PlanetLevels.is(p.level()))
                                p.sendSystemMessage(
                                        Component.translatable(
                                                "message.starfold.clock_stopped",
                                                WaypointServer.failure(error)));
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Post cpEvent) -> {
                    if (!(cpEvent.getLevel()
                            instanceof net.minecraft.server.level.ServerLevel level)) return;
                    if (!PlanetLevels.is(level)) return;
                    try {
                        var saved = GroundClock.get(level);
                        if (!PlanetSystem.multiple(level)) saved.advance();
                        GroundFlightServer.tick(level, saved);
                        ImageStream.tick(level);
                        GroundViewServer.tick(level, saved);
                        AnomalyZone.tick(level);
                        if (level.getGameTime() % 20 == 0) {
                            for (var player : List.copyOf(level.players())) {
                                var at = GroundFlightServer.position(player, saved);
                                if (at != null && Universe.grow(level, at))
                                    saved = GroundClock.get(level);
                            }
                            for (var player : level.players()) PlanetPlayers.send(player, saved);
                        }
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        for (var player : level.players())
                            player.sendSystemMessage(
                                    Component.translatable(
                                            "message.starfold.clock_failed",
                                            WaypointServer.failure(error)));
                    }
                });
    }

    private static void registerCommands() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent cpEvent) -> {
                    var dispatcher = cpEvent.getDispatcher();
                    dispatcher.register(
                            coreCommands()
                                    .then(navigationCommands())
                                    .then(faceCommands())
                                    .then(spinCommands()));
                });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            coreCommands() {
        return Commands.literal("starfold")
                // Going to the planets, steering and marking are everyone's;
                // the rest is for setting a world up and looking into it.
                .then(
                        Commands.literal("enter")
                                .requires(s -> s.hasPermission(2))
                                .executes(
                                        c ->
                                                PlanetPlayers.enter(
                                                        c.getSource().getPlayerOrException())))
                .then(
                        Commands.literal("terrain")
                                .executes(
                                        c ->
                                                PlanetPlayers.enter(
                                                        c.getSource().getPlayerOrException(),
                                                        PlanetLevels.PLANET)))
                .then(
                        Commands.literal("leave")
                                .executes(
                                        c ->
                                                PlanetPlayers.leave(
                                                        c.getSource().getPlayerOrException())))
                .then(WaypointServer.commands())
                .then(
                        Commands.literal("status")
                                .executes(
                                        c ->
                                                PlanetPlayers.status(
                                                        c.getSource().getPlayerOrException())))
                .then(
                        Commands.literal("universe")
                                .executes(
                                        c ->
                                                PlanetPlayers.universe(
                                                        c.getSource().getPlayerOrException())))
                .then(
                        Commands.literal("orbit")
                                .requires(s -> s.hasPermission(2))
                                .executes(
                                        c ->
                                                GroundFlightServer.orbit(
                                                        c.getSource().getPlayerOrException())));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            navigationCommands() {
        return Commands.literal("nav")
                .then(
                        Commands.literal("target")
                                .then(
                                        Commands.literal("a")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.target(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0)))
                                .then(
                                        Commands.literal("b")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.target(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        1)))
                                .then(
                                        Commands.argument(
                                                        "planet",
                                                        IntegerArgumentType.integer(
                                                                0, PlanetStorage.MAX_PLANETS - 1))
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.target(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        IntegerArgumentType
                                                                                .getInteger(
                                                                                        c,
                                                                                        "planet")))))
                .then(
                        Commands.literal("brake")
                                .then(
                                        Commands.literal("on")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0,
                                                                        true)))
                                .then(
                                        Commands.literal("off")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0,
                                                                        false))))
                .then(
                        Commands.literal("land")
                                .then(
                                        Commands.literal("on")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        1,
                                                                        true)))
                                .then(
                                        Commands.literal("off")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        1,
                                                                        false))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            faceCommands() {
        return Commands.literal("face")
                .requires(s -> s.hasPermission(2))
                .then(
                        Commands.argument("id", IntegerArgumentType.integer(0, 5))
                                .executes(
                                        c ->
                                                PlanetPlayers.face(
                                                        c.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(c, "id"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            spinCommands() {
        return Commands.literal("spin")
                .requires(s -> s.hasPermission(2))
                .then(
                        Commands.literal("on")
                                .executes(
                                        c ->
                                                PlanetPlayers.spin(
                                                        c.getSource().getPlayerOrException(),
                                                        true)))
                .then(
                        Commands.literal("off")
                                .executes(
                                        c ->
                                                PlanetPlayers.spin(
                                                        c.getSource().getPlayerOrException(),
                                                        false)));
    }
}
