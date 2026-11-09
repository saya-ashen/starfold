package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.ground.GroundSeams;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.*;

/** Actual native state display cache; missing coverage is never synthesized as grass or air. */
public final class GroundViewClient {
    private static final GroundViewState<ClientLevel> state = new GroundViewState<>();
    private static long coverageRevision = -1;
    private static java.nio.file.Path surfaceFile;
    private static boolean surfaceDirty;
    private static final GroundSurfaceStore surfaceStore = new GroundSurfaceStore();

    private static void surfaceChanged() {
        surfaceDirty = true;
        surfaceStore.changed();
    }
    private static long lastSurfaceSave;

    static int cachedPlanet() {
        return state.level == Minecraft.getInstance().level ? state.planet : -1;
    }

    private static Set<Long> window = Set.of();
    private static long windowStamp = Long.MIN_VALUE;

    /** Canonical tiles covered by the vanilla view window around the observer. */
    public static Set<Long> window() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !GroundClient.ready()) return Set.of();
        try {
            var pose =
                    GroundFlightClient.controlled()
                            ? GroundFlightClient.pose()
                            : GroundClient.access()
                                    .body(
                                            GroundClient.storage(
                                                    mc.player.getX(),
                                                    mc.player.getY(),
                                                    mc.player.getZ()));
            int radius = mc.options.getEffectiveRenderDistance();
            long stamp =
                    (long) Math.floor(pose.point().x() / 16)
                            + 4096L
                                    * ((long) Math.floor(pose.point().z() / 16)
                                            + 4096L * (pose.face().ordinal() + 8L * radius));
            if (stamp != windowStamp) {
                windowStamp = stamp;
                window = GroundStreaming.window(GroundClient.shape(), pose, radius);
            }
            return window;
        } catch (IllegalArgumentException outside) {
            return Set.of();
        }
    }

    public static void bindSession(ClientLevel world, long id) {
        int planet = GroundFlightClient.planet();
        var shape = GroundClient.shape();
        if (state.matches(world, id, planet, shape)) return;
        boolean change = !state.sameWorld(world, planet, shape);
        clear();
        state.bind(world, id, planet, shape);
        if (change) GroundRenderer.clear();
        PlanetRemoteRenderer.seedOwn(state.planet, id);
    }

    /** Staged passive data is display-only; the new chart session still asks for authoritative refresh. */
    private static boolean previewed;

    static void preview(long id, PlanetRemoteView.Snapshot snapshot) {
        var t = snapshot.terrain();
        var candidate =
                new PlanetPackets.Profile(
                        id,
                        t.seed(),
                        t.version(),
                        t.generation(),
                        snapshot.biome(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        if (state.level == null
                || !dev.starfold.worldgen.PlanetTint.known(candidate, state.level.registryAccess()))
            return;
        profile = candidate;
        terrain = t;
        heights = new PlanetHeightCache(t);
        state.changed.or(snapshot.changed());
        state.surfaces.putAll(snapshot.surfaces());
        coverageRevision = 0;
        previewed = true;
    }

    private static boolean flat;
    private static PlanetPackets.Profile profile;
    private static final java.util.Map<Long,dev.starfold.universe.PlanetPlanSync.Assembler> plans=new java.util.HashMap<>();
    private static Object planLevel;
    private static final java.util.concurrent.ExecutorService planDecoder=
            java.util.concurrent.Executors.newSingleThreadExecutor(r->{
                var thread=new Thread(r,"starfold-plan-decode");thread.setDaemon(true);return thread;
            });

    public static PlanetPackets.Profile profile() {
        return profile;
    }

    public static Map<Long, GroundSurface> surfaces() {
        return state.surfaces;
    }
    public static Map<Long,GroundSurface> overlays() { return state.overlays; }
    public static GroundSurface overlay(long key) { return state.overlays.get(key); }

    private static PlanetTerrain terrain;
    private static PlanetHeightCache heights;

    public static PlanetHeightCache heights() {
        return heights;
    }

    public static PlanetTerrain terrain() {
        return terrain;
    }


    /** Coverage and invalidations belong to their bound planet and shape, even between a teleport and its new profile. */
    public static boolean coverageReady() {
        return state.level == Minecraft.getInstance().level
                && state.session == GroundFlightClient.session()
                && state.planet == GroundClient.planet()
                && state.shape.equals(GroundClient.shape())
                && coverageRevision >= 0
                && (heights == null || heights.shape().equals(state.shape));
    }

    public static boolean flat() {
        return flat;
    }

    public static boolean isChanged(long key) {
        return state.changed.get((int) key);
    }

    public static BitSet changed() {
        return (BitSet) state.changed.clone();
    }

    public static GroundSurface surface(long key) {
        return state.surfaces.get(key);
    }


    private static void invalidate(long key) { state.invalidated.surface(state.shape, key); }

    private static void invalidateEdit(long key, net.minecraft.core.BlockPos pos) {
        state.invalidated.edit(state.shape, key, pos);
    }

    private static long ticks;

    public static List<Long> drainInvalidated() {
        return state.invalidated.drain();
    }

    private static void saveSurfaces() {
        if (surfaceFile == null || !surfaceDirty) return;
        lastSurfaceSave = System.nanoTime();
        surfaceStore.save(surfaceFile, state.overlays, state.changed, state.surfaceRevisions, () -> surfaceDirty = false);
    }

    private static void loadSurfaces(long identity) {
        surfaceFile = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("starfold/surface-cache/" + Long.toHexString(identity) + "-" + state.planet + ".bin");
        lastSurfaceSave = System.nanoTime();
        surfaceStore.bind(new Object());
        surfaceStore.load(surfaceFile, state.changed, images -> {
            state.loaded(images);
            if (!images.isEmpty()) surfaceChanged();
            sendKnown();
        });
    }

    private static void sendKnown() {
        long[] keys = new long[state.overlays.size()], revisions = new long[keys.length];
        int i = 0;
        for (var entry : state.overlays.entrySet()) {
            keys[i] = entry.getKey();
            revisions[i++] = entry.getValue().revision();
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new GroundViewPackets.Known(state.session, keys, revisions));
    }

    public static void clear() {
        saveSurfaces();
        surfaceStore.bind(null);
        surfaceFile = null;
        surfaceDirty = false;
        window = Set.of();
        windowStamp = Long.MIN_VALUE;
        coverageRevision = -1;
        previewed = false;
        flat = false;
        profile = null;
        terrain = null;
        heights = null;
        state.clear();
    }

    public static void initialize() {
        registerProfile();
        installSeamReaders();
        registerSurfaceUpdates();
        registerBlockUpdates();
        registerClientLifecycle();
    }

    private static void clearPlans() {
        plans.values().forEach(dev.starfold.universe.PlanetPlanSync.Assembler::clear);
        plans.clear();
    }
    private static dev.starfold.universe.PlanetPlanSync.Assembler planIdentity(long session) {
        var current=Minecraft.getInstance().level;
        if(planLevel!=current) {clearPlans();planLevel=current;}
        plans.entrySet().removeIf(entry->{
            if(PlanetRemoteRenderer.acceptsPlanSession(entry.getKey()))return false;
            entry.getValue().clear();return true;
        });
        return plans.computeIfAbsent(session,id->new dev.starfold.universe.PlanetPlanSync.Assembler());
    }
    public static void resolveProfile(PlanetPackets.Profile profile,Object owner,PlanetPackets.Profile known,java.util.function.Consumer<PlanetPackets.Profile> ready) {
        if(!PlanetRemoteRenderer.acceptsPlanSession(profile.session()))return;
        if(planIdentity(profile.session()).await(profile,owner,known,ready))
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.starfold.universe.PlanetPlanSync.Request(
                    profile.session(),profile.seed(),profile.planReference().orElseThrow()));
    }
    private static void registerProfile() {
        StarfoldNetwork.clientReceiver(dev.starfold.universe.PlanetPlanSync.Part.TYPE,(packet,c)->{
            if(PlanetRemoteRenderer.acceptsPlanSession(packet.session())) {
                planIdentity(packet.session()).accept(packet,planDecoder,Minecraft.getInstance()::execute);
            }
        });
        StarfoldNetwork.clientReceiver(
                PlanetPackets.Profile.TYPE,
                (incoming, c) -> {
                    if(incoming.session()!=GroundFlightClient.session())return;
                    var expectedWorld=Minecraft.getInstance().level;
                    int expectedPlanet=GroundFlightClient.planet();
                    resolveProfile(incoming,"ground",profile,packet->{
                        if(Minecraft.getInstance().level!=expectedWorld || GroundFlightClient.planet()!=expectedPlanet
                                || !state.matches(expectedWorld, incoming.session(), expectedPlanet, state.shape))return;
                        if (!GroundFlightClient.ready()
                                || packet.session() != GroundFlightClient.session()
                                || net.minecraft.client.Minecraft.getInstance().level != state.level
                                || !PlanetLevels.is(state.level)
                                || !packet.surfaceCompatible()
                                || !dev.starfold.worldgen.PlanetTint.known(
                                        packet, state.level.registryAccess())) return;
                        // A re-sent profile of the same terrain must not reset the far view.
                        if (!previewed && profile != null && terrain != null && profile.sameTerrain(packet)) return;
                        previewed = false;
                        profile = packet;
                        terrain = packet.terrain();
                        heights = new PlanetHeightCache(terrain);
                        coverageRevision = -1;
                    });
                });
    }

    private static void installSeamReaders() {
        GroundSeams.clientEntities(
                world ->
                        world == Minecraft.getInstance().level
                                ? Minecraft.getInstance().level.entitiesForRendering()
                                : List.of());
    }

    private static void registerSurfaceUpdates() {
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Coverage.TYPE,
                (packet, c) -> {
                    if (!GroundFlightClient.ready()
                            || packet.session() != GroundFlightClient.session()
                            || packet.revision() < coverageRevision) return;
                    coverageRevision = packet.revision();
                    flat = packet.flat();
                    var next = BitSet.valueOf(packet.changed());
                    if (state.coverage(next, packet.revision())) surfaceChanged();
                    if (surfaceFile == null) loadSurfaces(packet.identity());
                });
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Surface.TYPE,
                (packet, c) -> {
                    if (!GroundFlightClient.ready()
                            || packet.session() != GroundFlightClient.session() || !packet.compatible()) return;
                    if (state.surface(packet.key(), packet.revision(), packet.image())) surfaceChanged();
                });
    }

    private static void registerBlockUpdates() {
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Delta.TYPE,
                (packet, c) -> {
                    if (packet.session() != GroundFlightClient.session()
                            || net.minecraft.client.Minecraft.getInstance().level != state.level) return;
                    long key =
                            GroundSurfaceIndex.key(
                                    packet.pos().getX() >> 4, packet.pos().getZ() >> 4);
                    invalidateEdit(key, packet.pos());
                });
    }

    static void disconnected() {
        // Deterministic bases outlive chart sessions, but not connections.
        GroundBaseCache.clear();
        clearPlans();
        planLevel = null;
        dev.starfold.universe.PlanetPlanSync.clearCompleted();
    }

    private static void registerClientLifecycle() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.GameShuttingDownEvent event) -> {
                    saveSurfaces();
                    surfaceStore.close();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (!GroundFlightClient.ready()) {
                        if (state.level != null) clear();
                        return;
                    }
                    bindSession(mc.level, GroundFlightClient.session());
                    surfaceStore.drain();
                    // Near terrain is vanilla image chunks now; the snapshot working set stays
                    // empty.
                    ticks++;
                    if (System.nanoTime() - lastSurfaceSave >= 60_000_000_000L) saveSurfaces();
                    // Only the far cover still needs this channel: ask for profile/coverage until
                    // it has arrived.
                    if ((profile == null || (previewed || !coverageReady()))
                            && ticks % 40 == 1
                            && net.minecraft.client.Minecraft.getInstance()
                                    .getConnection()
                                    .hasChannel(GroundViewPackets.Hello.TYPE))
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new GroundViewPackets.Hello(state.session));
                });
    }

    private GroundViewClient() {}
}
