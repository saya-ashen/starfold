package dev.starfold.render;

import dev.starfold.flight.GroundFlightServer;
import dev.starfold.ground.ImageStream;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceApproach;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.server.level.*;

import java.util.*;

/** Bounded pull of actual loaded chunks. Does not generate/load the whole planet for a view. */
public final class GroundViewServer {
    private record Sent(
            GroundChunkSnapshot image,
            long version,
            long sampledAt,
            List<GroundDisplayEntities.Image> entities) {}

    private static final Map<ServerLevel, GroundViewVersions> versions = new HashMap<>();

    private static final class Viewer {
        long coverageRevision = -1;
        int cursor;
        boolean profiled;
        final Map<Long, Long> surfaces = new HashMap<>();
        final long session;
        final GroundViewPackets.Subscription subscription = new GroundViewPackets.Subscription();
        final LinkedHashMap<Long, Sent> sent = new LinkedHashMap<>(16, .75f, true);

        Viewer(long id) {
            session = id;
        }
    }

    private static final Map<UUID, Viewer> viewers = new HashMap<>();
    private static final Map<ServerLevel, LinkedHashSet<net.minecraft.core.BlockPos>> changes =
            new HashMap<>();

    public static void initialize() {
        StarfoldNetwork.playS2C(PlanetPackets.Profile.TYPE, PlanetPackets.Profile.CODEC);
        StarfoldNetwork.playS2C(GroundViewPackets.Delta.TYPE, GroundViewPackets.Delta.CODEC);
        StarfoldNetwork.playS2C(GroundViewPackets.Event.TYPE, GroundViewPackets.Event.CODEC);
        StarfoldNetwork.playS2C(GroundViewPackets.Coverage.TYPE, GroundViewPackets.Coverage.CODEC);
        StarfoldNetwork.playS2C(GroundViewPackets.Surface.TYPE, GroundViewPackets.Surface.CODEC);
        StarfoldNetwork.playC2S(GroundViewPackets.Request.TYPE, GroundViewPackets.Request.CODEC);
        StarfoldNetwork.playC2S(GroundViewPackets.Hello.TYPE, GroundViewPackets.Hello.CODEC);
        // A fresh viewer resends profile and coverage; the client asks once its cache is bound to
        // the session.
        StarfoldNetwork.serverReceiver(
                GroundViewPackets.Hello.TYPE,
                (packet, c) -> {
                    var p = ((net.minecraft.server.level.ServerPlayer) c.player());
                    if (!Space.in(p)
                            && PlanetLevels.is(p.level())
                            && packet.session() == GroundFlightServer.session(p))
                        viewers.put(p.getUUID(), new Viewer(packet.session()));
                });
        StarfoldNetwork.playS2C(GroundViewPackets.Image.TYPE, GroundViewPackets.Image.CODEC);
        StarfoldNetwork.serverReceiver(
                GroundViewPackets.Request.TYPE,
                (packet, c) -> {
                    var p = ((net.minecraft.server.level.ServerPlayer) c.player());
                    if (Space.in(p)
                            || !PlanetLevels.is(p.level())
                            || packet.session() != GroundFlightServer.session(p)
                            || !p.connection.hasChannel(GroundViewPackets.Image.TYPE)) return;
                    var v = viewers.get(p.getUUID());
                    if (v == null || v.session != packet.session()) {
                        v = new Viewer(packet.session());
                        viewers.put(p.getUUID(), v);
                    }
                    v.subscription.replace(packet, p.level().getGameTime());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                cpEvent) -> {
                    var h =
                            ((net.minecraft.server.level.ServerPlayer) cpEvent.getEntity())
                                    .connection;
                    var s = h.player.getServer();
                    remove(h.player);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent) -> {
                    viewers.clear();
                    changes.clear();
                    versions.clear();
                });
    }

    public static void changed(
            ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        if (PlanetAccess.of(level).key(pos.getX() >> 4, pos.getZ() >> 4) < 0) return;
        ImageStream.changed(level, pos);
        versions.computeIfAbsent(level, l -> new GroundViewVersions())
                .changed(PlanetAccess.of(level).key(pos.getX() >> 4, pos.getZ() >> 4));
        var pending = changes.computeIfAbsent(level, l -> new LinkedHashSet<>());
        pending.add(pos.immutable());
        while (pending.size() > 8192) pending.remove(pending.iterator().next());
    }

