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
import dev.starfold.space.SpaceDust;
import dev.starfold.space.SpaceServer;
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
                    GroundClock clock;
                    try {
                        clock = updateClock.get();
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        // Preserve the clock origin across Universe.grow's other state changes.
                        throw new ClockUpdateFailure(error);
                    }
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
                        dev.starfold.worldgen.plan.PlanetPlanStore.bind(level, system);
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
                        PlanetPlayers.joined(
                                (net.minecraft.server.level.ServerPlayer) event.getEntity()));
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
                        tickFailure(a, "clock advance", error);
                        var message =
                                Component.translatable(
                                        "message.starfold.clock_stopped",
                                        WaypointServer.failureMessage(error));
                        for (var p : server.getPlayerList().getPlayers())
                            if (PlanetLevels.is(p.level())) p.sendSystemMessage(message);
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Post cpEvent) -> {
                    if (!(cpEvent.getLevel()
                            instanceof net.minecraft.server.level.ServerLevel level)) return;
                    if (!PlanetLevels.is(level)) return;
                    var saved = tickFlight(level);
                    tickGroundViews(level, saved);
                    if (saved == null) return;
                    try {
                        AnomalyZone.tick(level);
                        DevelopmentHooks.tickLevel.accept(level);
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        tickFailure(level, "player environment", error);
                    }
                    if (level.getGameTime() % 20 == 0) tickPeriodic(level, saved);
                });
    }

    /** A failed clock or flight update must not feed partially updated state to its consumers. */
    private static GroundClock tickFlight(ServerLevel level) {
        var saved = tickClock(level);
        if (saved == null) return null;
        try {
            GroundFlightServer.tick(level, saved);
            return saved;
        } catch (IllegalArgumentException | IllegalStateException error) {
            tickFailure(level, "flight", error);
            return null;
        }
    }

    private static GroundClock tickClock(ServerLevel level) {
        try {
            return GroundClock.get(level);
        } catch (IllegalArgumentException | IllegalStateException error) {
            clockFailed(level, error);
            return null;
        }
    }

    private static void clockFailed(ServerLevel level, RuntimeException error) {
        tickFailure(level, "clock load/update", error);
        var message =
                Component.translatable(
                        "message.starfold.clock_failed", WaypointServer.failureMessage(error));
        for (var player : level.players()) player.sendSystemMessage(message);
    }

    /** Only errors from the growth callback's clock supplier carry this origin marker. */
    private static final class ClockUpdateFailure extends IllegalStateException {
        private ClockUpdateFailure(RuntimeException cause) {
            super(cause);
        }
    }

    private static void tickGroundViews(ServerLevel level, GroundClock saved) {
        // Image and far-view publication both consume flight state, but neither owns the other's
        // queues. Held-chunk maintenance needs neither the celestial clock nor a flight update.
        if (saved != null) {
            try {
                ImageStream.tick(level);
            } catch (IllegalArgumentException | IllegalStateException error) {
                tickFailure(level, "image stream", error);
            }
        }
        try {
            ChunkTickReadiness.postProcessHeld(level);
        } catch (IllegalArgumentException | IllegalStateException error) {
            tickFailure(level, "held chunk post-processing", error);
        }
        if (saved != null) {
            try {
                GroundViewServer.tick(level, saved);
            } catch (IllegalArgumentException | IllegalStateException error) {
                tickFailure(level, "ground view", error);
            }
        }
    }

    private static void tickPeriodic(ServerLevel level, GroundClock saved) {
        try {
            ShipRecords.refresh(level);
        } catch (IllegalArgumentException | IllegalStateException error) {
            tickFailure(level, "ship records", error);
        }
        // Growth changes the model and restarts flight sessions. Do not publish the sky or continue
        // to another traveller if that transition failed partway through.
        try {
            for (var player : List.copyOf(level.players())) {
                var at = GroundFlightServer.position(player, saved);
                if (at != null && Universe.grow(level, at)) {
                    saved = tickClock(level);
                    if (saved == null) return;
                }
            }
            for (var player : level.players()) PlanetPlayers.send(player, saved);
        } catch (ClockUpdateFailure error) {
            clockFailed(level, (RuntimeException) error.getCause());
        } catch (IllegalArgumentException | IllegalStateException error) {
            tickFailure(level, "universe growth and sky sync", error);
        }
    }

    private static void tickFailure(ServerLevel level, String phase, RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger("starfold")
                .warn("Starfold {} tick failed in {}", phase, level.dimension().location(), error);
    }

    private static void registerCommands() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent cpEvent) -> {
                    StarfoldCommands.register(cpEvent.getDispatcher());
                });
    }
}
