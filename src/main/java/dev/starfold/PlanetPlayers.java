package dev.starfold;

import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.FlightPermit;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.ground.GroundPackets;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.WorldSessionSave;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.api.PlanetContent;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.sky.Space;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetSpawn;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.waypoint.WaypointServer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

/** Player entry, departure and planet commands, wired above the participating subsystems. */
public final class PlanetPlayers {
    /** Someone who chose to leave for the overworld stays there until they ask to go back. */
    public static final String STAY = "starfold_stay";

    public static void afterDeath(ServerPlayer player) {
        GroundServer.arrivalForgotten(player.getUUID());
        if (PlanetLevels.is(player.level())) GroundFlightServer.forget(player);
    }

    public static void canSleep(
            net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent event) {
        var player = event.getEntity();
        var pos = event.getPos();
        if (!GroundDaylight.kept(player.level(), pos)) return;
        var problem = event.getVanillaProblem();
        var time = net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_POSSIBLE_NOW;
        var unsafe = net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_SAFE;
        if (GroundDaylight.day(player.serverLevel(), pos)) {
            if (problem == null || problem == time || problem == unsafe) event.setProblem(time);
        } else if (problem == time) {
            net.minecraft.world.entity.player.Player.BedSleepingProblem result = null;
            if (!player.isCreative()) {
                double d0 = 8.0;
                double d1 = 5.0;
                var vec3 = net.minecraft.world.phys.Vec3.atBottomCenterOf(pos);
                List<net.minecraft.world.entity.monster.Monster> list =
                        player.level()
                                .getEntitiesOfClass(
                                        net.minecraft.world.entity.monster.Monster.class,
                                        new net.minecraft.world.phys.AABB(
                                                vec3.x() - 8.0,
                                                vec3.y() - 5.0,
                                                vec3.z() - 8.0,
                                                vec3.x() + 8.0,
                                                vec3.y() + 5.0,
                                                vec3.z() + 8.0),
                                        monster -> monster.isPreventingPlayerRest(player));
                if (!list.isEmpty()) result = unsafe;
            }
            event.setProblem(result);
        }
    }

    /** In a world with a system, whoever stands in the overworld is taken to the home planet: a new player, and
     *  whoever died with no bed to wake in. It is tried once for each time they appear there. */
    public static void arrive(net.minecraft.server.MinecraftServer server) {
        joining.values().removeIf(pending -> !pending.connection.isConnected());
        var planet = server.getLevel(PlanetLevels.PLANET);
        if (planet == null
                || !(planet.getChunkSource().getGenerator() instanceof SystemChunkGenerator))
            return;
        for (var player : List.copyOf(server.overworld().players())) {
            if (!player.isAlive()
                    || player.isSpectator()
                    || player.getTags().contains(STAY)
                    || !GroundFlightServer.entryReady(player,planet)
                    || !GroundServer.arrivalAttempted(player.getUUID())) continue;
            enter(player, PlanetLevels.PLANET);
        }
    }

    public static int enter(ServerPlayer player) {
        return enter(player, PlanetLevels.PLANET);
    }

    public static int enter(ServerPlayer player, ResourceKey<Level> destination) {
        player.removeTag(STAY);
        if (player.level().dimension().equals(destination)) return status(player);
        if (player.level().dimension().location().getNamespace().equals("starfold")) {
            player.sendSystemMessage(Component.translatable("message.starfold.leave_first"));
            return 0;
        }
        if (player.isSpectator()
                || (destination.equals(PlanetLevels.PLANET)
                        && !player.connection.hasChannel(PlanetPackets.Profile.TYPE))
                || (!player.connection.hasChannel(GroundPackets.Sky.TYPE)
                        || !GroundFlightServer.compatible(player))) {
            player.sendSystemMessage(Component.translatable("message.starfold.client"));
            return 0;
        }
        var level = player.getServer().getLevel(destination);
        if (level == null || !GroundFlightServer.entryReady(player,level)) return 0;
        if (!level.players().isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.starfold.one_player"));
            return 0;
        }
        Arrival arrival;
        try {
            arrival = arrival(player, level);
        } catch (IllegalArgumentException | IllegalStateException error) {
            player.sendSystemMessage(
                    Component.translatable(
                            "message.starfold.restore_failed", WaypointServer.failure(error)));
            return 0;
        }
        WorldSessionSave.get(level).remember(player);
        border(level, 0);
        player.teleportTo(
                level, arrival.spawn.x(), arrival.spawn.y(), arrival.spawn.z(), arrival.yaw, 0);
        arrived(player, arrival);
        return 1;
    }

    record Arrival(
            net.minecraft.server.level.ServerLevel level,
            GroundClock clock,
            GroundDynamics.State saved,
            dev.starfold.geometry.BentStripGeometry.Point spawn,
            float yaw) {}

