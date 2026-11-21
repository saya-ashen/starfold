package dev.starfold.render;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.lod.VoxelColumn;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.*;
import java.util.function.LongFunction;

/** Render-thread voxel base readiness and immutable capture snapshots. */
final class GroundMipCache {
    private GroundBaseCache.Entry base;
    private final Map<Long, GroundSurface> edited = new HashMap<>();
    private final BitSet overlayChanges = new BitSet();
    private int baseCursor;
    private final Map<Long, Long> captureVersions = new HashMap<>();
    private Point camera;
    private PlanetShape shape;
    private Object captureOwner;
    private GroundCoverSnapshot coverOwner;
    private static final Object UNVERSIONED = new Object();
    private final CaptureSnapshot[] liveSnapshots =
            new CaptureSnapshot[GroundViewPolicy.PATCH_COUNT];
    private final CaptureSnapshot[] coverSnapshots =
            new CaptureSnapshot[GroundViewPolicy.PATCH_COUNT];

    /** Only immutable patch inputs are shared; every field below owns its derived columns. */
    private record CaptureSnapshot(Object owner, long version, Map<Long, GroundSurface> captures) {}

    void clear() {
        base = null;
        edited.clear();
        baseCursor = 0;
        captureVersions.clear();
        overlayChanges.clear();
        polledOverlays.clear();
        camera = null;
        shape = null;
        captureOwner = null;
        coverOwner = null;
        Arrays.fill(liveSnapshots, null);
        Arrays.fill(coverSnapshots, null);
    }

    void focus(Point camera) {
        this.camera = camera;
    }

    long fineBytes() {
        return base == null ? 0 : base.fineBytes();
    }

    long coarseBytes() {
        var cache = base == null ? null : base.cache;
        return cache == null ? 0 : cache.retainedBytes();
    }

    /**
     * Ready notifications name consumers, including their four edge halos, never the whole planet.
     */
    private BitSet polledOverlays = new BitSet();

    /** The capture-overlay share of the last {@link #poll()}. */
    BitSet polledOverlays() {
        var out = polledOverlays;
        polledOverlays = new BitSet();
        return out;
    }

    BitSet poll() {
        return poll(true);
    }

    /** The quadtree already invalidates authoritative overlay changes on receipt. */
    BitSet pollBase() {
        return poll(false);
    }

    private BitSet poll(boolean includeOverlays) {
        if (base != null) {
            var patches = new BitSet();
            int published = base.ready.publicationCount();
            while (baseCursor < published) {
                int p = base.ready.publication(baseCursor++);
                int face = p / 1024, u = (p % 32) * 64, v = (p % 1024 / 32) * 64;
                patches.set(face * 64 + u / 256 + 8 * (v / 256));
                // Halo consumers also depend on this patch's boundary.
                for (int side = 0; side < 4; side++) {
                    int x = u + (side == 0 ? -1 : side == 1 ? 64 : 0);
                    int z = v + (side == 2 ? -1 : side == 3 ? 64 : 0);
                    var c =
                            base.function
                                    .terrain()
                                    .shape()
                                    .topology()
                                    .canonical(new Cell(Face.VALUES[face], x, 0, z))
                                    .cell();
                    patches.set(c.face().ordinal() * 64 + c.u() / 256 + 8 * (c.v() / 256));
                }
            }
            polledOverlays = (BitSet) overlayChanges.clone();
            if (includeOverlays) patches.or(overlayChanges);
            overlayChanges.clear();
            return patches;
        }
        return new BitSet();
    }

    private GroundBaseCache.Entry source(PlanetShape shape, PlanetTerrain terrain) {
        if (terrain == null)
            throw new IllegalStateException("Terrain profile required before meshing");
        var source = GroundBaseCache.get(terrain, camera);
        if (base != source || !shape.equals(this.shape)) {
            base = source;
            this.shape = shape;
            baseCursor = 0;
            edited.clear();
            captureVersions.clear();
            overlayChanges.clear();
            polledOverlays.clear();
            captureOwner = null;
            coverOwner = null;
            Arrays.fill(liveSnapshots, null);
            Arrays.fill(coverSnapshots, null);
        }
        return source;
    }

