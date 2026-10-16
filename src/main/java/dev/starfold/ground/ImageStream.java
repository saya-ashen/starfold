package dev.starfold.ground;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartOrientation;
import dev.starfold.mixin.BlockEntityPacketAccess;
import dev.starfold.mixin.ChunkPacketAccess;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.probe.RelightProbe;
import dev.starfold.sky.Space;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/** Vanilla chunk packets for everything an observer can see, in the observer's continuous client
 * coordinates (ObserverFrame). Each client chunk shows its canonical owner rotated into the plane,
 * whichever face it lives on, so the vanilla mesher, loading order, picking and collision see
 * ordinary contiguous terrain across face seams. Only wire/client caches are duplicated: owners
 * remain the sole saved and simulated chunks, and nothing here writes to the world. */
public final class ImageStream {
    /** One column of an image chunk: where its blocks really live and how they turn into the plane. */
    private record Column(LevelChunk chunk, int x, int z, int turns) {}

    /** What an image shows, without touching chunks: owner column and rotation at five probes. */
    private record Plan(long[] owners, long signature) {}

    private static final class View {
        final ServerLevel level;
        ChunkPos center;
        int radius = -1;
        ObserverFrame frame;
        final Map<Long, Plan> sent = new HashMap<>();
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final RelightQueue light = new RelightQueue();
        Set<Long> owners = Set.of();
        final Map<Long, byte[]> probeLight = new HashMap<>();

        View(ServerLevel level) {
            this.level = level;
        }
    }

    private static final Map<ServerPlayer, View> VIEWS = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<BlockPos>> DIRTY = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<Long>> LIT = new WeakHashMap<>();
    public static final int CHUNKS_PER_TICK = 24;
    public static final long BUDGET_NS = 6_000_000;
    static final long RELIGHT_BUDGET_NS = BUDGET_NS / 2;

    public static boolean active(ServerPlayer player) {
        return PlanetLevels.is(player.level());
    }

    public static void clear() {
        VIEWS.clear();
        DIRTY.clear();
        LIT.clear();
    }

    /** A radius within which there are no positions at all. */
    public static final int ALOFT = -2;

    public static int radius(ServerPlayer player) {
        return Mth.clamp(
                player.requestedViewDistance(),
                2,
                player.serverLevel().getServer().getPlayerList().getViewDistance());
    }

    private static ChunkPos center(ServerPlayer player, ObserverFrame frame) {
        var c = frame.toClient(new Point(player.getX(), player.getY(), player.getZ()));
        return new ChunkPos((int) Math.floor(c.x()) >> 4, (int) Math.floor(c.z()) >> 4);
    }

    /** Canonical owner chunks this level must keep loaded for its observers' charts. */
    public static Set<Long> owners(ServerLevel level) {
        var out = new HashSet<Long>();
        for (var e : VIEWS.entrySet()) {
            var view = e.getValue();
            if (view.level == level && !e.getKey().isRemoved() && !Space.in(e.getKey()))
                out.addAll(view.owners);
        }
        return out;
    }

    /** Same shape vanilla sends: the view distance plus the outer ring its mesher needs, nearest first. */
    public static List<Long> positions(ChunkPos center, int radius) {
        var out = new ArrayList<Long>();
        for (int dx = -radius - 1; dx <= radius + 1; dx++)
            for (int dz = -radius - 1; dz <= radius + 1; dz++)
                if (ChunkTrackingView.isWithinDistance(
                        center.x, center.z, radius, center.x + dx, center.z + dz, true))
                    out.add(ChunkPos.asLong(center.x + dx, center.z + dz));
        out.sort(
                Comparator.comparingInt(
                        p -> {
                            int dx = ChunkPos.getX(p) - center.x, dz = ChunkPos.getZ(p) - center.z;
                            return dx * dx + dz * dz;
                        }));
        return out;
    }

