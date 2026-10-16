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
import dev.starfold.ship.StarterShip;
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
        var planet = server.getLevel(PlanetLevels.PLANET);
        if (planet == null
                || !(planet.getChunkSource().getGenerator() instanceof SystemChunkGenerator))
            return;
        for (var player : List.copyOf(server.overworld().players())) {
            if (!player.isAlive()
                    || player.isSpectator()
                    || player.getTags().contains(STAY)
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
        if (level == null) return 0;
        if (!level.players().isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.starfold.one_player"));
            return 0;
        }
        GroundClock clock;
        GroundDynamics.State saved;
        dev.starfold.geometry.BentStripGeometry.Point spawn;
        try {
            clock = GroundClock.get(level);
            saved = GroundFlightServer.entry(player, level, clock);
            spawn =
                    saved == null
                            ? StarterShip.home(
                                    level,
                                    GroundDaylight.homeFace(clock.clock.model, PlanetShape.of(0)))
                            : PlanetAccess.of(level)
                                    .feet(
                                            saved.planet(),
                                            saved.space()
                                                    ? GroundTopology.locate(
                                                            PlanetShape.of(saved.planet()),
                                                            clock.clock
                                                                    .model
                                                                    .at(
                                                                            saved.planet(),
                                                                            saved.tick())
                                                                    .local(saved.position()))
                                                    : saved.local());
        } catch (IllegalArgumentException | IllegalStateException error) {
            player.sendSystemMessage(
                    Component.translatable(
                            "message.starfold.restore_failed", WaypointServer.failure(error)));
            return 0;
        }
        WorldSessionSave.get(level).remember(player);
        border(level, 0);
        player.teleportTo(
                level,
                spawn.x(),
                spawn.y(),
                spawn.z(),
                saved == null ? StarterShip.homeYaw(level) : 0,
                0);
        GroundServer.whole(player);
        send(player, clock);
        GroundFlightServer.enter(player, clock, saved);
        return 1;
    }

    private static void border(net.minecraft.server.level.ServerLevel level, int face) {
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(60_000_000);
    }

    public static int face(ServerPlayer player, int face) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        if (!PlanetLevels.is(player.level())) return 0;
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
                        PlanetSystem.name(PlanetSystem.planet(player)),
                        PlanetAccess.of(player.level()).face(player.getX()) + 1,
                        Math.round(
                                100
                                        * GroundDaylight.daylight(
                                                player.serverLevel(), player.blockPosition()))));
        return 1;
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
                        PlanetSystem.name(here)));
        for (int id = 0; id < model.count(); id++) {
            var g = ((SystemChunkGenerator) level.getChunkSource().getGenerator()).planet(id);
            var centre = model.at(id, clock.clock.tick()).center();
            var star = model.star(centre);
            player.sendSystemMessage(
                    Component.translatable(
                            "message.starfold.planet",
                            PlanetSystem.name(id),
                            id,
                            g.terrain().shape().size(),
                            g.generation().depth(),
                            Component.translatable(
                                    g.generation().rocky()
                                            ? g.generation()
                                                    .palette()
                                                    .cover()
                                                    .orElse(g.generation().palette().rock())
                                                    .getDescriptionId()
                                            : "message.starfold.living"),
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