    private static Arrival arrival(ServerPlayer player, net.minecraft.server.level.ServerLevel level) {
        var clock = GroundClock.get(level);
        var saved = GroundFlightServer.entry(player, level, clock);
        var home = saved == null
                ? PlanetContent.get().home(level, GroundDaylight.homeFace(clock.clock.model, PlanetShape.of(0)))
                : null;
        var spawn =
                saved == null
                        ? home.position()
                        : PlanetAccess.of(level)
                                .feet(
                                        saved.planet(),
                                        saved.space()
                                                ? GroundTopology.locate(
                                                        PlanetShape.of(saved.planet()),
                                                        clock.clock
                                                                .model
                                                                .at(saved.planet(), saved.tick())
                                                                .local(saved.position()))
                                                : saved.local());
        return new Arrival(level, clock, saved, spawn, saved == null ? home.yaw() : 0);
    }

    private static void arrived(ServerPlayer player, Arrival arrival) {
        GroundServer.whole(player);
        send(player, arrival.clock);
        GroundFlightServer.enter(player, arrival.clock, arrival.saved);
    }

    record Pending(net.minecraft.network.Connection connection, Arrival arrival) {}

    static final java.util.Map<java.util.UUID, Pending> joining = new java.util.HashMap<>();

    public static void cancel(java.util.UUID player) {
        joining.remove(player);
    }

    public static void clear() {
        joining.clear();
    }

    public static void release(net.minecraft.server.level.ServerLevel level) {
        joining.values().removeIf(pending -> pending.arrival.level == level);
    }

    static Arrival consume(java.util.UUID player, net.minecraft.server.level.ServerLevel level) {
        var pending = joining.remove(player);
        return pending != null && pending.arrival.level == level ? pending.arrival : null;
    }

    /** Logging in to the overworld of a world with a system lands on the home planet instead, before the
     *  client is told which level it joins, so the overworld is never shown first. */
    public static net.minecraft.server.level.ServerLevel join(
            net.minecraft.network.Connection connection,
            ServerPlayer player,
            net.minecraft.server.level.ServerLevel level) {
        var arrival = arrival(connection, player, level);
        if (arrival == null) return level;
        player.moveTo(arrival.spawn.x(), arrival.spawn.y(), arrival.spawn.z(), arrival.yaw, 0);
        return arrival.level;
    }

    /** Someone reborn with no bed to wake in is reborn on the home planet, not in the overworld. */
    public static void respawn(net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent event) {
        var player = (ServerPlayer) event.getEntity();
        var to = event.getDimensionTransition();
        var arrival = arrival(player.connection.getConnection(), player, to.newLevel());
        if (arrival == null) return;
        event.setDimensionTransition(
                new net.minecraft.world.level.portal.DimensionTransition(
                        arrival.level,
                        new net.minecraft.world.phys.Vec3(arrival.spawn.x(), arrival.spawn.y(), arrival.spawn.z()),
                        net.minecraft.world.phys.Vec3.ZERO,
                        arrival.yaw,
                        0,
                        to.missingRespawnBlock(),
                        to.postDimensionTransition()));
    }

    /** The planet arrival for someone about to appear in the overworld, or null to let them appear there. */
    private static Arrival arrival(
            net.minecraft.network.Connection connection,
            ServerPlayer player,
            net.minecraft.server.level.ServerLevel level) {
        cancel(player.getUUID());
        var server = level.getServer();
        var planet = server.getLevel(PlanetLevels.PLANET);
        if (level != server.overworld()
                || planet == null
                || !(planet.getChunkSource().getGenerator() instanceof SystemChunkGenerator)
                || player.getTags().contains(STAY)
                || !planet.players().isEmpty()
                || !GroundFlightServer.entryReady(player,planet)
                || !java.util.stream.Stream.of(
                                PlanetPackets.Profile.TYPE,
                                GroundPackets.Sky.TYPE,
                                dev.starfold.ground.GroundFlightPackets.Setup.TYPE,
                                dev.starfold.ground.GroundFlightPackets.State.TYPE)
                        .allMatch(
                                type ->
                                        net.neoforged.neoforge.network.registration.NetworkRegistry
                                                .hasChannel(
                                                        connection,
                                                        net.minecraft.network.ConnectionProtocol.PLAY,
                                                        type.id())))
            return null;
        Arrival arrival;
        try {
            arrival = arrival(player, planet);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return null; // the overworld poll retries and reports the failure
        }
        // Only an overworld place is worth returning to on leaving; the dead are still where they fell.
        if (player.level() == server.overworld()) WorldSessionSave.get(planet).remember(player);
        border(planet, 0);
        GroundServer.arrivalAttempted(player.getUUID());
        joining.put(player.getUUID(), new Pending(connection, arrival));
        return arrival;
    }