    /** The same bounded window, clipped to the reserved region; never a chart image. */
    public static List<Long> regionPositions(ChunkPos center, int radius) {
        return positions(center, radius).stream()
                .filter(
                        p ->
                                Space.holds(ChunkPos.getX(p) * 16, ChunkPos.getZ(p) * 16)
                                        && Space.holds(
                                                ChunkPos.getX(p) * 16 + 15,
                                                ChunkPos.getZ(p) * 16 + 15))
                .toList();
    }

    /** Owner block and plane rotation of one client column. Outside every chart the storage chunk shows itself. */
    private static long[] resolve(PlanetAccess access, ObserverFrame frame, int x, int z) {
        var storage = frame.toStorage(new BlockPos(x, GroundCoordinates.FLOOR, z));
        try {
            if (access.inChart(storage)) {
                var address = access.address(storage);
                return new long[] {
                    address.owner().getX(),
                    address.owner().getZ(),
                    Math.floorMod(frame.turns() - address.turns(), 4)
                };
            }
        } catch (IllegalArgumentException outside) {
        }
        return new long[] {storage.getX(), storage.getZ(), frame.turns()};
    }

    private static Plan plan(PlanetAccess access, ObserverFrame frame, ChunkPos image) {
        long signature = 1;
        var owners = new LinkedHashSet<Long>();
        for (int[] probe : new int[][] {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}}) {
            var r =
                    resolve(
                            access,
                            frame,
                            image.getMinBlockX() + probe[0],
                            image.getMinBlockZ() + probe[1]);
            signature = signature * 1_000_003 + r[0];
            signature = signature * 1_000_003 + r[1];
            signature = signature * 31 + r[2];
            owners.add(ChunkPos.asLong((int) r[0] >> 4, (int) r[1] >> 4));
        }
        return new Plan(owners.stream().mapToLong(Long::longValue).toArray(), signature);
    }

    /** A real block changed: forward it to every image (the vanilla storage-keyed broadcast is dropped). */
    public static void changed(ServerLevel level, BlockPos owner) {
        DIRTY.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(owner.immutable());
        RelightProbe.changed(level, owner);
    }

    /** Server light of an owner chunk changed. */
    public static void lit(ServerLevel level, ChunkPos owner) {
        LIT.computeIfAbsent(level, l -> new HashSet<>()).add(owner.toLong());
    }

    public static void tick(ServerLevel level) {
        long start = ArrivalProbe.start();
        try {
            tickMeasured(level);
        } finally {
            ArrivalProbe.end("image", start);
        }
    }

    private static void tickMeasured(ServerLevel level) {
        VIEWS.entrySet()
                .removeIf(
                        e ->
                                e.getKey().isRemoved()
                                        || e.getKey().level() != e.getValue().level
                                        || !active(e.getKey()));
        var dirty = DIRTY.remove(level);
        var lit = LIT.remove(level);
        long now = level.getGameTime();
        var access = PlanetAccess.of(level);
        for (var player : level.players()) {
            if (!active(player)) continue;
            var view = VIEWS.computeIfAbsent(player, p -> new View(level));
            var frame = ObserverFrames.get(player);
            var center = center(player, frame);
            // A vessel far above a planet is shown none of its chunks: its place over the ground
            // sweeps whole faces as it
            // passes, and every chunk shown is one loaded and, the first time, made. What it sees
            // is the far view.
            int radius = GroundHost.get().aloft(player) ? ALOFT : radius(player);
            boolean reframed = !frame.equals(view.frame);
            if (reframed || !center.equals(view.center) || radius != view.radius) {
                if (!center.equals(view.center))
                    PacketMap.raw(
                            player, new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
                long windowStart = ArrivalProbe.start();
                view.center = center;
                view.radius = radius;
                view.frame = frame;
                var wanted =
                        Space.in(player)
                                ? regionPositions(center, radius)
                                : positions(center, radius);
                var set = new HashSet<>(wanted);
                for (var it = view.sent.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    if (!set.contains(e.getKey())) {
                        PacketMap.raw(
                                player,
                                new ClientboundForgetLevelChunkPacket(new ChunkPos(e.getKey())));
                        view.light.remove(e.getKey());
                        it.remove();
                    }
                    // A re-rooted frame keeps every plane position that still shows the same owner
                    // (all but a corner wedge).
                    else if (reframed
                            && plan(access, frame, new ChunkPos(e.getKey())).signature
                                    != e.getValue().signature) it.remove();
                }
                view.pending.clear();
                for (long image : wanted)
                    if (!view.sent.containsKey(image)) view.pending.add(image);
                var owners = new HashSet<Long>();
                for (long image : wanted)
                    for (long owner : plan(access, frame, new ChunkPos(image)).owners) {
                        long key = access.key(ChunkPos.getX(owner), ChunkPos.getZ(owner));
                        if (key >= 0) owners.add(key);
                    }
                view.owners = owners;
                ArrivalProbe.end("imageWindow", windowStart);
            }
            // Forget/reframe drops old image work; a newly sent chunk already includes its current
            // light.
            view.light.retain(view.sent.keySet());
            view.probeLight.keySet().retainAll(view.sent.keySet());
            if (dirty != null) for (var owner : dirty) forward(level, player, view, owner, now);
            if (lit != null)
                for (var e : view.sent.entrySet())
                    for (long owner : e.getValue().owners)
                        if (lit.contains(owner)) {
                            view.light.mark(e.getKey(), now, now);
                            break;
                        }
            long start = System.nanoTime(), deadline = start + BUDGET_NS;
            int queued = view.light.size();
            // Reserve half for lighting so new chunks cannot starve it; unused time goes to new
            // chunks.
            int processed =
                    view.light.drain(
                            now,
                            image -> {
                                double dx = ChunkPos.getX(image) - center.x,
                                        dz = ChunkPos.getZ(image) - center.z;
                                return dx * dx + dz * dz;
                            },
                            System::nanoTime,
                            start + RELIGHT_BUDGET_NS,
                            CHUNKS_PER_TICK - 1,
                            image -> relight(level, player, frame, new ChunkPos(image)));
            long relightNanos = System.nanoTime() - start;
            int count = 0, attempts = processed;
            for (var it = view.pending.iterator();
                    it.hasNext()
                            && attempts < CHUNKS_PER_TICK
                            && (count == 0 || System.nanoTime() < deadline); ) {
                long image = it.next();
                attempts++;
                if (send(level, player, frame, new ChunkPos(image))) {
                    view.sent.put(image, plan(access, frame, new ChunkPos(image)));
                    it.remove();
                    count++;
                }
            }
            RelightProbe.stream(
                    player,
                    queued,
                    processed,
                    relightNanos,
                    view.sent.size(),
                    view.pending.size(),
                    view.light.size(),
                    dirty == null ? 0 : dirty.size(),
                    lit == null ? 0 : lit.size(),
                    RelightProbe.ENABLED ? view.light.oldestAge(now) : 0);
        }
    }

    private static Column[] columns(ServerLevel level, ObserverFrame frame, ChunkPos image) {
        var access = PlanetAccess.of(level);
        var out = new Column[256];
        LevelChunk last = null;
        long lastPos = Long.MIN_VALUE;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                var r = resolve(access, frame, image.getMinBlockX() + x, image.getMinBlockZ() + z);
                int ox = (int) r[0], oz = (int) r[1];
                long at = ChunkPos.asLong(ox >> 4, oz >> 4);
                // Unknown stays unsent: region tickets load the owner, this never generates it
                // synchronously.
                if (at != lastPos) {
                    last = level.getChunkSource().getChunkNow(ox >> 4, oz >> 4);
                    lastPos = at;
                }
                if (last == null) return null;
                out[x + 16 * z] = new Column(last, ox & 15, oz & 15, (int) r[2]);
            }
        return out;
    }

    private static boolean identity(Column[] columns) {
        for (int i = 0; i < 256; i++) {
            var c = columns[i];
            if (c.chunk != columns[0].chunk || c.turns != 0 || c.x != (i & 15) || c.z != i >> 4)
                return false;
        }
        return true;
    }

    /** @return false while any owner is not yet loaded. */
    private static boolean send(
            ServerLevel level, ServerPlayer player, ObserverFrame frame, ChunkPos image) {
        long start = ArrivalProbe.start();
        try {
            return sendMeasured(level, player, frame, image);
        } finally {
            ArrivalProbe.end("imageSend", start);
        }
    }

    private static boolean sendMeasured(
            ServerLevel level, ServerPlayer player, ObserverFrame frame, ChunkPos image) {
        var columns = columns(level, frame, image);
        if (columns == null) return false;
        if (identity(columns)) {
            var packet =
                    new ClientboundLevelChunkWithLightPacket(
                            columns[0].chunk, level.getLightEngine(), null, null);
            if (!columns[0].chunk.getPos().equals(image)) {
                var access = (ChunkPacketAccess) packet;
                access.starfold$coverX(image.x);
                access.starfold$coverZ(image.z);
            }
            if (RelightProbe.ENABLED) {
                var lightBuffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    packet.getLightData().write(lightBuffer);
                    rememberLight(player, image, lightBuffer, 0);
                } finally {
                    lightBuffer.release();
                }
            }
            PacketMap.raw(player, packet);
            return true;
        }
        var view = new LevelChunk(level, image);
        var rotated = new IdentityHashMap<BlockState, BlockState[]>();
        for (int s = 0; s < view.getSectionsCount(); s++) {
            var to = view.getSection(s);
            final int section = s;
            for (int i = 0; i < 256; i++) {
                var c = columns[i];
                var from = c.chunk.getSection(s);
                if (from.hasOnlyAir()) continue;
                for (int y = 0; y < 16; y++) {
                    var state = from.getBlockState(c.x, y, c.z);
                    if (state.isAir()) continue;
                    var images = rotated.computeIfAbsent(state, k -> new BlockState[4]);
                    if (images[c.turns] == null)
                        images[c.turns] = ChartOrientation.state(state, c.turns);
                    to.setBlockState(i & 15, y, i >> 4, images[c.turns], false);
                }
            }
            to.fillBiomesFromNoise(
                    (x, y, z, sampler) -> {
                        var c =
                                columns[
                                        Math.min(15, (x & 3) * 4 + 1)
                                                + 16 * Math.min(15, (z & 3) * 4 + 1)];
                        return c.chunk.getSection(section).getNoiseBiome(c.x >> 2, y & 3, c.z >> 2);
                    },
                    null,
                    image.x * 4,
                    0,
                    image.z * 4);
        }
        int bits = Mth.ceillog2(level.getHeight() + 1);
        for (var entry : columns[0].chunk.getHeightmaps()) {
            if (!entry.getKey().sendToClient()) continue;
            var heights = new SimpleBitStorage(bits, 256);
            for (int i = 0; i < 256; i++) {
                var c = columns[i];
                heights.set(
                        i,
                        c.chunk
                                        .getOrCreateHeightmapUnprimed(entry.getKey())
                                        .getFirstAvailable(c.x, c.z)
                                - level.getMinBuildHeight());
            }
            view.setHeightmap(entry.getKey(), heights.getRaw());
        }
        var data =
                new ClientboundLevelChunkWithLightPacket(view, level.getLightEngine(), null, null)
                        .getChunkData();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            buffer.writeInt(image.x);
            buffer.writeInt(image.z);
            data.write(buffer);
            int lightStart = buffer.writerIndex();
            light(level, columns, buffer);
            rememberLight(player, image, buffer, lightStart);
            PacketMap.raw(player, ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        // Block entities are sent at their image position; the owner's instance is never moved or
        // cloned into simulation.
        for (int i = 0; i < 256; i++) {
            var c = columns[i];
            int x = image.getMinBlockX() + (i & 15), z = image.getMinBlockZ() + (i >> 4);
            for (var be : c.chunk.getBlockEntities().values())
                if ((be.getBlockPos().getX() & 15) == c.x && (be.getBlockPos().getZ() & 15) == c.z)
                    blockEntity(player, be, new BlockPos(x, be.getBlockPos().getY(), z));
        }
        return true;
    }

    private static void blockEntity(
            ServerPlayer player,
            net.minecraft.world.level.block.entity.BlockEntity be,
            BlockPos at) {
        var tag = be.getUpdateTag(player.registryAccess()).copy();
        if (tag.contains("x")) {
            tag.putInt("x", at.getX());
            tag.putInt("z", at.getZ());
        }
        PacketMap.raw(player, BlockEntityPacketAccess.starfold$create(at, be.getType(), tag));
    }

    /** The owners' real light, column by column. Vanilla light at the image position is the empty halo's. */
    private static void light(ServerLevel level, Column[] columns, FriendlyByteBuf buffer) {
        var engine = level.getLightEngine();
        int count = engine.getLightSectionCount(), min = engine.getMinLightSection();
        var masks = new BitSet[] {new BitSet(), new BitSet(), new BitSet(), new BitSet()};
        var updates = List.of(new ArrayList<byte[]>(), new ArrayList<byte[]>());
        var pos = new BlockPos.MutableBlockPos();
        for (int layer = 0; layer < 2; layer++) {
            var listener = engine.getLayerListener(layer == 0 ? LightLayer.SKY : LightLayer.BLOCK);
            for (int s = 0; s < count; s++) {
                var out = new DataLayer();
                boolean lit = false;
                var cache = new IdentityHashMap<LevelChunk, Optional<DataLayer>>();
                for (int i = 0; i < 256; i++) {
                    var c = columns[i];
                    final int section = min + s;
                    var source =
                            cache.computeIfAbsent(
                                            c.chunk,
                                            k ->
                                                    Optional.ofNullable(
                                                            listener.getDataLayerData(
                                                                    SectionPos.of(
                                                                            k.getPos(), section))))
                                    .orElse(null);
                    for (int y = 0; y < 16; y++) {
                        int value =
                                source != null
                                        ? source.get(c.x, y, c.z)
                                        : listener.getLightValue(
                                                pos.set(
                                                        c.chunk.getPos().getMinBlockX() + c.x,
                                                        SectionPos.sectionToBlockCoord(section) + y,
                                                        c.chunk.getPos().getMinBlockZ() + c.z));
                        if (value != 0) {
                            out.set(i & 15, y, i >> 4, value);
                            lit = true;
                        }
                    }
                }
                // An absent owner layer must replace any light the client still holds.
                lightSection(out, lit, s, masks[layer], masks[2 + layer], updates.get(layer));
            }
        }
        buffer.writeBitSet(masks[0]);
        buffer.writeBitSet(masks[1]);
        buffer.writeBitSet(masks[2]);
        buffer.writeBitSet(masks[3]);
        buffer.writeCollection(updates.get(0), (b, a) -> b.writeByteArray(a));
        buffer.writeCollection(updates.get(1), (b, a) -> b.writeByteArray(a));
    }

    static void lightSection(
            DataLayer data,
            boolean lit,
            int section,
            BitSet values,
            BitSet empty,
            List<byte[]> updates) {
        if (lit) {
            values.set(section);
            updates.add(data.getData());
        } else empty.set(section);
    }

    private static boolean relight(
            ServerLevel level, ServerPlayer player, ObserverFrame frame, ChunkPos image) {
        var columns = columns(level, frame, image);
        if (columns == null) return false;
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            buffer.writeVarInt(image.x);
            buffer.writeVarInt(image.z);
            int lightStart = buffer.writerIndex();
            light(level, columns, buffer);
            rememberLight(player, image, buffer, lightStart);
            PacketMap.raw(player, ClientboundLightUpdatePacket.STREAM_CODEC.decode(buffer));
            return true;
        } finally {
            buffer.release();
        }
    }

    private static void forward(
            ServerLevel level, ServerPlayer player, View view, BlockPos owner, long now) {
        var access = PlanetAccess.of(level);
        long chunk = ChunkPos.asLong(owner.getX() >> 4, owner.getZ() >> 4);
        for (var e : view.sent.entrySet()) {
            if (Arrays.stream(e.getValue().owners).noneMatch(o -> o == chunk)) continue;
            int x0 = ChunkPos.getX(e.getKey()) * 16, z0 = ChunkPos.getZ(e.getKey()) * 16;
            for (int i = 0; i < 256; i++) {
                var r = resolve(access, view.frame, x0 + (i & 15), z0 + (i >> 4));
                if (r[0] != owner.getX() || r[1] != owner.getZ()) continue;
                var at = new BlockPos(x0 + (i & 15), owner.getY(), z0 + (i >> 4));
                PacketMap.raw(
                        player,
                        new ClientboundBlockUpdatePacket(
                                at,
                                ChartOrientation.state(level.getBlockState(owner), (int) r[2])));
                var be = level.getBlockEntity(owner);
                if (be != null) blockEntity(player, be, at);
                // Server light settles asynchronously after the block change.
                view.light.mark(e.getKey(), now + 4, now);
                break;
            }
        }
    }

    private static void rememberLight(
            ServerPlayer player, ChunkPos image, FriendlyByteBuf buffer, int start) {
        if (!RelightProbe.ENABLED) return;
        var bytes = new byte[buffer.writerIndex() - start];
        buffer.getBytes(start, bytes);
        VIEWS.get(player).probeLight.put(image.toLong(), bytes);
    }

    /** Probe-only, explicit settled-state comparison of emitted light against the unchanged unbudgeted sampler. */
    public static void auditLight(ServerPlayer player) {
        if (!RelightProbe.ENABLED) return;
        var view = VIEWS.get(player);
        var log = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        if (view == null || !view.pending.isEmpty() || view.light.size() != 0) {
            log.info(
                    "PROBE RELIGHT_AUDIT NOT_QUIET pending {} light {}",
                    view == null ? -1 : view.pending.size(),
                    view == null ? -1 : view.light.size());
            return;
        }
        int checked = 0, mismatches = 0, equivalentEncodings = 0;
        long start = System.nanoTime();
        for (long image : positions(view.center, view.radius)) {
            var expected = view.probeLight.get(image);
            if (expected == null) continue;
            var cols = columns(view.level, view.frame, new ChunkPos(image));
            if (cols == null) continue;
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                light(view.level, cols, buffer);
                var actual = new byte[buffer.writerIndex()];
                buffer.getBytes(0, actual);
                if (!Arrays.equals(expected, actual)) {
                    if (LightPayload.same(expected, actual)) equivalentEncodings++;
                    else {
                        mismatches++;
                        RelightProbe.mismatch(
                                PlanetSystem.planet(player),
                                image,
                                identity(cols),
                                expected,
                                actual);
                    }
                }
                checked++;
            } finally {
                buffer.release();
            }
        }
        log.info(
                "PROBE RELIGHT_AUDIT planet {} checked {} mismatches {} pending {} light {} ns {} equivalentEncodings {}",
                PlanetSystem.planet(player),
                checked,
                mismatches,
                view.pending.size(),
                view.light.size(),
                System.nanoTime() - start,
                equivalentEncodings);
    }

    private ImageStream() {}
}