    private static boolean belongs(ServerPlayer p, net.minecraft.core.BlockPos pos) {
        return !Space.in(p) && PlanetSystem.planet(p) == PlanetStorage.find(pos.getX(), pos.getZ());
    }

    private static net.minecraft.core.BlockPos local(
            ServerLevel level, net.minecraft.core.BlockPos pos) {
        return PlanetStorage.local(PlanetStorage.planet(pos), pos);
    }

    public static void event(ServerLevel level, net.minecraft.world.level.BlockEventData event) {
        if (PlanetAccess.of(level).key(event.pos().getX() >> 4, event.pos().getZ() >> 4) < 0)
            return;
        for (var p : level.players()) {
            long id = GroundFlightServer.session(p);
            if (id != 0
                    && belongs(p, event.pos())
                    && p.connection.hasChannel(GroundViewPackets.Event.TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        p,
                        new GroundViewPackets.Event(
                                id, local(level, event.pos()), event.paramA(), event.paramB()));
        }
    }

    public static void remove(ServerPlayer player) {
        viewers.remove(player.getUUID());
    }

    public static boolean flat(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator()
                instanceof net.minecraft.world.level.levelgen.FlatLevelSource flat)) return false;
        var settings = flat.settings();
        var flags = (dev.starfold.mixin.GroundFlatAccess) (Object) settings;
        if (flags.starfold$decoration()
                || flags.starfold$lakes()
                || settings.structureOverrides().isEmpty()
                || settings.structureOverrides().get().size() != 0) return false;
        var layers = settings.getLayers();
        if (layers.size() != 192 || level.getMinBuildHeight() != -64) return false;
        for (int i = 0; i < 192; i++) {
            var expected =
                    i == 0
                            ? net.minecraft.world.level.block.Blocks.BEDROCK
                            : i < 188
                                    ? net.minecraft.world.level.block.Blocks.STONE
                                    : i < 191
                                            ? net.minecraft.world.level.block.Blocks.DIRT
                                            : net.minecraft.world.level.block.Blocks.GRASS_BLOCK;
            if (!layers.get(i).equals(expected.defaultBlockState())) return false;
        }
        return true;
    }

    public static void tick(ServerLevel level, GroundClock clock) {
        publishChanges(level, clock);
        refreshIndices(level, clock);
        for (var p : level.players()) tickViewer(level, clock, p);
    }

    private static void publishChanges(ServerLevel level, GroundClock clock) {
        int budget = 256;
        var updates = changes.computeIfAbsent(level, l -> new LinkedHashSet<>()).iterator();
        while (updates.hasNext() && budget-- > 0) {
            var pos = updates.next();
            updates.remove();
            int state =
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(
                            level.getBlockState(pos));
            for (var p : level.players()) {
                long id = GroundFlightServer.session(p);
                if (id != 0
                        && belongs(p, pos)
                        && p.connection.hasChannel(GroundViewPackets.Delta.TYPE))
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            p,
                            new GroundViewPackets.Delta(
                                    id, clock.clock.tick(), local(level, pos), state));
            }
        }
    }

    private static void refreshIndices(ServerLevel level, GroundClock clock) {
        // The index of every planet someone is on is kept up to date; the first two always were.
        var ticked = new java.util.TreeSet<Integer>();
        ticked.add(0);
        ticked.add(1);
        for (var p : level.players()) if (!Space.in(p)) ticked.add(PlanetSystem.planet(p));
        // A lead's destination gets the same non-loading index refresh before it owns a chart
        // observer.
        for (var p : level.players())
            if (Space.in(p)) {
                int destination = SpaceApproach.destination(p);
                if (destination >= 0) ticked.add(destination);
            }
        for (int id : ticked) GroundSurfaceIndex.get(level, id).tick(level, clock.clock.tick());
    }

    private static void tickViewer(ServerLevel level, GroundClock clock, ServerPlayer p) {
        if (Space.in(p)) {
            remove(p);
            PlanetRemoteServer.tick(p, clock);
            return;
        }
        int planet = PlanetSystem.planet(p),
                offset = PlanetStorage.offset(planet) / 16,
                offsetZ = PlanetStorage.offsetZ(planet) / 16;
        var index = GroundSurfaceIndex.get(level, planet);
        PlanetRemoteServer.tick(p, clock);
        var v = viewers.get(p.getUUID());
        if (v == null) return;
        if (v.session != GroundFlightServer.session(p)) {
            remove(p);
            return;
        }
        var source = level.getChunkSource().getGenerator();
        var generator =
                source instanceof SystemChunkGenerator system ? system.planet(planet) : null;
        if (!v.profiled && generator != null) {
            if (!p.connection.hasChannel(PlanetPackets.Profile.TYPE)) return;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    p, PlanetPackets.Profile.of(v.session, generator));
            v.profiled = true;
        }
        publishCoverage(level, p, v, index);
        var keys = index.coverage();
        int count = keys.cardinality();
        if (count > 0) {
            int key = keys.nextSetBit(v.cursor);
            if (key < 0) key = keys.nextSetBit(0);
            v.cursor = key + 1;
            var surface = index.surface(key);
            if (surface != null
                    && !index.dirty(key)
                    && v.surfaces.getOrDefault((long) key, -1L) != surface.revision()) {
                v.surfaces.put((long) key, surface.revision());
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        p, new GroundViewPackets.Surface(v.session, key, surface));
            }
        }
        // Several volumes per tick under a time budget: a 17-wide row of new owners per chunk
        // step
        // (and a cold window across a seam) must not queue behind one capture per tick.
        long deadline = System.nanoTime() + GroundStreaming.SERVE_BUDGET_NS;
        for (int served = 0;
                served < GroundStreaming.SERVE_TILES
                        && (served == 0 || System.nanoTime() < deadline); ) {
            var tile = v.subscription.poll();
            if (tile == null) break;
            int face = (int) (tile.key() / (128 * 128)),
                    u = (int) (tile.key() % 128),
                    z = (int) (tile.key() / 128 % 128);
            // Unknown remains unknown. Server chunk tickets/vanilla tracking own loading, not
            // this cache.
            var chunk =
                    level.getChunkSource()
                            .getChunkNow(
                                    offset + face * GroundCoordinates.STRIDE / 16 + u, offsetZ + z);
            if (chunk == null) continue;
            var prior = v.sent.get(tile.key());
            long
                    version =
                            versions.computeIfAbsent(level, l -> new GroundViewVersions())
                                    .get(
                                            tile.key()
                                                    + (long) planet
                                                            * PlanetStorage.CHUNKS_PER_PLANET),
                    now = level.getGameTime();
            boolean refresh =
                    GroundViewPolicy.captureNeeded(
                            prior != null,
                            version,
                            prior == null ? -1 : prior.version,
                            now,
                            prior == null ? -1 : prior.sampledAt);
            if (refresh) served++;
            GroundChunkSnapshot image;
            long sampledAt;
            if (refresh) {
                image = GroundChunkSnapshot.capture(chunk, clock.clock.tick());
                sampledAt = now;
            } else {
                image = prior.image;
                sampledAt = prior.sampledAt;
            }
            var entities = GroundDisplayEntities.capture(chunk, level.registryAccess());
            if (prior != null
                    && prior.image.fingerprint().equals(image.fingerprint())
                    && prior.entities.equals(entities)) {
                image = prior.image;
                v.sent.put(tile.key(), new Sent(image, version, sampledAt, entities));
                if (tile.known() == image.revision()) continue;
            }
            v.sent.put(tile.key(), new Sent(image, version, sampledAt, entities));
            while (v.sent.size() > GroundViewCache.DETAIL_LIMIT
                    || v.sent.values().stream().mapToLong(s -> s.image.memoryBytes()).sum()
                            > GroundViewCache.BYTE_LIMIT)
                v.sent.remove(v.sent.keySet().iterator().next());
            if (!refresh) served++;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    p, new GroundViewPackets.Image(v.session, image, entities));
        }
    }

    private static void publishCoverage(
            ServerLevel level, ServerPlayer p, Viewer v, GroundSurfaceIndex index) {
        if (v.coverageRevision != index.revision()) {
            v.coverageRevision = index.revision();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    p,
                    new GroundViewPackets.Coverage(
                            v.session,
                            index.revision(),
                            flat(level),
                            index.coverage().toLongArray()));
        }
    }

    private GroundViewServer() {}
}
