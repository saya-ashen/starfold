package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Persistent edit-only visible surfaces. Never loads or indexes unedited generated chunks. */
public final class GroundSurfaceIndex extends SavedData {
    public static final String FILE = "starfold_native_surface_overlay_v5";
    static final int FORMAT=5, SURFACE_FORM=2;
    private dev.starfold.worldgen.surface.SurfaceFunction function;
    private dev.starfold.universe.PlanetChunkGenerator generator;
    private final Map<Long,ChunkAccess> awaitingPlan=new LinkedHashMap<>();
    private final Map<Long,Long> removals=new HashMap<>();
    static final int READS_PER_TICK = 8192;
    private static final Map<ServerLevel, Map<Integer, GroundSurfaceIndex>> active =
            new HashMap<>();
    private final BitSet generated = new BitSet(98304);

    private record Packed(long revision, byte[] columns) {}

    private final Map<Long, Packed> surfaces = new HashMap<>();
    private final NavigableMap<Long, Long> recent = new TreeMap<>();
    // Retain an unloading chunk only until its queued top scan completes; no tickets or reloads.
    private final Map<Long, GroundSurfaceCapture> pending = new LinkedHashMap<>();
    private final Set<Long> dirty = new LinkedHashSet<>(), urgent = new LinkedHashSet<>(),
            prominent = new LinkedHashSet<>();
    private long revision;
    private long identity = java.util.concurrent.ThreadLocalRandom.current().nextLong();

    long identity() { return identity; }
    private int planet;
    private final java.util.concurrent.atomic.AtomicBoolean saveFailed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private java.util.concurrent.CompletableFuture<GroundSurfaceIndex> loading;
    private GroundSurfaceIndex restoring;
    private Iterator<Map.Entry<Long, Packed>> restoreSurfaces;
    private Iterator<Long> restoreDirty;
    private boolean loadFailed;

    public GroundSurfaceIndex() {
        this(0);
    }

    GroundSurfaceIndex(int planet) {
        PlanetShape.valid(planet);
        this.planet = planet;
        setDirty();
    }

