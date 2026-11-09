package dev.starfold.space;

import dev.starfold.chart.GasOcclusion;
import dev.starfold.chart.GasPressure;
import dev.starfold.ship.GasPressurePacket;
import dev.starfold.ship.GasShipDamage;
import dev.starfold.ship.SableFrames;
import dev.starfold.sky.GroundClock;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Piggybacks the existing passive-view recipient traversal, never fleet x players. */
public final class GasPressureServer {
    private record Sent(long session, UUID hull, long sequence, long tick) {}

    // Values deliberately do not hold a player, hull object or Level (which can retain its
    // players).
    private static final Map<ServerPlayer, Sent> sent = new WeakHashMap<>();

    public static void clear() {
        sent.clear();
    }

    public static void send(ServerPlayer player, long session) {
        if (session == 0
                || !dev.starfold.sky.Space.in(player)
                || !player.connection.hasChannel(GasPressurePacket.TYPE)) return;
        var level = player.serverLevel();
        var model = GroundClock.get(level).clock.model;
        if (!GasOcclusion.present(model)) {
            sent.remove(player);
            return;
        }
        long now = level.getGameTime();
        var old = sent.get(player);
        if (old != null && old.session() == session && now - old.tick() < 5) return;
        var hull = SableFrames.aboard(player);
        if (hull != null && (hull.isRemoved() || hull.getLevel() != level)) hull = null;
        UUID uuid = hull == null ? null : hull.getUniqueId();
        if (uuid == null && (old == null || old.session() != session)) return;
        if (uuid == null) uuid = old.hull();
        long sequence =
                old == null || old.session() != session
                        ? 0
                        : old.sequence() == Long.MAX_VALUE ? -1 : old.sequence() + 1;
        if (sequence < 0) return; // Never wrap and accept an old packet as new.
        var sample = hull == null ? null : GasShipDamage.sample(level, uuid);
        var readings = new ArrayList<GasPressure.Reading>(2);
        int age = 0;
        var status =
                hull == null ? GasPressurePacket.Status.CLEAR : GasPressurePacket.Status.UNKNOWN;
        if (sample != null
                && now >= sample.tick()
                && now - sample.tick() < GasPressurePacket.FRESH_TICKS
                && sample.geometry().isPresent()) {
            age = (int) (now - sample.tick());
            readings.add(sample.ambient().orElseThrow());
            int selected = SpaceServer.selectedBodyIndex(player, uuid);
            sample.selected(selected)
                    .ifPresent(
                            r -> {
                                if (r.bodyIndex() != readings.getFirst().bodyIndex())
                                    readings.add(r);
                            });
            status = GasPressurePacket.Status.KNOWN;
        }
        var packet = new GasPressurePacket(session, uuid, sequence, age, status, readings);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        sent.put(player, new Sent(session, uuid, sequence, now));
    }

    private GasPressureServer() {}
}
