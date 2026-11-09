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
import java.util.stream.IntStream;

/** Bounded pull of actual loaded chunks. Does not generate/load the whole planet for a view. */
public final class GroundViewServer {
    private static final class Viewer {
        long coverageRevision = -1, lastCoverageTick;
        final GroundSurfaceStream surfaceStream = new GroundSurfaceStream();
        // Surfaces wait for the client's list of cached ones, sent after its first coverage.
        boolean profiled, known;
        dev.starfold.universe.PlanetPlanSync.Prepared profile;
        final Map<Long, Long> surfaces = new HashMap<>();
        final long session;
        Viewer(long id) {
            session = id;
        }
    }

    private static final Map<UUID, Viewer> viewers = new HashMap<>();
    private static final Map<ServerLevel, LinkedHashSet<net.minecraft.core.BlockPos>> changes =
            new HashMap<>();

    public static void initialize() {
        GroundSurfaceIndex.initialize();
        StarfoldNetwork.playS2C(PlanetPackets.Profile.TYPE, PlanetPackets.Profile.CODEC);
        StarfoldNetwork.playS2C(dev.starfold.universe.PlanetPlanSync.Part.TYPE,dev.starfold.universe.PlanetPlanSync.Part.CODEC);
        StarfoldNetwork.playC2S(dev.starfold.universe.PlanetPlanSync.Request.TYPE,dev.starfold.universe.PlanetPlanSync.Request.CODEC);
        StarfoldNetwork.serverReceiver(dev.starfold.universe.PlanetPlanSync.Request.TYPE,(packet,c)->{
            var player=(ServerPlayer)c.player();var viewer=viewers.get(player.getUUID());
            if(!Space.in(player) && packet.session()==GroundFlightServer.session(player)
                    && viewer!=null && viewer.profiled && viewer.profile!=null && packet.matches(viewer.profile.profile()))
                viewer.profiled=false;
            PlanetRemoteServer.requestPlan(player,packet);
        });
        StarfoldNetwork.playS2C(GroundViewPackets.Delta.TYPE, GroundViewPackets.Delta.CODEC);
        StarfoldNetwork.playS2C(GroundViewPackets.Coverage.TYPE, GroundViewPackets.Coverage.CODEC);
        StarfoldNetwork.playS2C(GroundViewPackets.Surface.TYPE, GroundViewPackets.Surface.CODEC);
        StarfoldNetwork.playC2S(GroundViewPackets.Hello.TYPE, GroundViewPackets.Hello.CODEC);
        StarfoldNetwork.playC2S(GroundViewPackets.Known.TYPE, GroundViewPackets.Known.CODEC);
        StarfoldNetwork.serverReceiver(GroundViewPackets.Known.TYPE, (packet, c) -> {
            var p = (ServerPlayer) c.player();
            var v = viewers.get(p.getUUID());
            if (Space.in(p) || !PlanetLevels.is(p.level()) || v == null
                    || v.session != packet.session()
                    || packet.session() != GroundFlightServer.session(p)) return;
            var index = GroundSurfaceIndex.get(p.serverLevel(), PlanetSystem.planet(p));
            for (int i = 0; i < packet.keys().length; i++)
                if (packet.revisions()[i] == index.surfaceRevision(packet.keys()[i]))
                    v.surfaces.put(packet.keys()[i], packet.revisions()[i]);
            v.known = true;
        });
        // A fresh viewer resends profile and coverage; the client asks once its cache is bound to
        // the session.
        StarfoldNetwork.serverReceiver(
                GroundViewPackets.Hello.TYPE,
                (packet, c) -> {
                    var p = ((net.minecraft.server.level.ServerPlayer) c.player());
                    if (!Space.in(p)
                            && PlanetLevels.is(p.level())
                            && packet.session() == GroundFlightServer.session(p)) {
                        var v = viewers.get(p.getUUID());
                        // Repeated hellos resend profile and coverage, not every surface again.
                        if (v != null && v.session == packet.session()) {
                            v.profiled = false;
                            v.coverageRevision = -1;
                        } else viewers.put(p.getUUID(), new Viewer(packet.session()));
                    }
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
                });
    }

    public static void changed(
            ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        if (PlanetAccess.of(level).key(pos.getX() >> 4, pos.getZ() >> 4) < 0) return;
        ImageStream.changed(level, pos);
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
        var pending = changes.computeIfAbsent(level, l -> new LinkedHashSet<>());
        while (!pending.isEmpty() && budget-- > 0) {
            // No iterator survives callbacks, and unloaded edits must never generate chunks here.
            var updates=pending.iterator();
            var pos=updates.next(); updates.remove();
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null) continue;
            int state =
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(
                            chunk.getBlockState(pos));
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
        // A lead's destination gets the same non-loading refresh before it owns a chart observer.
        var ticked =
                refreshSlots(
                        PlanetSystem.count(level),
                        level.players().stream()
                                .filter(p -> !Space.in(p))
                                .mapToInt(PlanetSystem::planet),
                        level.players().stream()
                                .filter(Space::in)
                                .mapToInt(SpaceApproach::destination));
        ticked.addAll(GroundSurfaceIndex.pendingPlanets(level));
        for (int id : ticked) GroundSurfaceIndex.get(level, id).tick(level, clock.clock.tick());
    }

    static SortedSet<Integer> refreshSlots(
            int surfaceCount, IntStream occupied, IntStream destinations) {
        var ticked = new TreeSet<Integer>();
        // These are storage slots, not catalog indices (suns and gas giants have no surface).
        IntStream.range(0, Math.min(2, surfaceCount)).forEach(ticked::add);
        IntStream.concat(occupied, destinations)
                .filter(id -> id >= 0 && id < surfaceCount)
                .forEach(ticked::add);
        return ticked;
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
            var profile=PlanetPackets.Profile.whenReady(v.session,generator);
            if(profile.isEmpty())return;
            var transfer=dev.starfold.universe.PlanetPlanSync.prepare(profile.orElseThrow(),level.registryAccess());
            v.profile=transfer;
            for(var part:transfer.parts())net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,part);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,transfer.profile());
            v.profiled = true;
        }
        publishCoverage(level, p, v, index);
        var shape = dev.starfold.chart.PlanetShape.of(planet);
        var eye =
                shape.position(
                        PlanetAccess.of(level)
                                .body(
                                        new dev.starfold.geometry.BentStripGeometry.Point(
                                                p.getX(), p.getEyeY(), p.getZ())));
        long deadline = System.nanoTime() + GroundStreaming.SERVE_BUDGET_NS;
        for (int served = 0; v.known && served < 16 && System.nanoTime() < deadline; served++) {
            long key = v.surfaceStream.next(index, v.surfaces, shape, eye);
            if (key < 0) break;
            var surface = index.surface(key);
            long revision=index.surfaceRevision(key);
            v.surfaces.put(key,revision);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    p, surface==null ? GroundViewPackets.Surface.removal(v.session,key,revision)
                            : new GroundViewPackets.Surface(v.session, key, surface));
        }
    }

    private static void publishCoverage(
            ServerLevel level, ServerPlayer p, Viewer v, GroundSurfaceIndex index) {
        if (!index.ready()) return;
        if (v.coverageRevision != index.revision()
                && (v.coverageRevision < 0 || level.getGameTime() - v.lastCoverageTick >= 10)) {
            v.coverageRevision = index.revision();
            v.lastCoverageTick = level.getGameTime();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    p,
                    new GroundViewPackets.Coverage(
                            v.session,
                            index.identity(),
                            index.revision(),
                            flat(level),
                            index.coverage().toLongArray()));
        }
    }

    private GroundViewServer() {}
}
