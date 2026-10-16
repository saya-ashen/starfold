package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Derived coverage index. Native chunks alone own all blocks, inventories and tick data. */
public final class GroundSurfaceIndex extends SavedData {
    public static final String FILE = "starfold_native_surface_index_v1";
    private final BitSet changed = new BitSet(98304);
    private final Map<Long, GroundViewCache.Surface> surfaces = new HashMap<>();
    private final Set<Long> dirty = new LinkedHashSet<>();
    private long revision;
    private int planet;

    public GroundSurfaceIndex() {
        this(0);
    }

    GroundSurfaceIndex(int planet) {
        PlanetShape.valid(planet);
        this.planet = planet;
    }

    public static GroundSurfaceIndex get(ServerLevel level) {
        return get(level, 0);
    }

    public static GroundSurfaceIndex get(ServerLevel level, int id) {
        int owner = id;
        PlanetShape.valid(owner);
        return level.getDataStorage()
                .computeIfAbsent(
                        new Factory<>(
                                () -> scan(level, owner),
                                (tag, registry) -> {
                                    var index = load(tag, registry);
                                    index.planet = owner;
                                    return index;
                                },
                                null),
                        file(owner));
    }

    /** Each planet has an index of its own; the first two keep the names they had. */
    public static String file(int planet) {
        return planet == 0 ? FILE : planet == 1 ? FILE + "_planet_b" : FILE + "_planet_" + planet;
    }

    public static GroundSurfaceIndex at(ServerLevel level, int chunkX, int chunkZ) {
        return get(level, PlanetStorage.planet(chunkX * 16.0, chunkZ * 16.0));
    }

    /** A chunk of this index's planet as that planet numbers its own chunks. */
    private int localX(int chunkX) {
        return chunkX - PlanetStorage.offset(planet) / 16;
    }

    private int localZ(int chunkZ) {
        return chunkZ - PlanetStorage.offsetZ(planet) / 16;
    }

    /** Existing chunks may contain alpha.61 buildings. Conservatively index all allocated native owners. */
    static GroundSurfaceIndex scan(ServerLevel level) {
        return scan(level, 0);
    }

    static GroundSurfaceIndex scan(ServerLevel level, int planet) {
        var out = new GroundSurfaceIndex();
        out.planet = planet;
        var folder =
                DimensionType.getStorageFolder(
                                level.dimension(),
                                level.getServer().getWorldPath(LevelResource.ROOT))
                        .resolve("region");
        if (Files.isDirectory(folder))
            try (var paths = Files.list(folder)) {
                for (var path :
                        paths.filter(
                                        p ->
                                                p.getFileName()
                                                        .toString()
                                                        .matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                                .toList()) {
                    var name = path.getFileName().toString().split("\\.");
                    int rx = Integer.parseInt(name[1]), rz = Integer.parseInt(name[2]);
                    // A region file being made has no table of chunks yet, and so no chunk to
                    // index.
                    if (Files.size(path) < 4096) continue;
                    try (var in = new DataInputStream(Files.newInputStream(path))) {
                        for (int i = 0; i < 1024; i++) {
                            int offset = in.readInt();
                            if (offset == 0) continue;
                            long key =
                                    key(out.localX(rx * 32 + i % 32), out.localZ(rz * 32 + i / 32));
                            if (key >= 0) {
                                out.changed.set((int) key);
                                out.dirty.add(key);
                            }
                        }
                    }
                }
            } catch (IOException | NumberFormatException e) {
                throw new IllegalStateException(
                        "Native coverage region index cannot be read; no chunks changed", e);
            }
        out.setDirty();
        return out;
    }

    /** Compatibility entry point; addressing belongs to storage, not this derived index. */
    public static long key(int x, int z) {
        return dev.starfold.storage.ChunkKeys.local(x, z);
    }

    public void changed(LevelChunk chunk) {
        long key = key(localX(chunk.getPos().x), localZ(chunk.getPos().z));
        if (key < 0) return;
        if (!changed.get((int) key)) {
            changed.set((int) key);
            revision++;
        }
        dirty.add(key);
        setDirty();
    }

    public void mark(net.minecraft.core.BlockPos owner) {
        long key = key(localX(owner.getX() >> 4), localZ(owner.getZ() >> 4));
        if (key < 0) return;
        if (!changed.get((int) key)) {
            changed.set((int) key);
            revision++;
        }
        dirty.add(key);
        setDirty();
    }

    public BitSet coverage() {
        return (BitSet) changed.clone();
    }

    public long revision() {
        return revision;
    }

    public GroundViewCache.Surface surface(long key) {
        return surfaces.get(key);
    }

    public boolean dirty(long key) {
        return dirty.contains(key);
    }

    public void update(GroundChunkSnapshot image) {
        update(image, null);
    }

    public void update(GroundChunkSnapshot image, PlanetTerrain terrain) {
        long key = image.key();
        if (!changed.get((int) key)) return;
        if (terrain == null ? image.baseline() : terrain.matches(image)) {
            changed.clear((int) key);
            surfaces.remove(key);
        } else surfaces.put(key, GroundViewCache.surfaceImage(image));
        dirty.remove(key);
        revision++;
        setDirty();
    }

    /** Surface-only refresh: 256 actual columns, no full-volume light snapshot or remote loads. */
    public void tick(ServerLevel level, long epoch) {
        if (dirty.isEmpty() || epoch % 10 != 0) return;
        int probes = 0;
        for (long key : List.copyOf(dirty)) {
            if (probes++ == 16) break;
            int face = (int) (key / 16384), u = (int) (key % 128), v = (int) (key / 128 % 128);
            var chunk =
                    level.getChunkSource().getChunkNow(face * 256 + u - localX(0), v - localZ(0));
            if (chunk == null) {
                dirty.remove(key);
                dirty.add(key);
                continue;
            }
            int[] heights = new int[256], states = new int[256];
            var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    int i = x + 16 * z,
                            y =
                                    chunk.getHeight(
                                            net.minecraft.world.level.levelgen.Heightmap.Types
                                                    .WORLD_SURFACE,
                                            x,
                                            z);
                    heights[i] = y;
                    states[i] =
                            net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(
                                    y < chunk.getMinBuildHeight()
                                            ? net.minecraft.world.level.block.Blocks.AIR
                                                    .defaultBlockState()
                                            : chunk.getBlockState(
                                                    pos.set(
                                                            chunk.getPos().getMinBlockX() + x,
                                                            y,
                                                            chunk.getPos().getMinBlockZ() + z)));
                }
            var prior = surfaces.get(key);
            if (prior == null
                    || !Arrays.equals(prior.heights(), heights)
                    || !Arrays.equals(prior.states(), states)) {
                surfaces.put(key, new GroundViewCache.Surface(epoch, heights, states));
                revision++;
                setDirty();
            }
            dirty.remove(key);
            break;
        }
    }

