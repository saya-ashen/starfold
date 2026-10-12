package dev.starfold.flight;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.*;

/** What a player feels around a vertex: a warning on the way in, and nausea for as long as they stay. */
public final class AnomalyZone {
    private static final int PERIOD = 10, EFFECT = 100;
    private static final Set<UUID> inside = new HashSet<>();

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % PERIOD != 0) return;
        var access = PlanetAccess.of(level);
        for (var player : level.players()) {
            if (within(level, access, player)) {
                player.addEffect(
                        new MobEffectInstance(MobEffects.CONFUSION, EFFECT, 0, true, false, true));
                if (inside.add(player.getUUID())) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                    player.connection.send(
                            new ClientboundSetSubtitleTextPacket(
                                    Component.translatable("anomaly.starfold.enter.detail")));
                    player.connection.send(
                            new ClientboundSetTitleTextPacket(
                                    Component.translatable("anomaly.starfold.enter")));
                    player.playNotifySound(
                            SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 1, .5f);
                }
            } else if (inside.remove(player.getUUID()))
                player.displayClientMessage(Component.translatable("anomaly.starfold.leave"), true);
        }
    }

    /** Distance from the nearest vertex, in edge-equivalent blocks, of the column the player stands in; infinite off the planet's chart. */
    public static double distance(ServerLevel level, PlanetAccess access, ServerPlayer player) {
        var pos = player.blockPosition();
        if (!access.inChart(pos)) return Double.POSITIVE_INFINITY;
        try {
            var shape = PlanetSystem.shape(level, PlanetSystem.planet(player));
            var at = access.cell(pos);
            var cell =
                    shape.topology()
                            .canonical(new CubeSurfaceTopology.Cell(at.face(), at.u(), 0, at.v()))
                            .cell();
            return Anomaly.distance(
                    PlanetTerrain.direction(shape, cell.face(), cell.u() + .5, cell.v() + .5),
                    shape);
        } catch (IllegalArgumentException outside) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static boolean within(ServerLevel level, PlanetAccess access, ServerPlayer player) {
        return !player.isSpectator()
                && !GroundFlightServer.controlled(player)
                && player.getY() < GroundCoordinates.FLOOR + Anomaly.ZONE_HEIGHT
                && distance(level, access, player) < Anomaly.ZONE;
    }

    public static void clear() {
        inside.clear();
    }

    private AnomalyZone() {}
}
