package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.BentStripGeometry;
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
    private static ClientLevel level;
    private static long session, lastRequest = Long.MIN_VALUE, coverageRevision = -1;
    private static int cursor;
    private static int boundPlanet = -1;
    private static PlanetShape boundShape = PlanetShape.STANDARD;

    static int cachedPlanet() {
        return level == Minecraft.getInstance().level ? boundPlanet : -1;
    }

    private static final GroundStreaming streaming = new GroundStreaming();

    public static Set<Long> residentTiles() {
        return streaming.resident();
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

    private static void updateStreaming(Minecraft mc) {
        var pose =
                GroundFlightClient.controlled()
                        ? GroundFlightClient.pose()
                        : GroundClient.access()
                                .body(
                                        new BentStripGeometry.Point(
                                                mc.player.getX(),
                                                mc.player.getY(),
                                                mc.player.getZ()));
        streaming.update(GroundClient.shape(), pose, mc.options.getEffectiveRenderDistance());
        cache.retain(streaming.resident());
    }

    public static void bindSession(ClientLevel world, long id) {
        int planet = GroundFlightClient.planet();
        var shape = GroundClient.shape();
        if (level == world && session == id && boundPlanet == planet && boundShape.equals(shape))
            return;
        boolean change = level != world || boundPlanet != planet || !boundShape.equals(shape);
        clear();
        level = world;
        session = id;
        boundPlanet = planet;
        boundShape = shape;
        if (change) GroundRenderer.clear();
        PlanetRemoteRenderer.seedOwn(boundPlanet, id);
    }

    /** Staged passive data is display-only; the new chart session still asks for authoritative refresh. */
    private static boolean previewed;

    static void preview(long id, PlanetRemoteView.Snapshot snapshot) {
        var t = snapshot.terrain();
        profile =
                new PlanetPackets.Profile(
                        id, t.seed(), t.version(), t.generation(), snapshot.biome());
        terrain = t;
        heights = new PlanetHeightCache(t);
        changed.or(snapshot.changed());
        surfaces.putAll(snapshot.surfaces());
        coverageRevision = 0;
        previewed = true;
    }

    private static boolean flat;
    private static PlanetPackets.Profile profile;

    public static PlanetPackets.Profile profile() {
        return profile;
    }

    public static Map<Long, GroundViewCache.Surface> surfaces() {
        return surfaces;
    }

    private static PlanetTerrain terrain;
    private static PlanetHeightCache heights;

    public static PlanetHeightCache heights() {
        return heights;
    }

    public static PlanetTerrain terrain() {
        return terrain;
    }

    public static net.minecraft.world.level.block.state.BlockState generated(
            net.minecraft.core.BlockPos owner) {
        long key = GroundSurfaceIndex.key(owner.getX() >> 4, owner.getZ() >> 4);
        if (terrain == null || !coverageReady() || key < 0 || isChanged(key)) return null;
        var cell = GroundTopology.cell(owner);
        return terrain.version() >= PlanetTerrain.MINING_VERSION
                ? terrain.state(cell.face(), cell.u(), owner.getY(), cell.v())
                : terrain.state(heights.ground(cell.face(), cell.u(), cell.v()), owner.getY());
    }

    private static final BitSet changed = new BitSet();
    private static final Map<Long, GroundViewCache.Surface> surfaces = new HashMap<>();
    private static final LinkedHashMap<net.minecraft.core.BlockPos, GroundViewPackets.Delta>
            deltas = new LinkedHashMap<>();

    public static net.minecraft.world.level.block.state.BlockState state(
            net.minecraft.core.BlockPos owner) {
        var delta = deltas.get(owner);
        if (delta != null)
            return net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(delta.state());
        var state = cache.canonical(owner);
        return state == null ? generated(owner) : state;
    }

    /** Complete authoritative snapshots plus newer edits; no generated terrain or surface LOD. */
    static net.minecraft.world.level.block.state.BlockState collisionState(
            net.minecraft.core.BlockPos owner) {
        var state = cache.canonical(owner);
        if (state == null) return null;
        var delta = deltas.get(owner);
        return delta == null
                ? state
                : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(delta.state());
    }

    static boolean hasDeltas(long key) {
        return deltas.keySet().stream()
                .anyMatch(p -> GroundSurfaceIndex.key(p.getX() >> 4, p.getZ() >> 4) == key);
    }

    static net.minecraft.world.level.block.state.BlockState updatedState(
            GroundChunkSnapshot image, net.minecraft.core.BlockPos owner) {
        var delta = deltas.get(owner);
        return delta != null && delta.epoch() >= image.revision()
                ? net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(delta.state())
                : image.state(owner);
    }

    private static final Map<
                    Long,
                    Map<
                            net.minecraft.core.BlockPos,
                            net.minecraft.world.level.block.entity.BlockEntity>>
            entities = new HashMap<>();

    public static java.util.List<net.minecraft.world.level.block.entity.BlockEntity> entities() {
        return entities.values().stream().flatMap(m -> m.values().stream()).toList();
    }

    /** Coverage and invalidations belong to their bound planet and shape, even between a teleport and its new profile. */
    public static boolean coverageReady() {
        return level == Minecraft.getInstance().level
                && session == GroundFlightClient.session()
                && boundPlanet == GroundClient.planet()
                && boundShape.equals(GroundClient.shape())
                && coverageRevision >= 0
                && (heights == null || heights.shape().equals(boundShape));
    }

    public static boolean flat() {
        return flat;
    }

    public static boolean isChanged(long key) {
        return changed.get((int) key);
    }

    public static BitSet changed() {
        return (BitSet) changed.clone();
    }

    public static GroundViewCache.Surface surface(long key) {
        return surfaces.get(key);
    }

    private static final Set<Long> invalidated = new LinkedHashSet<>();
    private static final GroundViewCache cache =
            new GroundViewCache(() -> boundShape, GroundViewClient::invalidate);

    private static void invalidate(long key) {
        invalidated.add(key);
        for (long neighbour : GroundStreaming.neighbours(boundShape, key))
            invalidated.add(neighbour);
        trimInvalidated();
    }

    /** One block edit re-meshes its own tile, plus a neighbour only when the block touches that edge.
     * Light reaching further is corrected by the prioritized snapshot refresh that follows the edit. */
    private static void invalidateEdit(long key, net.minecraft.core.BlockPos pos) {
        invalidated.add(key);
        int x = pos.getX() & 15, z = pos.getZ() & 15;
        var neighbours = GroundStreaming.neighbours(boundShape, key);
        if (x == 0) invalidated.add(neighbours[0]);
        if (x == 15) invalidated.add(neighbours[1]);
        if (z == 0) invalidated.add(neighbours[2]);
        if (z == 15) invalidated.add(neighbours[3]);
        trimInvalidated();
    }

    private static void trimInvalidated() {
        while (invalidated.size() > GroundViewCache.DETAIL_LIMIT * 5)
            invalidated.remove(invalidated.iterator().next());
    }

    private static long ticks, lastArrival = Long.MIN_VALUE;

    /** True once no full snapshot has arrived for STALL_TICKS: nothing a waiting mesh could still gain. */
    public static boolean stalled() {
        return lastArrival == Long.MIN_VALUE || ticks - lastArrival >= GroundStreaming.STALL_TICKS;
    }

    public static GroundViewCache cache() {
        return cache;
    }

    public static List<Long> drainInvalidated() {
        var out = List.copyOf(invalidated);
        invalidated.clear();
        return out;
    }

    public static void clear() {
        streaming.clear();
        window = Set.of();
        windowStamp = Long.MIN_VALUE;
        level = null;
        boundPlanet = -1;
        session = 0;
        lastRequest = Long.MIN_VALUE;
        lastArrival = Long.MIN_VALUE;
        cursor = 0;
        coverageRevision = -1;
        previewed = false;
        flat = false;
        profile = null;
        terrain = null;
        heights = null;
        changed.clear();
        surfaces.clear();
        entities.clear();
        deltas.clear();
        cache.clear();
        invalidated.clear();
    }

    public static void initialize() {
        registerProfile();
        installSeamReaders();
        registerImages();
        registerSurfaceUpdates();
        registerBlockUpdates();
        registerClientLifecycle();
    }

    private static void registerProfile() {
        StarfoldNetwork.clientReceiver(
                PlanetPackets.Profile.TYPE,
                (packet, c) -> {
                    if (!GroundFlightClient.ready()
                            || packet.session() != GroundFlightClient.session()
                            || net.minecraft.client.Minecraft.getInstance().level != level
                            || !PlanetLevels.is(level)) return;
                    previewed = false;
                    profile = packet;
                    terrain =
                            new PlanetTerrain(packet.seed(), packet.version(), packet.generation());
                    heights = new PlanetHeightCache(terrain);
                    coverageRevision = -1;
                });
    }

    private static void installSeamReaders() {
        GroundSeams.clientRead(
                (world, owner) ->
                        world == level && samePlanet(owner)
                                ? state(GroundClient.local(owner))
                                : null);
        GroundSeams.clientCollisionRead(
                (world, owner) ->
                        world == level && samePlanet(owner)
                                ? collisionState(GroundClient.local(owner))
                                : null);
        GroundSeams.clientEntities(
                world ->
                        world == Minecraft.getInstance().level
                                ? Minecraft.getInstance().level.entitiesForRendering()
                                : List.of());
        GroundSeams.clientEntity(
                (world, owner) -> {
                    if (world != level || !samePlanet(owner)) return null;
                    var local = GroundClient.local(owner);
                    return entities.getOrDefault(
                                    GroundSurfaceIndex.key(local.getX() >> 4, local.getZ() >> 4),
                                    Map.of())
                            .get(local);
                });
        GroundSeams.clientLight(
                (world, query) -> {
                    if (world != level || !samePlanet(query.pos())) return null;
                    var pos = GroundClient.local(query.pos());
                    long key = GroundSurfaceIndex.key(pos.getX() >> 4, pos.getZ() >> 4);
                    var image = cache.image(key);
                    return image == null ? null : image.light(query.layer(), pos);
                });
    }

    private static void registerImages() {
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Image.TYPE,
                (packet, c) -> {
                    if (!GroundFlightClient.ready()
                            || packet.session() != GroundFlightClient.session()
                            || net.minecraft.client.Minecraft.getInstance().level != level) return;
                    if (packet.chunk().revision() < cache.version(packet.chunk().key())) return;
                    if (cache.accept(packet.chunk()) || cache.refill(packet.chunk()))
                        lastArrival = ticks;
                    deltas.entrySet()
                            .removeIf(
                                    e ->
                                            packet.chunk().owns(e.getKey())
                                                    && e.getValue().epoch()
                                                            <= packet.chunk().revision());
                    var previous = entities.getOrDefault(packet.chunk().key(), Map.of());
                    var next =
                            new HashMap<
                                    net.minecraft.core.BlockPos,
                                    net.minecraft.world.level.block.entity.BlockEntity>();
                    for (var image : packet.entities()) {
                        var be = previous.get(image.pos());
                        var state = packet.chunk().state(image.pos());
                        if (be != null
                                && be.getBlockState().equals(state)
                                && net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                                        .getKey(be.getType())
                                        .equals(image.type()))
                            be.loadWithComponents(image.update(), level.registryAccess());
                        else
                            be =
                                    GroundDisplayEntities.create(
                                            new GroundDisplayEntities.Image(
                                                    GroundClient.storage(image.pos()),
                                                    image.type(),
                                                    image.update()),
                                            level,
                                            state);
                        next.put(image.pos(), be);
                    }
                    entities.put(packet.chunk().key(), next);
                    var old = surfaces.get(packet.chunk().key());
                    if (old == null || old.revision() <= packet.chunk().revision())
                        surfaces.put(
                                packet.chunk().key(), GroundViewCache.surfaceImage(packet.chunk()));
                });
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
                    changed.clear();
                    changed.or(BitSet.valueOf(packet.changed()));
                });
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Surface.TYPE,
                (packet, c) -> {
                    if (!GroundFlightClient.ready()
                            || packet.session() != GroundFlightClient.session()) return;
                    var old = surfaces.get(packet.key());
                    if (old == null || old.revision() < packet.image().revision()) {
                        surfaces.put(packet.key(), packet.image());
                        invalidate(packet.key());
                    }
                });
    }

    private static void registerBlockUpdates() {
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Delta.TYPE,
                (packet, c) -> {
                    if (packet.session() != GroundFlightClient.session()
                            || net.minecraft.client.Minecraft.getInstance().level != level) return;
                    long key =
                            GroundSurfaceIndex.key(
                                    packet.pos().getX() >> 4, packet.pos().getZ() >> 4);
                    if (packet.epoch() < cache.version(key)) return;
                    deltas.put(packet.pos(), packet);
                    while (deltas.size() > 8192) deltas.remove(deltas.keySet().iterator().next());
                    invalidateEdit(key, packet.pos());
                });
        StarfoldNetwork.clientReceiver(
                GroundViewPackets.Event.TYPE,
                (packet, c) -> {
                    if (packet.session() != GroundFlightClient.session()) return;
                    long key =
                            GroundSurfaceIndex.key(
                                    packet.pos().getX() >> 4, packet.pos().getZ() >> 4);
                    var map = entities.get(key);
                    if (map != null) {
                        var be = map.get(packet.pos());
                        if (be != null) be.triggerEvent(packet.id(), packet.data());
                    }
                });
    }

    private static void registerClientLifecycle() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                        clear());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (!GroundFlightClient.ready()) {
                        if (level != null) clear();
                        return;
                    }
                    bindSession(mc.level, GroundFlightClient.session());
                    // Near terrain is vanilla image chunks now; the snapshot working set stays
                    // empty.
                    ticks++;
                    // Only the far cover still needs this channel: ask for profile/coverage until
                    // it has arrived.
                    if ((previewed || !coverageReady())
                            && ticks % 40 == 1
                            && net.minecraft.client.Minecraft.getInstance()
                                    .getConnection()
                                    .hasChannel(GroundViewPackets.Hello.TYPE))
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new GroundViewPackets.Hello(session));
                    entities.keySet().removeIf(key -> !cache.detailed(key));
                    for (var be : entities()) tickEntity(be);
                    long now = mc.level.getGameTime();
                    if (mc.isPaused()
                            || lastRequest != Long.MIN_VALUE
                                    && now >= lastRequest
                                    && now - lastRequest < GroundStreaming.REQUEST_TICKS
                            || !net.minecraft.client.Minecraft.getInstance()
                                    .getConnection()
                                    .hasChannel(GroundViewPackets.Request.TYPE)) return;
                    lastRequest = now;
                    // Tiles with edits newer than their snapshot jump the refresh rotation, so
                    // light follows the block.
                    var edited = new HashSet<Long>();
                    for (var pos : deltas.keySet())
                        edited.add(GroundSurfaceIndex.key(pos.getX() >> 4, pos.getZ() >> 4));
                    var keys =
                            GroundStreaming.request(
                                    streaming.resident(),
                                    cursor++,
                                    cache::detailed,
                                    edited::contains);
                    if (keys.isEmpty()) return;
                    var tiles = new ArrayList<GroundViewPackets.Tile>();
                    for (long key : keys)
                        tiles.add(
                                new GroundViewPackets.Tile(
                                        key, cache.detailed(key) ? cache.version(key) : -1));
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new GroundViewPackets.Request(session, tiles));
                });
    }

    private static boolean samePlanet(net.minecraft.core.BlockPos pos) {
        return PlanetStorage.find(pos.getX(), pos.getZ()) == boundPlanet;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tickEntity(net.minecraft.world.level.block.entity.BlockEntity be) {
        var state = be.getBlockState();
        var ticker =
                state.getTicker(
                        level,
                        (net.minecraft.world.level.block.entity.BlockEntityType) be.getType());
        if (ticker != null) ticker.tick(level, be.getBlockPos(), state, be);
    }

    /** 3x3 near coverage each refresh, then round-robin the larger ring, transported over seams. */
    public static List<Long> plan(PlanetShape.Pose pose, int cursor) {
        return plan(PlanetShape.STANDARD, pose, cursor);
    }

    static List<Long> plan(PlanetShape shape, PlanetShape.Pose pose, int cursor) {
        var center = GroundTopology.feet(pose);
        var near = new LinkedHashSet<Long>();
        var ring = new LinkedHashSet<Long>();
        for (int r = 0; r <= 4; r++)
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++)
                    if (Math.max(Math.abs(x), Math.abs(z)) == r) {
                        var address =
                                GroundTopology.address(
                                        shape,
                                        new net.minecraft.core.BlockPos(
                                                (int) Math.floor(center.x() / 16) * 16 + x * 16,
                                                GroundCoordinates.FLOOR,
                                                (int) Math.floor(center.z() / 16) * 16 + z * 16));
                        var cell = GroundTopology.cell(address.owner());
                        long key =
                                cell.u() / 16
                                        + 128L * (cell.v() / 16 + 128L * cell.face().ordinal());
                        if (r <= 1) near.add(key);
                        else ring.add(key);
                    }
        var out = new LinkedHashSet<>(near);
        var rest = new ArrayList<>(ring);
        for (int i = 0; i < rest.size() && out.size() < 16; i++)
            out.add(rest.get(Math.floorMod((long) cursor * 7 + i, rest.size())));
        return List.copyOf(out);
    }

    private GroundViewClient() {}
}
