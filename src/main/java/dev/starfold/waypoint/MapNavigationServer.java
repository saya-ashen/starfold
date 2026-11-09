package dev.starfold.waypoint;

import dev.starfold.ship.HelmServer;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.ShipMapPackets;
import dev.starfold.ship.ShipRecord;
import dev.starfold.ship.ShipRecords;
import dev.starfold.ship.ShipSave;

import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import dev.starfold.space.MapNavigation;
import dev.starfold.space.SpaceServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.UUID;

/** Private location snapshots and ID-only navigation intents, with explicit owned-ship recovery. */
public final class MapNavigationServer {
    private static final Map<ServerPlayer, UUID> destinations = new java.util.WeakHashMap<>();
    private static final Map<ServerPlayer, Long> requests = new java.util.WeakHashMap<>();

    public static void initialize() {
        StarfoldNetwork.playS2C(ShipMapPackets.Ships.TYPE, ShipMapPackets.Ships.CODEC);
        StarfoldNetwork.playC2S(ShipMapPackets.Request.TYPE, ShipMapPackets.Request.CODEC);
        StarfoldNetwork.playC2S(ShipMapPackets.Target.TYPE, ShipMapPackets.Target.CODEC);
        StarfoldNetwork.serverReceiver(ShipMapPackets.Request.TYPE, (p, c) -> {
            var player = (ServerPlayer) c.player();
            if (!PlanetLevels.is(player.level())) return;
            long now = player.level().getGameTime();
            var last = requests.put(player, now);
            if (last != null && now - last < 10) return;
            ShipRecords.refresh(player.serverLevel());
        });
        StarfoldNetwork.serverReceiver(ShipMapPackets.Target.TYPE, (p, c) -> target((ServerPlayer) c.player(), p));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) -> send((ServerPlayer) e.getEntity()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) -> {
            var player = (ServerPlayer) e.getEntity();
            destinations.remove(player);
            var previous = player.server.getLevel(e.getFrom());
            if (previous != null && PlanetLevels.is(previous)) WaypointSave.get(previous).navigate(player.getUUID(), null);
            send(player);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> {
            if (!(e.getEntity() instanceof ServerPlayer player) || !PlanetLevels.is(player.level())
                    || !net.neoforged.fml.ModList.get().isLoaded("sable")) return;
            var hull = SableFrames.aboard(player);
            if (hull != null && HelmServer.pilot(player, hull)) {
                var save = ShipSave.get(player.serverLevel());
                if (save.claim(hull.getUniqueId(), player.getUUID())) send(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
            markerSelected((ServerPlayer) e.getEntity()); requests.remove(e.getEntity());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> { destinations.clear(); requests.clear(); });
    }

    public static void sync(ServerLevel level) { for (var player : level.players()) send(player); }

    public static void send(ServerPlayer player) {
        if (!PlanetLevels.is(player.level()) || !player.connection.hasChannel(ShipMapPackets.Ships.TYPE)) return;
        var save = ShipSave.get(player.serverLevel());
        PacketDistributor.sendToPlayer(player, new ShipMapPackets.Ships(save.owned(player.getUUID())));
        var id = destinations.get(player);
        if (id == null) return;
        var ship = save.find(id);
        if (ship == null || !save.owns(player.getUUID(), id)) { destinations.remove(player); WaypointSave.get(player.serverLevel()).navigate(player.getUUID(), null); return; }
        if (!Space.in(player)) navigate(player, ship, false);
    }

    private static void target(ServerPlayer player, ShipMapPackets.Target intent) {
        if (!PlanetLevels.is(player.level())) return;
        try {
            var model = GroundClock.get(player.serverLevel()).clock.model;
            if (intent.ship() != null) {
                var save = ShipSave.get(player.serverLevel());
                if (!save.owns(player.getUUID(), intent.ship())) return;
                var ship = save.find(intent.ship());
                if (ship == null || MapNavigation.position(model, ship, GroundClock.get(player.serverLevel()).clock.tick()) == null) return;
                if (Space.in(player)) {
                    dev.starfold.ship.ShipDormancy.requestWake(player.serverLevel(), ship.uuid());
                    SpaceServer.targetShip(player, ship.uuid());
                }
                else navigate(player, ship, true);
                destinations.put(player, ship.uuid());
            } else {
                var body = model.catalogBody(intent.body());
                destinations.remove(player);
                WaypointSave.get(player.serverLevel()).clear(player.getUUID());
                if (Space.in(player)) SpaceServer.target(player, intent.body());
                else if (body.slot() >= 0 && GroundFlightServer.target(player, body.slot()) != 0) {
                    double half = PlanetShape.of(body.slot()).half();
                    WaypointSave.get(player.serverLevel()).navigate(player.getUUID(),
                            new Waypoint("NAV", body.slot(), new PlanetShape.Pose(Face.TOP, new Point(half, 0, half))));
                }
                if (!Space.in(player)) WaypointServer.send(player);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.starfold.mark.failed", WaypointServer.failure(error)));
        }
    }

    private static void navigate(ServerPlayer player, ShipRecord ship, boolean select) {
        if (!(ship.state() instanceof ShipRecord.OnSlot slot)) {
            WaypointSave.get(player.serverLevel()).navigate(player.getUUID(), null);
            WaypointServer.send(player);
            return;
        }
        var pose = MapNavigation.surface(ship);
        if (pose == null || select && GroundFlightServer.target(player, slot.slot()) == 0) return;
        // Navigation accepts ground points; a hull COM height is not a walking destination.
        var point = new PlanetShape.Pose(pose.face(), new Point(pose.point().x(), 0, pose.point().z()));
        WaypointSave.get(player.serverLevel()).navigate(player.getUUID(), new Waypoint("SHIP-" + ship.id().region() + "-" + ship.id().index(), slot.slot(), point));
        WaypointServer.send(player);
    }
    public static void markerSelected(ServerPlayer player) {
        destinations.remove(player);
        if (PlanetLevels.is(player.level())) WaypointSave.get(player.serverLevel()).navigate(player.getUUID(), null);
    }
    private MapNavigationServer() {}
}
