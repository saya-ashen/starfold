package dev.starfold.render;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.surface.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;

/** Client-only planet LRU with a shared, bounded pool of patch workers. */
public final class GroundBaseCache {
    /**
     * Non-blocking view of the renderer's shared coarse cache; missing patches stay unpublished.
     */
    public static dev.starfold.api.PlanetMapApi.Surface surface(PlanetTerrain terrain, int step) {
        var entry = get(terrain, null);
        var cache = entry.cache;
        int width = (terrain.shape().size() + step - 1) / step;
        return new dev.starfold.api.PlanetMapApi.Surface() {
            public int width() {
                return width;
            }

            public int step() {
                return step;
            }

            public double progress() {
                int count = 0, n = (terrain.shape().size() + 63) / 64;
                for (int f = 0; f < 6; f++)
                    for (int v = 0; v < n; v++)
                        for (int u = 0; u < n; u++) count += entry.ready.get(f * 1024 + u + v * 32);
                return (double) count / (6 * n * n);
            }

            public int waterDepth(int face, int u, int v) {
                var column =
                        cache == null
                                ? null
                                : cache.column(Face.VALUES[face], u * step, v * step, step);
                if (column == null || column.runs().isEmpty()) return -1;
                var top = column.runs().getLast();
                var fluid = top.block().getFluidState().getType();
                return fluid == net.minecraft.world.level.material.Fluids.WATER
                                || fluid == net.minecraft.world.level.material.Fluids.FLOWING_WATER
                        ? top.top() - top.bottom()
                        : -1;
            }

            public dev.starfold.api.PlanetMapApi.Cell cell(int face, int u, int v) {
                var column =
                        cache == null
                                ? null
                                : cache.column(Face.VALUES[face], u * step, v * step, step);
                if (column == null) return null;
                var runs = column.runs();
                var top = runs.isEmpty() ? null : runs.getLast();
                return new dev.starfold.api.PlanetMapApi.Cell(
                        top == null ? 0xff101820 : 0xff000000 | SurfaceColor.color(top.block()),
                        top == null ? -128 : top.top() - 128);
            }
        };
    }

    // A byte budget, not a planet count: a sky can hold ten small bodies, and a count below that
    // evicts and refills them in a loop.
    static final long BUDGET = 256L * 1024 * 1024;
    // Whole-planet background work must leave CPU for nearby chunks, even on a fresh cache.
    static final int THREADS =
            Math.clamp(
                    Integer.getInteger(
                            "starfold.surfaceThreads",
                            Math.min(
                                    2,
                                    Math.max(
                                            1,
                                            Runtime.getRuntime().availableProcessors() / 2 - 1))),
                    1,
                    256);
    private static boolean workersStarted;
    private static final List<Thread> workers = new CopyOnWriteArrayList<>();

    private static void wakeWorkers() {
        for (var worker : workers) if (worker != null) LockSupport.unpark(worker);
    }

    record Work(Entry entry, int patch) {}

    private static final ConcurrentHashMap<SurfaceKey, Entry> retiring = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Entry> cleanup = new ConcurrentLinkedQueue<>();