    public static void initialize() {
        // Generation load/save events deliberately do not register surfaces.
        // Final level save flushes any outstanding edited-surface work.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Save event) -> {
                    if (event.getLevel() instanceof ServerLevel level
                            && level.getServer().isStopped()) {
                        active.getOrDefault(level, Map.of())
                                .values()
                                .forEach(GroundSurfaceIndex::finishPending);
                        level.getDataStorage().save();
                    }
                });
        // A normal stop must save edited chunks whose scans were still queued. This is shutdown,
        // not tick work; Minecraft's final world save follows ServerStoppingEvent.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent event) -> {
                    active.forEach(
                            (level, indices) -> {
                                if (level.getServer() == event.getServer())
                                    indices.values().forEach(GroundSurfaceIndex::finishPending);
                            });
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                        active.keySet().removeIf(level -> level.getServer() == event.getServer()));
    }

    public static GroundSurfaceIndex get(ServerLevel level) {
        return get(level, 0);
    }

    public static GroundSurfaceIndex get(ServerLevel level, int id) {
        PlanetShape.valid(id);
        return active.computeIfAbsent(level, l -> new HashMap<>())
                .computeIfAbsent(
                        id,
                        owner -> {
                            var index = new GroundSurfaceIndex(owner);
                            var source=level.getChunkSource().getGenerator();
                            var generator=source instanceof dev.starfold.universe.SystemChunkGenerator s ? s.planet(owner)
                                    : source instanceof dev.starfold.universe.PlanetChunkGenerator g ? g : null;
                            index.generator=generator;
                            // Vanilla computeIfAbsent reads/decompresses SavedData synchronously.
                            // Register
                            // this owner now; read the one current-format file off the tick thread.
                            level.getDataStorage().set(file(owner), index);
                            var folder =
                                    net.minecraft.world.level.dimension.DimensionType
                                            .getStorageFolder(
                                                    level.dimension(),
                                                    level.getServer()
                                                            .getWorldPath(
                                                                    net.minecraft.world.level
                                                                            .storage.LevelResource
                                                                            .ROOT));
                            index.restoreFrom(folder.resolve("data").resolve(file(owner) + ".dat"));
                            return index;
                        });
    }

    void restoreFrom(java.nio.file.Path file) {
        loading =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            if (!java.nio.file.Files.exists(file)) return null;
                            try {
                                var root =
                                        NbtIo.readCompressed(
                                                file, NbtAccounter.create(512L * 1024 * 1024));
                                return load(root.getCompound("data"), null);
                            } catch (java.io.IOException e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        },
                        net.minecraft.Util.ioPool());
    }

    boolean ready() {
        return loading == null && restoring == null;
    }

    /** At most limit records are attached on the owner thread; never waits for disk or a future. */
    int restore(int limit, long deadline) {
        if (loading != null) {
            if (!loading.isDone()) return 0;
            try {
                restoring = loading.join();
            } catch (java.util.concurrent.CompletionException e) {
                loadFailed = true;
                org.slf4j.LoggerFactory.getLogger(GroundSurfaceIndex.class)
                        .error("Cannot read generated surface index; preserving existing file", e);
            }
            loading = null;
            if (restoring == null) return 0;
            identity = restoring.identity;
            var old=(BitSet)restoring.generated.clone();
            for(long key:removals.keySet()) old.clear((int)key);
            generated.or(old);
            restoreSurfaces = restoring.surfaces.entrySet().iterator();
            restoreDirty = restoring.dirty.iterator();
        }
        int attached = 0;
        if (restoring != null) {
            while (restoreSurfaces.hasNext() && attached < limit && System.nanoTime() < deadline) {
                var entry = restoreSurfaces.next();
                if(!removals.containsKey(entry.getKey())) surfaces.putIfAbsent(entry.getKey(), entry.getValue());
                attached++;
            }
            while (!restoreSurfaces.hasNext()
                    && restoreDirty.hasNext()
                    && attached < limit
                    && System.nanoTime() < deadline) {
                long key = restoreDirty.next();
                if (!surfaces.containsKey(key) && !removals.containsKey(key)) dirty.add(key);
                attached++;
            }
            if (!restoreSurfaces.hasNext() && !restoreDirty.hasNext()) {
                revision = Math.max(revision, restoring.revision) + 1;
                restoring = null;
                restoreSurfaces = null;
                restoreDirty = null;
                setDirty();
            }
        }
        return attached;
    }

    public static String file(int planet) {
        return planet == 0 ? FILE : planet == 1 ? FILE + "_planet_b" : FILE + "_planet_" + planet;
    }

    public static GroundSurfaceIndex at(ServerLevel level, int chunkX, int chunkZ) {
        return get(level, PlanetStorage.planet(chunkX * 16.0, chunkZ * 16.0));
    }

    static Set<Integer> pendingPlanets(ServerLevel level) {
        var result = new HashSet<Integer>();
        active.getOrDefault(level, Map.of())
                .forEach(
                        (id, index) -> {
                            if (!index.ready() || !index.pending.isEmpty()) result.add(id);
                        });
        return result;
    }

    public static long key(int x, int z) {
        return dev.starfold.storage.ChunkKeys.local(x, z);
    }

    private long chunkKey(ChunkAccess chunk) {
        return dev.starfold.storage.ChunkKeys.local(
                chunk.getPos().x - PlanetStorage.offset(planet) / 16,
                chunk.getPos().z - PlanetStorage.offsetZ(planet) / 16,
                PlanetShape.of(planet).chunks());
    }

    /** A reload can resume an already marked edit, but never registers generated terrain. */
    public void loaded(ChunkAccess chunk) {
        long key=chunkKey(chunk);
        if(key>=0 && dirty.contains(key)) edited(chunk);
    }
    void baseline(dev.starfold.worldgen.surface.SurfaceFunction function) { this.function=function; }
    private boolean baselineReady() {
        if(generator==null || function!=null)return true;
        if(!generator.planReady())return false;
        function=generator.terrain().readySurface();
        return function!=null;
    }
    void edited(ChunkAccess chunk) {
        long key=chunkKey(chunk); if(key<0) return;
        register(key);
        if(!baselineReady()) { awaitingPlan.put(key,chunk);return; }
        awaitingPlan.remove(key);
        pending.compute(key,(k,prior)->prior==null ? new GroundSurfaceCapture(chunk,function,key) : prior.changed(chunk));
        urgent.add(key);
    }
    public void changed(ChunkAccess chunk,net.minecraft.core.BlockPos pos) {
        long key=chunkKey(chunk);
        // Large vertical departures have the greatest silhouette error, even outside native range.
        if(key>=0 && function!=null && pos.getY()>function.groundTop(
                dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[(int)(key/16384)],
                (int)(key%128)*16+(pos.getX()&15),(int)(key/128%128)*16+(pos.getZ()&15))+32)
            prominent.add(key);
        edited(chunk);
    }

    private void register(long key) {
        if (!generated.get((int) key)) {
            generated.set((int) key);
            revision++;
        }
        dirty.add(key);
        setDirty();
    }

    public void mark(net.minecraft.core.BlockPos owner) {
        long key =
                key(
                        (owner.getX() - PlanetStorage.offset(planet)) >> 4,
                        (owner.getZ() - PlanetStorage.offsetZ(planet)) >> 4);
        if (key < 0) return;
        register(key);
        urgent.add(key);
        pending.remove(key);
    }

    public BitSet coverage() {
        return (BitSet) generated.clone();
    }

    public long revision() {
        return revision;
    }

    void changesSince(long after, java.util.function.LongConsumer changed) {
        recent.tailMap(after, false).values().forEach(changed::accept);
    }

    public long surfaceRevision(long key) {
        var image = surfaces.get(key);
        return !ready() ? -1 : image == null ? removals.getOrDefault(key,-1L) : image.revision;
    }

    public GroundSurface surface(long key) {
        var image = surfaces.get(key);
        return !ready() || image == null
                ? null
                : GroundSurfaceCodec.unpack(image.revision, image.columns);
    }

    public boolean dirty(long key) {
        return dirty.contains(key);
    }

    BitSet unknown() {
        var out = new BitSet();
        for (long key : dirty) out.set((int) key);
        return out;
    }

    public void update(GroundChunkSnapshot image) {
        register(image.key());
        commit(image.key(), GroundSurface.from(image));
    }

    private void commit(long key, GroundSurface surface) {
        byte[] columns = GroundSurfaceCodec.pack(surface);
        surfaces.put(key, new Packed(++revision, columns));
        removals.remove(key);
        recent.put(revision, key);
        while (recent.size() > 2048) recent.pollFirstEntry();
        setDirty();
        pending.remove(key);
        dirty.remove(key);
        urgent.remove(key); prominent.remove(key);
    }

    private void remove(long key) {
        surfaces.remove(key); generated.clear((int)key);
        removals.put(key,++revision); recent.put(revision,key);
        while(recent.size()>2048) recent.pollFirstEntry();
        pending.remove(key); dirty.remove(key); urgent.remove(key); prominent.remove(key); setDirty();
    }
    boolean hasSurface(long key) { return surfaces.containsKey(key); }

    static List<Long> candidates(Set<Long> urgent, Set<Long> dirty) {
        return candidates(urgent, dirty, List.of());
    }

    static List<Long> candidates(Set<Long> urgent, Set<Long> dirty, List<Long> observers) {
        return candidates(Set.of(),urgent,dirty,observers);
    }
    static List<Long> candidates(Set<Long> prominent,Set<Long> urgent,Set<Long> dirty,List<Long> observers) {
        var ordered = new LinkedHashSet<Long>();
        for(long key:prominent) {
            if(ordered.size()>=16) break;
            if(dirty.contains(key)) ordered.add(key);
        }
        for (long centre : observers) {
            int face = (int) (centre / 16384),
                    u = (int) (centre % 128),
                    v = (int) (centre / 128 % 128);
            for (int radius = 0; radius <= 2; radius++)
                for (int du = -radius; du <= radius; du++)
                    for (int dv = -radius; dv <= radius; dv++) {
                        if (Math.max(Math.abs(du), Math.abs(dv)) != radius
                                || u + du < 0
                                || u + du >= 128
                                || v + dv < 0
                                || v + dv >= 128) continue;
                        long key = face * 16384L + (u + du) + (v + dv) * 128L;
                        if (dirty.contains(key)) ordered.add(key);
                    }
        }
        // Only fill the bounded batch, not a planet-sized copy every tick.
        for (var set : List.of(urgent, dirty)) {
            for (long key : set) {
                if (ordered.size() >= 16) break;
                if (dirty.contains(key)) ordered.add(key);
            }
        }
        return ordered.stream().limit(16).toList();
    }

    public void tick(ServerLevel level, long epoch) {
        long deadline = System.nanoTime() + 2_000_000;
        restore(256, deadline);
        if(!baselineReady())return;
        for(var chunk:List.copyOf(awaitingPlan.values()))edited(chunk);
        awaitingPlan.clear();
        // Resume persisted dirty edits only if their native chunk is already loaded. Never request/generate one.
        for(long key:candidates(urgent,dirty)) {
            if(pending.containsKey(key)) continue;
            int x=PlanetStorage.offset(planet)/16+(int)(key/16384)*256+(int)(key%128);
            int z=PlanetStorage.offsetZ(planet)/16+(int)(key/128%128);
            var chunk=level.getChunkSource().getChunkNow(x,z);
            if(chunk!=null) loaded(chunk);
            else { dirty.remove(key); dirty.add(key); }
            if(System.nanoTime()>=deadline) return;
        }
        if (pending.isEmpty()) return;
        var observers =
                level.players().stream()
                        .map(p -> PlanetStorage.key(p.chunkPosition().x, p.chunkPosition().z))
                        .filter(k -> k >= 0 && k / 98304 == planet)
                        .map(k -> k % 98304)
                        .toList();
        advance(READS_PER_TICK, observers, deadline);
    }

    int advance(int reads, List<Long> observers, long deadline) {
        int left = reads;
        for (long key : candidates(prominent, urgent, pending.keySet(), observers)) {
            var capture = pending.get(key);
            while (!capture.done() && left > 0 && System.nanoTime() < deadline)
                left -= capture.step(Math.min(256, left));
            if (capture.done()) {
                boolean high=prominent.contains(key);
                if(capture.matchesBaseline()) remove(key); else commit(key,capture.result(0));
                // Publish bounded-stale coverage, then refresh; continuous random ticks must not
                // restart a full-height scan forever and starve buildings in the same chunk.
                if(capture.invalidated()) {
                    edited(capture.chunk());
                    if(high) prominent.add(key);
                }
            }
            if (left <= 0 || System.nanoTime() >= deadline) break;
        }
        return reads - left;
    }

    void finishPending() {
        // Shutdown only: finish the asynchronous read before the final SavedData snapshot.
        while (!ready()) {
            if (loading != null) loading.handle((result, failure) -> null).join();
            restore(256, Long.MAX_VALUE);
        }
        // If shutdown precedes planning, preserve edits as full snapshots without joining the plan.
        for(var entry:awaitingPlan.entrySet())
            pending.put(entry.getKey(),new GroundSurfaceCapture(entry.getValue(),null,entry.getKey()));
        awaitingPlan.clear();
        for (long key : List.copyOf(pending.keySet())) {
            var capture = pending.get(key);
            while (!capture.done()) capture.step(READS_PER_TICK);
            if(capture.invalidated()) {
                capture.restart(capture.chunk());
                while(!capture.done()) capture.step(READS_PER_TICK);
            }
            if(capture.matchesBaseline()) remove(key); else commit(key,capture.result(0));
        }
    }

    public static GroundSurfaceIndex load(CompoundTag tag, HolderLookup.Provider registry) {
        if (tag.getInt("format") != FORMAT || tag.getInt("surface_form") != SURFACE_FORM
                || tag.getInt("algorithm_version") != dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION
                || !tag.contains("coverage", 12)
                || !tag.contains("revision", 4)
                || !tag.contains("surfaces", 9))
            throw new IllegalArgumentException("Native surface index format");
        var out = new GroundSurfaceIndex();
        // Indexes saved before the client surface cache get their identity on first load.
        if (tag.contains("identity", 4)) out.identity = tag.getLong("identity");
        else out.setDirty();
        out.revision = tag.getLong("revision");
        if (out.revision < 0) throw new IllegalArgumentException("Surface revision");
        out.generated.or(BitSet.valueOf(tag.getLongArray("coverage")));
        if (out.generated.length() > 98304) throw new IllegalArgumentException("Coverage keys");
        var list = tag.getList("surfaces", 10);
        if (list.size() > 98304) throw new IllegalArgumentException("Surface count");
        for (var raw : list) {
            var s = (CompoundTag) raw;
            if (!s.contains("key", 4) || !s.contains("epoch", 4) || !s.contains("columns", 7))
                throw new IllegalArgumentException("Surface fields");
            long key = s.getLong("key"), epoch = s.getLong("epoch");
            if (key < 0
                    || key >= 98304
                    || !out.generated.get((int) key)
                    || out.surfaces.containsKey(key)
                    || epoch > out.revision)
                throw new IllegalArgumentException("Surface owner/revision");
            byte[] columns = s.getByteArray("columns").clone();
            GroundSurfaceCodec.unpack(epoch, columns);
            out.surfaces.put(key, new Packed(epoch, columns));
        }
        // Complete persisted surfaces can be served without loading their chunks again.
        for (int key = out.generated.nextSetBit(0);
                key >= 0;
                key = out.generated.nextSetBit(key + 1))
            if (!out.surfaces.containsKey((long) key)) out.dirty.add((long) key);
        return out;
    }

    @Override
    public boolean isDirty() {
        return ready() && !loadFailed && (saveFailed.get() || super.isDirty());
    }

    @Override
    public void save(java.io.File file, HolderLookup.Provider registry) {
        if (!isDirty()) return;
        // Packed records never mutate: copy references on the owner thread, not every column or
        // a planet-sized NBT tree. NBT construction/compression/I/O run on NeoForge's save worker.
        var images = new HashMap<>(surfaces);
        var coverage = coverage();
        long savedRevision = revision;
        saveFailed.set(false);
        setDirty(false);
        net.neoforged.neoforge.common.IOUtilities.withIOWorker(
                () -> {
                    var root = new CompoundTag();
                    root.put("data", snapshot(new CompoundTag(), identity, savedRevision, coverage, images));
                    NbtUtils.addCurrentDataVersion(root);
                    try {
                        net.neoforged.neoforge.common.IOUtilities.writeNbtCompressed(
                                root, file.toPath());
                    } catch (java.io.IOException e) {
                        saveFailed.set(true);
                        org.slf4j.LoggerFactory.getLogger(GroundSurfaceIndex.class)
                                .error("Cannot save generated surfaces to {}; will retry", file, e);
                    }
                });
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registry) {
        return snapshot(tag, identity, revision, generated, surfaces);
    }

    private static CompoundTag snapshot(
            CompoundTag tag, long identity, long revision, BitSet coverage, Map<Long, Packed> images) {
        tag.putInt("format", FORMAT);
        tag.putLong("identity", identity);
        tag.putInt("surface_form",SURFACE_FORM);
        tag.putInt("algorithm_version",dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION);
        tag.putLong("revision", revision);
        tag.putLongArray("coverage", coverage.toLongArray());
        var list = new ListTag();
        images.forEach(
                (key, image) -> {
                    var s = new CompoundTag();
                    s.putLong("key", key);
                    s.putLong("epoch", image.revision);
                    s.putByteArray("columns", image.columns.clone());
                    list.add(s);
                });
        tag.put("surfaces", list);
        return tag;
    }
}
