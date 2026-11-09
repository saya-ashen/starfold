package dev.starfold;

import dev.starfold.flight.AnomalyZone;
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
import dev.starfold.ship.ShipRecords;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.space.SpaceServer;
import dev.starfold.space.SpaceDust;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.universe.Universe;
import dev.starfold.waypoint.ShipGuide;
import dev.starfold.waypoint.WaypointServer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/** Brings the server side of every subsystem up in a fixed order, and owns the per-tick order between them and lifecycle wiring. */
public final class StarfoldServer {
    private StarfoldServer() {}

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        StarfoldHostBindings.install();
        initializeServerSystems(modBus);
        dev.starfold.planet.PlanetFire.initialize();
        registerPlanetLifecycle();
        initializePlayerSystems();
        registerTicketsAndCleanup();
        registerSleeping();
        registerClockTicks();
        registerCommands();
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
        SableBoarding.initialize();
        Thrusters.initialize(modBus);
        dev.starfold.space.GasPlayerExposureServer.initialize();
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
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
                    if (event.getLevel() instanceof ServerLevel level) {
                        dev.starfold.ground.ChunkViews.release(level);
                        PlanetPlayers.release(level);
                    }
                });
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
                        dev.starfold.worldgen.plan.PlanetPlanStore.bind(level,system);
                        GroundServer.system(system);
                    }
                    ShipRecords.attach(level);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent) -> {
                    var server = cpEvent.getServer();
                    PlanetPlayers.clear();
                    dev.starfold.ground.ObserverFrames.clear();
                    WaypointServer.clear();
                    ShipRecords.clear();
                    dev.starfold.ship.ShipDormancy.clear();
                    PlanetSystem.reset();
                    dev.starfold.ground.ChunkViews.resetLayout();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
                                cpEvent) -> {
                    var handler =
                            ((net.minecraft.server.level.ServerPlayer) cpEvent.getEntity())
                                    .connection;
                    var server = handler.player.getServer();
                    GroundServer.shapes(handler.player);
                    PlanetPlayers.joined(handler.player);
                });
        // Someone reborn with no bed wakes on the home planet, as a new player does.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(PlanetPlayers::respawn);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) ->
                        PlanetPlayers.joined((net.minecraft.server.level.ServerPlayer) event.getEntity()));
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
                    PlanetPlayers.cancel(handler.player.getUUID());
                    dev.starfold.ground.ObserverFrames.forget(handler.player);
                    WaypointServer.forget(handler.player);
                    GroundServer.forget(handler.player.getUUID());
                });
    }

    private static void initializePlayerSystems() {
        GroundFlightServer.initialize();
        WaypointServer.initialize();
        dev.starfold.waypoint.MapNavigationServer.initialize();
        ShipGuide.initialize();
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
                    ChunkTickReadiness.clear();
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
                        if (a != null) GroundClock.get(a).advance();
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
                        GroundFlightServer.tick(level, saved);
                        ImageStream.tick(level);
                        ChunkTickReadiness.postProcessHeld(level);
                        GroundViewServer.tick(level, saved);
                        AnomalyZone.tick(level);
                        DevelopmentHooks.tickLevel.accept(level);
                        if (level.getGameTime() % 20 == 0) {
                            ShipRecords.refresh(level);
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
                    StarfoldCommands.register(cpEvent.getDispatcher());
                });
    }

}