    private static void startWorkers() {
        if (workersStarted) return;
        workersStarted = true;
        for (int i = 0; i < THREADS; i++) {
            var thread = new Thread(GroundBaseCache::worker, "starfold-surface-" + i);
            workers.add(thread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Called under the cache monitor. No planet can queue work ahead of a new camera focus. */
    static Work claimWork() {
        var retired = cleanup.poll();
        if (retired != null) return new Work(retired, -2);
        Entry best = null;
        int patch = -1;
        for (var candidate : planets.values()) {
            if (candidate.state == Entry.State.CLOSED
                    || candidate.state == Entry.State.FAILED
                    || candidate.state == Entry.State.READY
                    || candidate.loading
                    || retiring.containsKey(candidate.key)) continue;
            // Equal priorities retain iteration order; only competitive planets scan patches.
            if (best != null && !(priority(candidate) < priority(best))) continue;
            int next = candidate.diskLoaded ? candidate.nextPatch() : -1;
            if (candidate.diskLoaded && next == -1) continue;
            best = candidate;
            patch = next;
        }
        if (best == null) return null;
        if (patch == -1) best.loading = true;
        else best.claimed.set(patch);
        best.state = Entry.State.BUILDING;
        best.running.add(Thread.currentThread());
        return new Work(best, patch);
    }

    private static double priority(Entry entry) {
        return entry.key.equals(observed) ? -1 : entry.cameraDistance;
    }

    private static void worker() {
        while (true) {
            Work work;
            synchronized (GroundBaseCache.class) {
                work = claimWork();
            }
            if (work == null) {
                LockSupport.park();
                // Entry cancellation must not retire a shared worker.
                Thread.interrupted();
                continue;
            }
            var entry = work.entry();
            Exception failure = null;
            try {
                final int patch = work.patch();
                if (patch != -2)
                    entry.retry(
                            () -> {
                                entry.work(patch);
                                return null;
                            });
            } catch (InterruptedException cancelled) {
                // close() already made the entry inaccessible; completed disk records survive.
            } catch (Exception failed) {
                failure = failed;
            } finally {
                boolean release;
                synchronized (GroundBaseCache.class) {
                    entry.running.remove(Thread.currentThread());
                    if (work.patch() == -1) entry.loading = false;
                    else if (work.patch() >= 0) entry.claimed.clear(work.patch());
                    synchronized (entry) {
                        if (entry.state != Entry.State.CLOSED) {
                            if (failure != null) {
                                entry.failure = failure;
                                entry.state = Entry.State.FAILED;
                            } else if (entry.state != Entry.State.FAILED) {
                                entry.state =
                                        entry.cache.complete()
                                                ? Entry.State.READY
                                                : entry.running.isEmpty()
                                                        ? Entry.State.QUEUED
                                                        : Entry.State.BUILDING;
                            }
                        }
                    }
                    release =
                            entry.running.isEmpty()
                                    && (entry.state == Entry.State.CLOSED
                                            || entry.state == Entry.State.READY
                                            || entry.state == Entry.State.FAILED);
                    Thread.interrupted();
                    wakeWorkers();
                }
                if (release) release(entry);
            }
        }
    }

    static void release(Entry entry) {
        entry.releaseDisk();
        // close() can race READY cleanup: keep replacement entries out until fine IO also closes.
        if (entry.fineDisk == null) retiring.remove(entry.key, entry);
        synchronized (GroundBaseCache.class) {
            wakeWorkers();
        }
    }

    private static SurfaceKey observed;

    static synchronized void observe(PlanetTerrain t) {
        observed = t == null ? null : SurfaceKey.of(t);
        wakeWorkers();
    }

    private static final LinkedHashMap<SurfaceKey, Entry> planets =
            new LinkedHashMap<>(4, .75f, true);

    // Only publication/retirement and scheduler claims need the class monitor. Hits read an
    // immutable registry snapshot and update an access stamp without contending with patch scans.
    private static volatile Map<SurfaceKey, Entry> lookup = Map.of();
    private static final Map<SurfaceKey, CompletableFuture<Entry>> creating = new HashMap<>();
    private static final AtomicLong accesses = new AtomicLong();

    static Entry get(PlanetTerrain terrain, Point camera) {
        return get(terrain, camera, Entry::new, true);
    }

    /** The production construction/publication path, with an offline factory/scheduler seam. */
    static Entry get(
            PlanetTerrain terrain,
            Point camera,
            Function<PlanetTerrain, Entry> factory,
            boolean schedule) {
        var key = SurfaceKey.of(terrain);
        var entry = lookup.get(key);
        if (entry != null && entry.state != Entry.State.CLOSED) return touch(entry, camera);
        CompletableFuture<Entry> flight;
        boolean owner;
        synchronized (GroundBaseCache.class) {
            entry = planets.get(key);
            if (entry != null) return touch(entry, camera);
            flight = creating.get(key);
            owner = flight == null;
            if (owner) {
                flight = new CompletableFuture<>();
                creating.put(key, flight);
            }
        }
        if (!owner) return touch(flight.join(), camera);
        Entry built = null;
        try {
            built = factory.apply(terrain);
            synchronized (GroundBaseCache.class) {
                // clear() removes and cancels the old flight. An old constructor must never
                // publish into a new connection, even if that connection requests the same key.
                if (creating.get(key) != flight) throw new CancellationException("Cache cleared");
                touch(built, camera);
                planets.put(key, built);
                evict(built, BUDGET);
                if (schedule) startWorkers();
                creating.remove(key);
                flight.complete(built);
                wakeWorkers();
            }
            return built;
        } catch (Throwable failure) {
            if (built != null) built.close();
            synchronized (GroundBaseCache.class) {
                creating.remove(key, flight);
                flight.completeExceptionally(failure);
            }
            return flight.join();
        }
    }

    private static Entry touch(Entry entry, Point camera) {
        entry.lastAccess.accumulateAndGet(accesses.incrementAndGet(), Math::max);
        entry.focus(camera);
        wakeWorkers();
        return entry;
    }

    /** Access stamps retain planet LRU without moving a linked map on every read. */
    static void evict(Entry protectedEntry, long budget) {
        while (retainedBytes(planets.values()) > budget) {
            Entry victim = null;
            for (var candidate : planets.values()) {
                if (candidate == protectedEntry || candidate.key.equals(observed)) continue;
                if (victim == null || candidate.lastAccess.get() < victim.lastAccess.get())
                    victim = candidate;
            }
            if (victim == null) break;
            planets.remove(victim.key);
            victim.close();
        }
        lookup = Collections.unmodifiableMap(new LinkedHashMap<>(planets));
    }

    static synchronized void clear() {
        if (Boolean.getBoolean("starfold.chunkPerf"))
            org.slf4j.LoggerFactory.getLogger(GroundBaseCache.class)
                    .info(
                            "PROBE PERF base-clear entries {} retained {}",
                            planets.size(),
                            retainedBytes(planets.values()),
                            new Exception("cache clear caller"));
        lookup = Map.of();
        planets.values().forEach(Entry::close);
        planets.clear();
        creating.values().forEach(f -> f.cancel(false));
        creating.clear();
        observed = null;
        wakeWorkers();
    }

    static long fineBytes() {
        return lookup.values().stream().mapToLong(Entry::fineBytes).sum();
    }

    static long retainedBytes() {
        return retainedBytes(lookup.values());
    }

    private static long retainedBytes(Collection<Entry> entries) {
        long bytes = 0;
        for (var entry : entries) {
            var cache = entry.cache;
            bytes += (cache == null ? 0 : cache.retainedBytes()) + entry.fineBytes();
        }
        return bytes;
    }

    /** Bounded packed columns. Each shard owns both its LRU and its per-key singleflight. */
    static final class FineCache {
        static final int SHARDS = 16;
        private final Shard[] shards = new Shard[SHARDS];
        private volatile boolean closed;

        private static final class Shard {
            final LinkedHashMap<Long, long[]> values = new LinkedHashMap<>(256, .75f, true);
            final Map<Long, CompletableFuture<long[]>> pending = new HashMap<>();
            final long limit;
            volatile long bytes;

            Shard(long limit) {
                this.limit = limit;
            }
        }

        FineCache(long limit) {
            if (limit < 0) throw new IllegalArgumentException("Negative fine budget");
            for (int i = 0; i < shards.length; i++)
                shards[i] = new Shard(limit / SHARDS + (i < limit % SHARDS ? 1 : 0));
        }

        private Shard shard(long key) {
            long hash = key;
            hash ^= hash >>> 33;
            hash *= 0xff51afd7ed558ccdL;
            hash ^= hash >>> 33;
            return shards[(int) hash & (SHARDS - 1)];
        }

        long[] get(long key, Supplier<long[]> compute) {
            var shard = shard(key);
            CompletableFuture<long[]> flight;
            boolean owner;
            synchronized (shard) {
                if (closed) throw new CancellationException("Fine cache closed");
                var value = shard.values.get(key);
                if (value != null) return value;
                flight = shard.pending.get(key);
                owner = flight == null;
                if (owner) {
                    flight = new CompletableFuture<>();
                    shard.pending.put(key, flight);
                }
            }
            if (!owner) return flight.join();
            try {
                var value = Objects.requireNonNull(compute.get());
                synchronized (shard) {
                    if (closed) throw new CancellationException("Fine cache closed");
                    long bytes = 80 + 8L * value.length;
                    if (bytes <= shard.limit) {
                        shard.values.put(key, value);
                        shard.bytes += bytes;
                        while (shard.bytes > shard.limit) {
                            var oldest = shard.values.pollFirstEntry();
                            shard.bytes -= 80 + 8L * oldest.getValue().length;
                        }
                    }
                    flight.complete(value);
                    shard.pending.remove(key);
                }
            } catch (Throwable failure) {
                synchronized (shard) {
                    flight.completeExceptionally(failure);
                    shard.pending.remove(key, flight);
                }
            }
            return flight.join();
        }

        long bytes() {
            long bytes = 0;
            for (var shard : shards) bytes += shard.bytes;
            return bytes;
        }

        void close() {
            closed = true;
            for (var shard : shards) {
                synchronized (shard) {
                    shard.values.clear();
                    shard.bytes = 0;
                    shard.pending.values().forEach(f -> f.cancel(false));
                    shard.pending.clear();
                }
            }
        }
    }

    static final class Entry {
        enum State {
            QUEUED,
            BUILDING,
            READY,
            FAILED,
            CLOSED
        }

        volatile State state = State.QUEUED;
        volatile int attempts;
        volatile Exception failure;
        static final int MAX_ATTEMPTS = 3;
        final SurfaceFunction function;

        /** The bounded retry policy is shared by whole-work tests and individual patch jobs. */
        private void retry(Callable<Void> work) throws Exception {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                if (state == State.CLOSED || Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                attempts = attempt;
                try {
                    work.call();
                    return;
                } catch (InterruptedException cancelled) {
                    throw cancelled;
                } catch (Exception failed) {
                    org.slf4j.LoggerFactory.getLogger(GroundBaseCache.class)
                            .error(
                                    "Client surface build failed: seed {} attempt {}/{}",
                                    function.terrain().seed(),
                                    attempt,
                                    MAX_ATTEMPTS,
                                    failed);
                    if (attempt == MAX_ATTEMPTS) throw failed;
                }
            }
        }

        void build(Callable<Void> work) {
            synchronized (this) {
                if (state == State.CLOSED) return;
                state = State.BUILDING;
            }
            try {
                retry(work);
                synchronized (this) {
                    if (state != State.CLOSED) {
                        failure = null;
                        state = State.READY;
                    }
                }
            } catch (InterruptedException cancelled) {
                close();
                Thread.currentThread().interrupt();
            } catch (Exception failed) {
                synchronized (this) {
                    if (state != State.CLOSED) {
                        failure = failed;
                        state = State.FAILED;
                    }
                }
            }
        }

        final SurfaceKey key;
        final int[] patchKeys;
        final Point[] centers = new Point[6144];
        final BitSet claimed = new BitSet();
        final Set<Thread> running = ConcurrentHashMap.newKeySet();
        boolean diskLoaded, loading;
        volatile GroundBaseDisk disk;
        volatile GroundFineDisk fineDisk;
        boolean fineDiskOpened;
        volatile Point direction;
        volatile double cameraDistance = Double.POSITIVE_INFINITY;
        final AtomicLong lastAccess = new AtomicLong();

        /** Focus is independently published; null map requests retain the renderer's focus. */
        void focus(Point camera) {
            if (camera == null || !camera.finite() || camera.length() == 0) return;
            direction = camera.scale(1 / camera.length());
            cameraDistance = Math.max(0, camera.length() - function.terrain().shape().size() / 2.);
        }

        int nextPatch() {
            var built = cache;
            if (built == null) return -1;
            var focus = direction;
            int best = -1;
            double distance = Double.POSITIVE_INFINITY;
            for (int patch : patchKeys) {
                if (claimed.get(patch) || built.hasPatch(patch)) continue;
                double d = 1 - focus.dot(centers[patch]);
                if (d < distance) {
                    distance = d;
                    best = patch;
                }
            }
            return best;
        }

        private void work(int patch) throws InterruptedException {
            var built = cache;
            if (built == null || state == State.CLOSED) throw new InterruptedException();
            if (patch == -1) {
                if (!fineDiskOpened) {
                    var opened = GroundFineDisk.open(key);
                    synchronized (this) {
                        fineDisk = opened;
                        fineDiskOpened = true;
                        if (state == State.CLOSED) throw new InterruptedException();
                    }
                }
                if (disk == null) {
                    var opened = GroundBaseDisk.open(key, built);
                    synchronized (this) {
                        disk = opened;
                        if (state == State.CLOSED) throw new InterruptedException();
                    }
                }
                var stored = disk;
                try {
                    diskLoaded = stored == null || !stored.loadNext(built, this::publish);
                } catch (java.io.IOException failure) {
                    disableDisk(failure);
                    diskLoaded = true;
                }
                return;
            }
            built.fillPatch(function, patch, this::publish);
            var stored = disk;
            if (stored != null) {
                try {
                    stored.savePatch(built, patch);
                } catch (java.io.IOException failure) {
                    disableDisk(failure);
                }
            }
        }

        private synchronized void publish(int patch) {
            if (state != State.CLOSED) ready.set(patch, 1);
        }

        private void disableDisk(java.io.IOException failure) {
            org.slf4j.LoggerFactory.getLogger(GroundBaseCache.class)
                    .warn("Disabling surface disk cache for {}", key, failure);
            releaseDisk();
        }

        // Closing may overlap cancellation cleanup. Serialize it without holding the entry/cache
        // monitors used by the render thread, and do not release retirement before IO has finished.
        private final Object diskClose = new Object();

        private void releaseDisk() {
            synchronized (diskClose) {
                GroundBaseDisk stored;
                GroundFineDisk fineStored;
                synchronized (this) {
                    stored = disk;
                    // Coarse completion closes its writer, not the fine cache still used by meshes.
                    fineStored = state == State.CLOSED ? fineDisk : null;
                }
                if (stored != null) stored.close();
                if (fineStored != null) fineStored.close();
                synchronized (this) {
                    if (disk == stored) disk = null;
                    if (fineDisk == fineStored) fineDisk = null;
                }
            }
        }

        static final long FINE_LIMIT = 64L * 1024 * 1024;
        final FineCache fine = new FineCache(FINE_LIMIT);

        long fineBytes() {
            return fine.bytes();
        }

        dev.starfold.worldgen.lod.VoxelColumn fine(Face f, int u, int v) {
            // Normal/edge sampling can leave the chart; those coordinates must not alias interior keys.
            if (u < 0 || v < 0 || u >= key.size() || v >= key.size())
                return dev.starfold.worldgen.lod.VoxelColumn.visible(function, f, u, v);
            long key = ((long) f.ordinal() * 2048 + u) * 2048 + v;
            var packed =
                    fine.get(
                            key,
                            () -> {
                                Supplier<long[]> generate = () ->
                                        dev.starfold.worldgen.lod.VoxelColumn.visible(function, f, u, v).packed();
                                var stored = fineDisk;
                                return stored == null ? generate.get() : stored.get(f.ordinal(), u, v, generate);
                            });
            return dev.starfold.worldgen.lod.VoxelColumn.unpack(packed, 0, packed.length);
        }

        /** Monotonic readiness with a bounded, non-destructive log for independent consumers. */
        static final class Readiness {
            private final AtomicIntegerArray values = new AtomicIntegerArray(6144);
            private final int[] publications = new int[6144];
            private final BitSet published = new BitSet();
            private volatile int publicationCount;

            int length() {
                return values.length();
            }

            int get(int patch) {
                return values.get(patch);
            }

            synchronized void set(int patch, int value) {
                values.set(patch, value);
                if (value == 0 || published.get(patch)) return;
                published.set(patch);
                publications[publicationCount] = patch;
                // Release the immutable log slot and the patch data before readers advance.
                publicationCount++;
            }

            int publicationCount() {
                return publicationCount;
            }

            int publication(int cursor) {
                return publications[cursor];
            }
        }

        volatile dev.starfold.worldgen.lod.VoxelSurfaceCache cache;
        final Readiness ready = new Readiness();

        Entry(PlanetTerrain terrain) {
            function = new SurfaceFunction(terrain);
            key = SurfaceKey.of(terrain);
            cache = new dev.starfold.worldgen.lod.VoxelSurfaceCache(terrain.shape().size());
            patchKeys = cache.patchKeys();
            direction = terrain.unit(Face.TOP, key.size() / 2., key.size() / 2.);
            for (int patch : patchKeys)
                centers[patch] =
                        terrain.unit(
                                Face.VALUES[patch / 1024],
                                Math.min(key.size(), patch % 32 * 64 + 32.),
                                Math.min(key.size(), patch % 1024 / 32 * 64 + 32.));
        }

        void close() {
            synchronized (GroundBaseCache.class) {
                synchronized (this) {
                    if (state == State.CLOSED) return;
                    state = State.CLOSED;
                    // Keep replacement entries off this journal until its worker has closed it.
                    if (!running.isEmpty() || disk != null || fineDisk != null) {
                        retiring.put(key, this);
                        if (running.isEmpty()) cleanup.add(this);
                    }
                    running.forEach(Thread::interrupt);
                    cache = null;
                    fine.close();
                }
                wakeWorkers();
            }
        }

        dev.starfold.worldgen.lod.VoxelColumn voxel(Face f, int u, int v, int step) {
            if (step >= 4) {
                var built = cache;
                var c = built == null ? null : built.column(f, u, v, step);
                if (c != null) return c;
                // Temporary, explicitly coarse startup coverage; replaced on patch publication.
                return dev.starfold.worldgen.lod.VoxelColumn.reduce(
                        List.of(
                                dev.starfold.worldgen.lod.VoxelColumn.visible(
                                        function,
                                        f,
                                        Math.min(
                                                function.terrain().shape().size() - 1,
                                                u + step / 2),
                                        Math.min(
                                                function.terrain().shape().size() - 1,
                                                v + step / 2))),
                        step);
            }
            if (step == 1) return fine(f, u, v);
            // Immutable base only: overlays are reduced separately in CapturedField. Share the
            // existing byte-bounded, single-flight cache; bit 25 cannot alias a fine-column key.
            boolean interior = u >= 0 && v >= 0 && u < key.size() && v < key.size();
            long key = (1L << 25) | ((long) f.ordinal() * 2048 + u) * 2048 + v;
            Supplier<long[]> reduce = () -> {
                var children = new ArrayList<dev.starfold.worldgen.lod.VoxelColumn>(4);
                for (int z = 0; z < 2; z++)
                    for (int x = 0; x < 2; x++) children.add(fine(f, u + x, v + z));
                return dev.starfold.worldgen.lod.VoxelColumn.reduce(children, 2).packed();
            };
            var packed = interior ? fine.get(key, reduce) : reduce.get();
            return dev.starfold.worldgen.lod.VoxelColumn.unpack(packed, 0, packed.length);
        }

        GroundFarMesh.Surface surface(Face f, int u, int v, int step) {
            return GroundFarMesh.Surface.voxel(voxel(f, u / step * step, v / step * step, step));
        }
    }

    private GroundBaseCache() {}
}