    public static GroundSurfaceIndex load(CompoundTag tag, HolderLookup.Provider registry) {
        if (!tag.contains("format", 3)
                || tag.getInt("format") != 1
                || !tag.contains("coverage", 12)
                || !tag.contains("revision", 4)
                || !tag.contains("surfaces", 9))
            throw new IllegalArgumentException("Native coverage index format");
        var out = new GroundSurfaceIndex();
        out.revision = tag.getLong("revision");
        if (out.revision < 0) throw new IllegalArgumentException("Coverage revision");
        out.changed.or(BitSet.valueOf(tag.getLongArray("coverage")));
        if (out.changed.length() > 98304) throw new IllegalArgumentException("Coverage keys");
        for (int key = out.changed.nextSetBit(0); key >= 0; key = out.changed.nextSetBit(key + 1))
            out.dirty.add((long) key);
        var list = tag.getList("surfaces", 10);
        if (list.size() > 98304) throw new IllegalArgumentException("Coverage count");
        for (var raw : list) {
            var s = (CompoundTag) raw;
            if (!s.contains("key", 4)
                    || !s.contains("epoch", 4)
                    || !s.contains("heights", 11)
                    || !s.contains("states", 11))
                throw new IllegalArgumentException("Coverage surface fields");
            long key = s.getLong("key");
            if (key < 0
                    || key >= 98304
                    || !out.changed.get((int) key)
                    || out.surfaces.containsKey(key))
                throw new IllegalArgumentException("Coverage owner");
            var surface =
                    new GroundViewCache.Surface(
                            s.getLong("epoch"), s.getIntArray("heights"), s.getIntArray("states"));
            out.surfaces.put(key, surface);
        }
        // Revalidate allocated chunks on first observation after reload. A derived cache is not
        // authority.
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registry) {
        tag.putInt("format", 1);
        tag.putLong("revision", revision);
        tag.putLongArray("coverage", changed.toLongArray());
        var list = new ListTag();
        for (var e : surfaces.entrySet()) {
            var s = new CompoundTag();
            s.putLong("key", e.getKey());
            s.putLong("epoch", e.getValue().revision());
            s.putIntArray("heights", e.getValue().heights());
            s.putIntArray("states", e.getValue().states());
            list.add(s);
        }
        tag.put("surfaces", list);
        return tag;
    }
}
