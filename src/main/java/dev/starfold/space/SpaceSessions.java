package dev.starfold.space;

import static dev.starfold.space.SpaceServer.helm;
import static dev.starfold.space.SpaceServer.lead;

import dev.starfold.chart.CelestialTarget;
import dev.starfold.chart.GroundSpace;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import net.minecraft.server.level.ServerLevel;

/** Transient player intents and view ownership, reset at server lifecycle boundaries. */
final class SpaceSessions {
    private SpaceSessions() {}

    static final int WISH_TICKS = 5;

    record Wish(
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            float pitch,
            float roll,
            double mouseYaw,
            double mousePitch,
            long tick) {}

    static final java.util.Map<net.minecraft.server.level.ServerPlayer, Wish> wishes =
            new java.util.WeakHashMap<>();

    record Target(
            ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            CelestialTarget celestial, java.util.UUID ship) {}

    static final java.util.Map<net.minecraft.server.level.ServerPlayer, Target> targets =
            new java.util.WeakHashMap<>();
    static final java.util.Map<net.minecraft.server.level.ServerPlayer, ServerLevel>
            observers = new java.util.WeakHashMap<>();

    record View(
            ServerLevel level, SpacePackets.View packet,
            SpaceViewOwner<net.minecraft.server.level.ServerPlayer> owner) {}

    static final java.util.Map<net.minecraft.server.level.ServerPlayer, View> views =
            new java.util.WeakHashMap<>();
    static final java.util.Map<net.minecraft.world.entity.LivingEntity, ServerLevel>
            weightless = new java.util.WeakHashMap<>();

    static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
                                event) ->
                        SpaceServer.sync((net.minecraft.server.level.ServerPlayer) event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone event) -> {
                    var old = views.get(event.getOriginal());
                    if (old != null && old.owner().owns(
                            (net.minecraft.server.level.ServerPlayer) event.getOriginal()))
                        old.owner().reference().clear();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) ->
                        SpaceServer.sync((net.minecraft.server.level.ServerPlayer) event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent
                                event) ->
                        SpaceServer.sync((net.minecraft.server.level.ServerPlayer) event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                event) -> {
                    forget((net.minecraft.server.level.ServerPlayer) event.getEntity());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    clear();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
                    if (event.getLevel() instanceof ServerLevel level) {
                        release(level);
                    }
                });
    }

    static void forget(net.minecraft.server.level.ServerPlayer player) {
        observers.remove(player);
        removeView(player);
        wishes.remove(player);
        targets.remove(player);
    }

    static void clear() {
        observers.clear();
        views.clear();
        wishes.clear();
        targets.clear();
        weightless.clear();
        dev.starfold.ship.GasShipDamage.clear();
        GasPressureServer.clear();
    }

    static void release(ServerLevel level) {
        observers.values().removeIf(value -> value == level);
        views.values().removeIf(value -> value.level() == level);
        wishes.keySet().removeIf(player -> player.serverLevel() == level);
        targets.values().removeIf(value -> value.level() == level);
        weightless.values().removeIf(value -> value == level);
        SpaceApproach.release(level);
        dev.starfold.ship.GasShipDamage.release(level);
    }

    static boolean fresh(long sent, long now) {
        return now >= sent && now - sent <= WISH_TICKS;
    }

    static void removeView(net.minecraft.server.level.ServerPlayer player) {
        var old = views.get(player);
        if (old != null && old.owner().owns(player)) views.remove(player);
    }

    /** Repeated metadata tolerates packets arriving while a client is between Levels; never captures a flight. */
    static long view(net.minecraft.server.level.ServerPlayer player) {
        if (!Space.in(player)) {
            removeView(player);
            if (player.connection.hasChannel(SpacePackets.View.TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, new SpacePackets.View(0, GroundSpace.DEFAULT_MASK));
            return 0;
        }
        var mask = GroundClock.get(player.serverLevel()).mask;
        var old = views.get(player);
        var v = view(player, player.serverLevel(), mask);
        boolean fresh = old != v;
        if (fresh) SpaceServer.send(player);
        if (fresh || player.level().getGameTime() % 20 == 0) {
            if (player.connection.hasChannel(SpacePackets.View.TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, v.packet());
            SpaceHost.get().targetSync(player, v.packet().session());
            GasPressureServer.send(player, v.packet().session());
        }
        return v.packet().session();
    }

    static View view(net.minecraft.server.level.ServerPlayer player, ServerLevel level,
            GroundSpace.Config mask) {
        var old = views.get(player);
        if (old != null && old.level() == level && old.packet().mask().equals(mask)
                && old.owner().owns(player)) return old;
        long id;
        do {
            id = java.util.UUID.randomUUID().getMostSignificantBits();
        } while (id == 0 || old != null && id == old.packet().session());
        // WeakHashMap.put with an equal key retains the old key; replace it explicitly.
        views.remove(player);
        var view = new View(level, new SpacePackets.View(id, mask), new SpaceViewOwner<>(player));
        views.put(player, view);
        return view;
    }

    static void wish(
            net.minecraft.server.level.ServerPlayer player, SpacePackets.Attitude packet) {
        if (!packet.finite() || !Space.in(player)) {
            wishes.remove(player);
            return;
        }
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                        player.serverLevel());
        if (!(container
                instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server)) {
            wishes.remove(player);
            return;
        }
        var lead = lead(player.serverLevel(), server);
        if (lead == null || !helm(player, lead)) {
            wishes.remove(player);
            return;
        }
        var old = wishes.get(player);
        boolean pending =
                old != null
                        && old.body() == lead
                        && fresh(old.tick(), player.level().getGameTime());
        double my = (pending ? old.mouseYaw() : 0) + packet.mouseYaw(),
                mp = (pending ? old.mousePitch() : 0) + packet.mousePitch();
        wishes.put(
                player,
                new Wish(
                        lead,
                        Math.clamp(packet.pitch(), -1, 1),
                        Math.clamp(packet.roll(), -1, 1),
                        my,
                        mp,
                        player.level().getGameTime()));
    }

}