    /** No optional mip jobs on the critical path to the first planet-wide coverage. */
    GroundFarMesh.Field initialField(PlanetTerrain terrain) {
        if (terrain == null)
            throw new IllegalStateException("Terrain profile required before meshing");
        var source = source(terrain.shape(), terrain);
        return (f, x, z, s) -> source.surface(f, x, z, s);
    }

    /** A latched cover is authoritative for its cohort, even when the live provider is newer. */
    GroundFarMesh.Field field(
            PlanetShape shape, PlanetTerrain terrain, GroundCoverSnapshot cover, int patch) {
        var source = source(shape, terrain);
        if (coverOwner != cover) {
            // Retain one cohort, not one planet-wide cover for every previously visited patch.
            Arrays.fill(coverSnapshots, null);
            coverOwner = cover;
        }
        var snapshot = coverSnapshots[patch];
        if (snapshot == null || snapshot.owner != cover) {
            snapshot =
                    new CaptureSnapshot(
                            cover, 0, capturePatch(shape, cover::surface, patch, false));
            coverSnapshots[patch] = snapshot;
        }
        return new CapturedField(shape, source, snapshot.captures);
    }

    /**
     * Reuse one immutable patch+64-block-halo snapshot per provider identity and version. The
     * render-thread caller must advance version on every provider mutation, including removals and
     * clear. Do not use a drained/possibly coalesced dirty queue as the version. Base readiness is
     * independent: each job still gets a fresh derived-column cache over the current shared base.
     */
    GroundFarMesh.Field field(
            PlanetShape shape,
            PlanetTerrain terrain,
            Map<Long, GroundSurface> captures,
            long version,
            int patch) {
        var source = source(shape, terrain);
        owner(captures);
        var snapshot = liveSnapshots[patch];
        if (snapshot == null || snapshot.version != version) {
            snapshot =
                    new CaptureSnapshot(
                            captures, version, capturePatch(shape, captures::get, patch, true));
            liveSnapshots[patch] = snapshot;
        }
        return new CapturedField(shape, source, snapshot.captures);
    }

    /**
     * Compatibility for unversioned lookups: bounded capture, without reuse. Lookup is consumed only
     * here on the render thread, never retained by a worker. Use the map+version overload for jobs
     * sharing a mutable provider, and clear this cache if an unversioned provider is replaced.
     */
    GroundFarMesh.Field field(
            PlanetShape shape,
            PlanetTerrain terrain,
            LongFunction<GroundSurface> capturesAt,
            int patch) {
        var source = source(shape, terrain);
        owner(UNVERSIONED);
        return new CapturedField(shape, source, capturePatch(shape, capturesAt, patch, true));
    }

    private void owner(Object owner) {
        if (captureOwner == owner) return;
        for (long key : edited.keySet()) overlayChanges.set(GroundViewPolicy.patch(key));
        edited.clear();
        captureVersions.clear();
        Arrays.fill(liveSnapshots, null);
        captureOwner = owner;
    }

