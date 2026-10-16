package dev.starfold.ground;

import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side seam topology at the chunk-lookup layer.
 * A chunk coordinate in a face chart's halo is a live, rotated view ({@link GhostChunk}) of the canonical
 * owner chunk(s) on the neighbouring face. Code that looks chunks up directly instead of going through
 * Level.getBlockState/setBlock (pathfinding regions, block collisions, the light engine, block entity
 * registration, biome lookups, other mods) therefore sees one continuous surface across face seams.
 * Views own nothing: no sections, ticks, entities or saves; every read and write goes to the owner. */
public final class ChunkViews {
    public static final int NONE = Integer.MIN_VALUE;

    /** Owner block column (storage coordinates), owner cell u/v and quarter turns for each local column. */
    public record Columns(int[] x, int[] z, int[] u, int[] v, byte[] turns, long[] owners) {
        public int index(int x, int z) {
            return (x & 15) + 16 * (z & 15);
        }

        public boolean present(int i) {
            return x[i] != NONE;
        }
    }

    private static final int CACHE_LIMIT = 8192;
    private static final ConcurrentHashMap<Long, Columns> COLUMNS = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, ConcurrentHashMap<Long, GhostChunk>> VIEWS =
            new WeakHashMap<>();

    /** Inside a face chart (owner or halo), mirroring {@link PlanetAccess#inChart}. No exceptions: this runs in
     *  the light engine. */
    static boolean inChart(PlanetAccess access, int x, int z) {
        return access.inChart(new BlockPos(x, GroundCoordinates.FLOOR, z));
    }

    /** A halo chunk of some face chart: not stored itself, but every column has a canonical owner. */
    public static boolean ghost(PlanetAccess access, int chunkX, int chunkZ) {
        return access.key(chunkX, chunkZ) < 0 && inChart(access, chunkX * 16 + 8, chunkZ * 16 + 8);
    }

    public static Columns columns(PlanetAccess access, int chunkX, int chunkZ) {
        var cache = COLUMNS;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        var hit = cache.get(key);
        if (hit != null) return hit;
        int[] xs = new int[256], zs = new int[256], us = new int[256], vs = new int[256];
        byte[] turns = new byte[256];
        var owners = new java.util.LinkedHashSet<Long>();
        for (int i = 0; i < 256; i++) {
            var query =
                    new BlockPos(
                            chunkX * 16 + (i & 15),
                            GroundCoordinates.FLOOR,
                            chunkZ * 16 + (i >> 4));
            try {
                var address = access.address(query);
                var owner = address.owner();
                var cell = access.cell(owner);
                xs[i] = owner.getX();
                zs[i] = owner.getZ();
                us[i] = cell.u();
                vs[i] = cell.v();
                turns[i] = (byte) address.turns();
                owners.add(ChunkPos.asLong(owner.getX() >> 4, owner.getZ() >> 4));
            } catch (RuntimeException unowned) {
                xs[i] = NONE;
                zs[i] = NONE;
            }
        }
        var columns =
                new Columns(
                        xs,
                        zs,
                        us,
                        vs,
                        turns,
                        owners.stream().mapToLong(Long::longValue).toArray());
        if (cache.size() > CACHE_LIMIT) cache.clear();
        cache.put(key, columns);
        return columns;
    }

    /** Ghost block position -> owner block position (light is a scalar: no rotation). Owned/unknown positions are returned unchanged. */
    public static long alias(PlanetAccess access, long pos) {
        int x = BlockPos.getX(pos), z = BlockPos.getZ(pos);
        if (!ghost(access, x >> 4, z >> 4)) return pos;
        var c = columns(access, x >> 4, z >> 4);
        int i = c.index(x, z);
        return c.present(i) ? BlockPos.asLong(c.x()[i], BlockPos.getY(pos), c.z()[i]) : pos;
    }

    /** Ghost section -> the owner section of its centre column. */
    public static long aliasSection(PlanetAccess access, long section) {
        int cx = SectionPos.x(section), cz = SectionPos.z(section);
        if (!ghost(access, cx, cz)) return section;
        var c = columns(access, cx, cz);
        int i = c.index(8, 8);
        return c.present(i)
                ? SectionPos.asLong(c.x()[i] >> 4, SectionPos.y(section), c.z()[i] >> 4)
                : section;
    }

    /** The live view for a ghost chunk; callers have checked {@link #ghost}. */
    public static GhostChunk view(ServerLevel level, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, GhostChunk> views;
        synchronized (VIEWS) {
            views = VIEWS.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        var view = views.get(key);
        if (view != null) return view;
        if (views.size() > CACHE_LIMIT) views.clear();
        return views.computeIfAbsent(
                key,
                k ->
                        new GhostChunk(
                                level,
                                new ChunkPos(chunkX, chunkZ),
                                columns(PlanetAccess.of(level), chunkX, chunkZ)));
    }

    /** Owner chunk for reads, from any thread (the light engine runs off the main thread). */
    static ChunkAccess read(ServerLevel level, int chunkX, int chunkZ) {
        var chunk = level.getChunkSource().getChunkForLighting(chunkX, chunkZ);
        return chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter
                ? imposter.getWrapped()
                : chunk instanceof ChunkAccess access ? access : null;
    }

    /** Owner chunk for writes: main thread, fully loaded. */
    static LevelChunk write(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }

    /** Every owner chunk of a ghost is available through {@code present}. */
    public static boolean ready(
            ServerLevel level, Columns columns, java.util.function.LongPredicate present) {
        return ready(columns, false, present, present);
    }

    /** Non-loading lookups must never invoke the loading path, even for ticketed, unfinished owners. */
    public static boolean ready(
            Columns columns,
            boolean load,
            java.util.function.LongPredicate loading,
            java.util.function.LongPredicate current) {
        var present = load ? loading : current;
        for (long owner : columns.owners()) if (!present.test(owner)) return false;
        return columns.owners().length > 0;
    }

    public static boolean ready(ServerLevel level, Columns columns) {
        return ready(
                level,
                columns,
                o -> read(level, ChunkPos.getX(o), ChunkPos.getZ(o)) instanceof LevelChunk);
    }

    private ChunkViews() {}
}
