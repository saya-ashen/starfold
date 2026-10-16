package dev.starfold.render;

import dev.starfold.flight.GroundFlightServer;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceApproach;
import dev.starfold.space.SpaceServer;
import dev.starfold.space.SpaceView;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Remote rendering never loads or generates chunks. Unvalidated surfaces remain unknown. */
public final class PlanetRemoteServer {
    private static final class Viewer {
        long session, last = -100;
        int planet, cursor;
        long revision = -1;
        final Map<Long, Long> sent = new HashMap<>();
    }

    /** The nearest planets of each player, chosen again every second: choosing means measuring to every planet found. */
    private record Near(int here, int count, long chosen, List<Integer> planets) {}

    private static final Map<UUID, Near> nearest = new HashMap<>();
    private static final Map<UUID, Map<Integer, Viewer>> viewers = new HashMap<>();

    public static void initialize() {
        StarfoldNetwork.playS2C(
                PlanetRemotePackets.Summary.TYPE, PlanetRemotePackets.Summary.CODEC);
        StarfoldNetwork.playS2C(
                PlanetRemotePackets.Surface.TYPE, PlanetRemotePackets.Surface.CODEC);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                cpEvent) -> {
                    var h =
                            ((net.minecraft.server.level.ServerPlayer) cpEvent.getEntity())
                                    .connection;
                    var s = h.player.getServer();
                    viewers.remove(h.player.getUUID());
                    nearest.remove(h.player.getUUID());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent) -> {
                    viewers.clear();
                    nearest.clear();
                });
    }

    /** Every other planet of the system is kept up to date for this player, each at its own pace. */
    public static void tick(ServerPlayer player, GroundClock clock) {
        if (Space.in(player)) {
            region(player, clock);
            return;
        }
        if (clock.clock.model.count() < 2) return;
        long session = GroundFlightServer.session(player);
        if (session == 0 || !player.connection.hasChannel(PlanetRemotePackets.Summary.TYPE)) return;
        int here = PlanetSystem.planet(player);
        var mine = viewers.computeIfAbsent(player.getUUID(), u -> new HashMap<>());
        mine.remove(here);
        // An endless universe has more planets than can be kept up to date at once: the nearest
        // are.
        var model = clock.clock.model;
        var chosen = nearest.get(player.getUUID());
        if (chosen == null
                || chosen.here() != here
                || chosen.count() != model.count()
                || clock.clock.tick() - chosen.chosen() >= 20) {
            var from = model.at(here, clock.clock.tick()).center();
            var all = new ArrayList<Integer>();
            for (int id = 0; id < model.count(); id++) if (id != here) all.add(id);
            if (all.size() > NEAR) {
                var distance = new HashMap<Integer, Double>();
                for (int id : all)
                    distance.put(id, model.at(id, clock.clock.tick()).center().sub(from).length());
                all.sort(java.util.Comparator.comparingDouble(distance::get));
                all.subList(NEAR, all.size()).clear();
            }
            chosen = new Near(here, model.count(), clock.clock.tick(), List.copyOf(all));
            nearest.put(player.getUUID(), chosen);
            mine.keySet().retainAll(chosen.planets());
        }
        var near = chosen.planets();
        for (int id : near) tick(player, clock, session, id, mine);
    }

    /** The actual universe eye chooses all candidates, including the departure and reference planets. */
    private static void region(ServerPlayer player, GroundClock clock) {
        long session = SpaceServer.view(player);
        if (session == 0 || !player.connection.hasChannel(PlanetRemotePackets.Summary.TYPE)) return;
        var model = clock.clock.model;
        var mine = viewers.computeIfAbsent(player.getUUID(), u -> new HashMap<>());
        var chosen = nearest.get(player.getUUID());
        if (chosen == null
                || chosen.here() != -1
                || chosen.count() != model.count()
                || clock.clock.tick() - chosen.chosen() >= 20) {
            var eye =
                    SpaceServer.frame(player.serverLevel())
                            .universe(
                                    new dev.starfold.geometry.BentStripGeometry.Point(
                                            player.getX(), player.getEyeY(), player.getZ()),
                                    model,
                                    clock.clock.tick());
            chosen =
                    new Near(
                            -1,
                            model.count(),
                            clock.clock.tick(),
                            SpaceView.nearest(model, eye, clock.clock.tick(), NEAR));
            nearest.put(player.getUUID(), chosen);
            mine.keySet().retainAll(chosen.planets());
        }
        // Pre-stage exactly the far-view data a chart observer needs, under the passive epoch.
        int approach = SpaceApproach.destination(player);
        if (approach >= 0) tick(player, clock, session, approach, mine);
        for (int id : chosen.planets()) if (id != approach) tick(player, clock, session, id, mine);
    }

    /** How many other planets a player is shown in detail. */
    public static final int NEAR = 8;

    private static void tick(
            ServerPlayer player,
            GroundClock clock,
            long session,
            int id,
            Map<Integer, Viewer> mine) {
        var level = player.serverLevel();
        var source = level.getChunkSource().getGenerator();
        var g = source instanceof SystemChunkGenerator system ? system.planet(id) : null;
        if (g == null) return;
        var index = GroundSurfaceIndex.get(level, id);
        var v = mine.get(id);
        if (v == null || v.session != session) {
            v = new Viewer();
            v.session = session;
            v.planet = id;
            mine.put(id, v);
        }
        var keys = index.coverage();
        if (v.revision != index.revision() || clock.clock.tick() - v.last >= 20) {
            var unknown = new BitSet();
            for (int k = keys.nextSetBit(0); k >= 0; k = keys.nextSetBit(k + 1))
                if (index.dirty(k) || index.surface(k) == null) {
                    unknown.set(k);
                    v.sent.remove((long) k);
                }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player,
                    new PlanetRemotePackets.Summary(
                            id,
                            PlanetPackets.Profile.of(session, g),
                            index.revision(),
                            keys.toLongArray(),
                            unknown.toLongArray()));
            v.revision = index.revision();
            v.last = clock.clock.tick();
        }
        if (!keys.isEmpty()) {
            int key = keys.nextSetBit(v.cursor);
            if (key < 0) key = keys.nextSetBit(0);
            v.cursor = key + 1;
            var surface = index.surface(key);
            if (surface != null
                    && !index.dirty(key)
                    && v.sent.getOrDefault((long) key, -1L) != surface.revision()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player,
                        new PlanetRemotePackets.Surface(
                                id, new GroundViewPackets.Surface(session, key, surface)));
                v.sent.put((long) key, surface.revision());
            }
        }
    }

    private PlanetRemoteServer() {}
}