    private Map<Long, GroundSurface> capturePatch(
            PlanetShape shape, LongFunction<GroundSurface> lookup, int patch, boolean live) {
        var captures = new HashMap<Long, GroundSurface>();
        var touched = new HashSet<Long>();
        var face = Face.VALUES[GroundViewPolicy.face(patch)];
        int a = GroundViewPolicy.x(patch), b = GroundViewPolicy.z(patch);
        for (int v = b - 64; v < Math.min(b + 320, shape.size() + 64); v += 16)
            for (int u = a - 64; u < Math.min(a + 320, shape.size() + 64); u += 16) {
                long key = GroundTileKeys.key(shape, face.ordinal(), u, v);
                if (!touched.add(key)) continue;
                var next = lookup.apply(key);
                if (live) {
                    var old = edited.get(key);
                    long priorVersion = captureVersions.getOrDefault(key, -1L);
                    if (next != null && next.revision() < priorVersion) next = old;
                    else {
                        boolean changed =
                                old == null ? next != null : next == null || !old.sameColumns(next);
                        // Notification-only revisions must advance the watermark too. Otherwise an
                        // intermediate stale packet can roll geometry back, even after a removal.
                        if (next != null) {
                            captureVersions.put(key, next.revision());
                            edited.put(key, next);
                        } else if (old != null) {
                            captureVersions.put(
                                    key,
                                    priorVersion == Long.MAX_VALUE
                                            ? priorVersion
                                            : priorVersion + 1);
                            edited.remove(key);
                        }
                        if (changed) overlayChanges.set(GroundViewPolicy.patch(key));
                    }
                }
                if (next != null) captures.put(key, next);
            }
        // Never copy the planet-wide edited map into a job. This private map is bounded to the
        // patch and its full canonical halo, and no code retains a mutable reference to it.
        return captures.isEmpty() ? Map.of() : Collections.unmodifiableMap(captures);
    }

    private static final class CapturedField implements GroundFarMesh.Field {
        private final PlanetShape shape;
        private final GroundBaseCache.Entry source;
        private final Map<Long, GroundSurface> near;
        private final Long2ObjectOpenHashMap<VoxelColumn> derived = new Long2ObjectOpenHashMap<>();

        CapturedField(
                PlanetShape shape, GroundBaseCache.Entry source, Map<Long, GroundSurface> near) {
            this.shape = shape;
            this.source = source;
            this.near = near;
        }

        private VoxelColumn column(Face f, int x, int z, int s) {
            boolean edit = false;
            for (int v = z / 16 * 16; v < z + s && !edit; v += 16)
                for (int u = x / 16 * 16; u < x + s; u += 16)
                    if (near.containsKey(GroundTileKeys.key(shape, f.ordinal(), u, v))) {
                        edit = true;
                        break;
                    }
            if (!edit) return source.voxel(f, x, z, s);
            long key = ((long) f.ordinal() * shape.size() + z) * shape.size() + x;
            key = key * 128 + s;
            var prior = derived.get(key);
            if (prior != null) return prior;
            VoxelColumn result;
            if (s == 1) {
                var capture = near.get(GroundTileKeys.key(shape, f.ordinal(), x, z));
                if (capture == null) result = source.voxel(f, x, z, 1);
                else {
                    var runs = new ArrayList<VoxelColumn.Run>();
                    for (int i = capture.runCount(x & 15, z & 15) - 1; i >= 0; i--) {
                        var r = capture.run(x & 15, z & 15, i);
                        if (!r.material().isAir())
                            runs.add(new VoxelColumn.Run(r.bottom(), r.top() + 1, r.material()));
                    }
                    result = VoxelColumn.compose(runs);
                }
            } else {
                var children = new ArrayList<VoxelColumn>(4);
                for (int dz = 0; dz < 2; dz++)
                    for (int dx = 0; dx < 2; dx++)
                        if (x + dx * s / 2 < shape.size() && z + dz * s / 2 < shape.size())
                            children.add(column(f, x + dx * s / 2, z + dz * s / 2, s / 2));
                result = VoxelColumn.reduce(children, s);
            }
            derived.put(key, result);
            return result;
        }

        @Override public String cacheInputs(int step) {
            // Startup midpoint samples are NOT the final reduced coarse columns. Never persist
            // their meshes under the stable terrain identity, even if readiness changes mid-build.
            var coarse = source.cache;
            if (step >= 4 && (coarse == null || !coarse.complete())) return null;
            return cacheLookupInputs(step);
        }

        @Override public String cacheLookupInputs(int step) {
            // Reading a previously completed mesh does not depend on transient coarse readiness.
            return GroundMeshCache.fingerprint(near);
        }

        public GroundFarMesh.Surface cell(Face f, int x, int z, int s) {
            return GroundFarMesh.Surface.voxel(column(f, x, z, s));
        }
    }
}