    /** Finishes a login or rebirth on the planet once the player is in the level and can receive packets. */
    public static void joined(ServerPlayer player) {
        var arrival = consume(player.getUUID(), player.serverLevel());
        if (arrival != null) arrived(player, arrival);
    }

    private static void border(net.minecraft.server.level.ServerLevel level, int face) {
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(60_000_000);
    }

    public static int face(ServerPlayer player, int face) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        if (!PlanetLevels.is(player.level())) return 0;
        if(!dev.starfold.worldgen.plan.PlanetPlanStore.ready(player.serverLevel(),PlanetSystem.planet(player)))return 0;
        border(player.serverLevel(), face);
        var spawn = PlanetSpawn.site(player.serverLevel(), PlanetSystem.planet(player), face);
        player.teleportTo(player.serverLevel(), spawn.x(), spawn.y(), spawn.z(), 0, 0);
        send(player, GroundClock.get(player.serverLevel()));
        GroundFlightServer.site(player);
        return status(player);
    }

    public static int spin(ServerPlayer player, boolean on) {
        if (!PlanetLevels.is(player.level())) return 0;
        var clock = GroundClock.get(player.serverLevel());
        clock.spin(on);
        send(player, clock);
        GroundFlightServer.restart(player, clock);
        return status(player);
    }

    public static int status(ServerPlayer player) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        if (!PlanetLevels.is(player.level())) return 0;
        player.sendSystemMessage(
                Component.translatable(
                        "message.starfold.status",
                        PlanetSystem.label(PlanetSystem.planet(player)),
                        PlanetAccess.of(player.level()).face(player.getX()) + 1,
                        Math.round(
                                100
                                        * GroundDaylight.daylight(
                                                player.serverLevel(), player.blockPosition()))));
        var access = PlanetAccess.of(player.level());
        int planet = access.storagePlanet(player.getX(), player.getZ());
        var clock = GroundClock.get(player.serverLevel()).clock;
        var pose = access.body(new dev.starfold.geometry.BentStripGeometry.Point(player.getX(), player.getY(), player.getZ()));
        var eye = clock.model.at(planet, clock.tick()).world(PlanetShape.of(planet).position(pose));
        player.sendSystemMessage(climate(dev.starfold.api.PlanetSeasons.reading(clock.model, planet, eye, clock.tick(), ((SystemChunkGenerator)player.serverLevel().getChunkSource().getGenerator()).planet(planet).terrain().terrainPlan())));
        return 1;
    }

    /** One line for chat and F3: local solar time, season, day of the year, temperature and snow. */
    public static Component climate(dev.starfold.api.PlanetSeasons.Reading r) {
        int minutes = (int) (r.hour() * 60);
        return Component.translatable(
                "message.starfold.climate",
                String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60),
                Component.translatable("message.starfold.season." + r.season()),
                r.day(),
                r.days(),
                Math.round(r.temperature()),
                Math.round(100 * r.snow()));
    }

    public static int universe(ServerPlayer player) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        if (!PlanetLevels.is(player.level())) return 0;
        var level = player.serverLevel();
        var clock = GroundClock.get(level);
        var model = clock.clock.model;
        int here = PlanetSystem.planet(player);
        var from = model.at(here, clock.clock.tick()).center();
        player.sendSystemMessage(
                Component.translatable(
                        "message.starfold.universe",
                        model.everyStar().size(),
                        model.count(),
                        PlanetSystem.label(here)));
        for (int id = 0; id < model.count(); id++) {
            var g = ((SystemChunkGenerator) level.getChunkSource().getGenerator()).planet(id);
            var centre = model.at(id, clock.clock.tick()).center();
            var star = model.star(centre);
            player.sendSystemMessage(
                    Component.translatable(
                            "message.starfold.planet",
                            PlanetSystem.label(id),
                            id,
                            g.terrain().shape().size(),
                            g.generation().depth(),
                            Component.translatable(
                                    g.generation().recipe().minerals().strata().getFirst().block().getDescriptionId()),
                            model.everyStar().indexOf(star) + 1,
                            Math.round(centre.sub(from).length())));
        }
        return 1;
    }

    public static int leave(ServerPlayer player) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        if (!PlanetLevels.is(player.level())) return 0;
        player.addTag(STAY);
        GroundServer.arrivalAttempted(player.getUUID());
        GroundFlightServer.detach(player);
        FlightPermit.revoke(player);
        if (!WorldSessionSave.get(player.serverLevel()).restore(player)) {
            var target = player.getServer().overworld();
            var at = target.getSharedSpawnPos();
            player.teleportTo(target, at.getX() + .5, at.getY() + 1, at.getZ() + .5, 0, 0);
        }
        return 1;
    }

    /** Publish the sky after player orchestration; ground owns the sending state and packets. */
    public static void send(ServerPlayer player, GroundClock clock) {
        GroundServer.send(player, clock);
    }

    private PlanetPlayers() {}
}
