package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.worldgen.PlanetHeightCache;

import java.util.*;
import java.util.function.LongFunction;

/** Immutable descriptions of the geometry actually baked into one cover mesh (including halo). */
public final class GroundCoverSnapshot {
    private final PlanetHeightCache heights;
    private final Map<Long, GroundSurface> surfaces;
    private final BitSet unknown;
    private final Map<Long, Double> terraces;
    private final int terraceStep;

    public GroundCoverSnapshot(
            PlanetHeightCache heights,
            Map<Long, GroundSurface> surfaces,
            BitSet unknown) {
        this(heights, surfaces, unknown, Map.of(), 0);
    }

    private GroundCoverSnapshot(
            PlanetHeightCache heights,
            Map<Long, GroundSurface> surfaces,
            BitSet unknown,
            Map<Long, Double> terraces,
            int terraceStep) {
        this.heights = heights;
        this.surfaces = surfaces.isEmpty() ? Map.of() : new HashMap<>(surfaces);
        this.unknown = (BitSet) unknown.clone();
        this.terraces = terraces.isEmpty() ? Map.of() : new HashMap<>(terraces);
        this.terraceStep = terraceStep;
    }

    GroundCoverSnapshot withTerraces(
            int face, java.util.List<dev.starfold.worldgen.lod.VoxelMesher.Quad> quads, int step) {
        var tops = new HashMap<>(terraces);
        for (var q : quads)
            if (q.axis() == 1) {
                var p = q.points().getFirst();
                if ((int) p.x() % step == 0 && (int) p.z() % step == 0)
                    tops.put(GroundOwnership.key(shape(), face, (int) p.x(), (int) p.z()), p.y());
            }
        return new GroundCoverSnapshot(heights, surfaces, unknown, tops, step);
    }

    /** Nearest committed terrace top, including captured height; never samples live terrain. */
    double distance(
            PlanetShape shape, dev.starfold.geometry.BentStripGeometry.Point camera, int patch) {
        double nearest = Double.POSITIVE_INFINITY;
        int face = GroundViewPolicy.face(patch),
                x0 = GroundViewPolicy.x(patch),
                z0 = GroundViewPolicy.z(patch);
        for (var entry : terraces.entrySet()) {
            long key = entry.getKey();
            int x = (int) (key % 128) * 16, z = (int) (key / 128 % 128) * 16;
            if (key / 16384 != face || x < x0 || x >= x0 + 256 || z < z0 || z >= z0 + 256) continue;
            nearest =
                    Math.min(
                            nearest,
                            GroundFarMesh.distance(
                                    shape,
                                    camera,
                                    CubeSurfaceTopology.Face.VALUES[face],
                                    x,
                                    z,
                                    terraceStep,
                                    entry.getValue()));
        }
        return nearest;
    }

    static GroundCoverSnapshot far(
            PlanetHeightCache heights,
            Map<Long, GroundSurface> captures,
            BitSet changed) {
        var surfaces = new HashMap<Long, GroundSurface>();
        captures.forEach((key, image) -> surfaces.put(key, image.surfaceOnly()));
        return new GroundCoverSnapshot(heights, surfaces, changed);
    }

    public static GroundCoverSnapshot parent(PlanetHeightCache heights, BitSet changed) {
        return new GroundCoverSnapshot(heights, Map.of(), changed);
    }

    /**
     * Bounded reference snapshot: surfaces and their optional voxel payloads are immutable and
     * shared.
     */
    public static GroundCoverSnapshot cohort(
            PlanetHeightCache heights,
            BitSet changed,
            LongFunction<GroundSurface> lookup) {
        var surfaces = new HashMap<Long, GroundSurface>();
        for (int key = changed.nextSetBit(0); key >= 0; key = changed.nextSetBit(key + 1)) {
            var surface = lookup.apply(key);
            if (surface != null) surfaces.put((long) key, surface);
        }
        return new GroundCoverSnapshot(heights, surfaces, changed);
    }

    public static GroundCoverSnapshot patch(
            PlanetHeightCache heights,
            BitSet changed,
            int face,
            int x0,
            int z0,
            LongFunction<GroundSurface> lookup) {
        return buildPatch(heights, changed, face, x0, z0, lookup);
    }

    public GroundCoverSnapshot patch(int face, int x0, int z0) {
        return buildPatch(heights, unknown, face, x0, z0, surfaces::get);
    }

    /** Far cells need up to 64 columns of halo, including unedited captures. */
    public GroundCoverSnapshot farPatch(int face, int x0, int z0) {
        var selected = new HashMap<Long, GroundSurface>();
        var missing = new BitSet();
        for (int x = x0 - 64; x < x0 + 320; x += 16)
            for (int z = z0 - 64; z < z0 + 320; z += 16) {
                long key = GroundOwnership.key(shape(), face, x, z);
                var s = surfaces.get(key);
                if (s != null) selected.put(key, s);
                if (unknown.get((int) key) && s == null) missing.set((int) key);
            }
        return new GroundCoverSnapshot(heights, selected, missing);
    }

    private static GroundCoverSnapshot buildPatch(
            PlanetHeightCache heights,
            BitSet changed,
            int face,
            int x0,
            int z0,
            LongFunction<GroundSurface> lookup) {
        var surfaces = new HashMap<Long, GroundSurface>();
        var unknown = new BitSet();
        for (int x = x0 - 16; x <= x0 + 256; x += 16)
            for (int z = z0 - 16; z <= z0 + 256; z += 16) {
                long key = GroundOwnership.key(shape(heights), face, x, z);
                if (changed.get((int) key)) {
                    var s = lookup.apply(key);
                    if (s == null) unknown.set((int) key);
                    else surfaces.put(key, s);
                }
            }
        return new GroundCoverSnapshot(heights, surfaces, unknown);
    }

    /** All baked inputs, including the halo, rather than block or packet revision stamps. */
    public boolean sameInputs(GroundCoverSnapshot other) {
        if (other == null
                || heights != other.heights
                || !unknown.equals(other.unknown)
                || !surfaces.keySet().equals(other.surfaces.keySet())) return false;
        for (var entry : surfaces.entrySet())
            if (!entry.getValue().sameColumns(other.surfaces.get(entry.getKey()))) return false;
        return true;
    }

    public PlanetHeightCache heights() {
        return heights;
    }

    public GroundSurface surface(long key) {
        return surfaces.get(key);
    }

    public boolean unknown(long key) {
        return unknown.get((int) key);
    }

    public boolean column(long key) {
        return surfaces.containsKey(key);
    }

    /** A snapshot without generated heights describes a planet of the standard size. */
    private static PlanetShape shape(PlanetHeightCache heights) {
        return heights == null ? PlanetShape.STANDARD : heights.shape();
    }

    public PlanetShape shape() {
        return shape(heights);
    }

    public double height(int face, int columnX, int columnZ, double edgeX, double edgeZ) {
        long key = GroundOwnership.key(shape(), face, columnX, columnZ);
        var c =
                shape().topology()
                        .canonical(
                                new CubeSurfaceTopology.Cell(
                                        CubeSurfaceTopology.Face.VALUES[face],
                                        columnX,
                                        0,
                                        columnZ))
                        .cell();
        if (terraceStep != 0) {
            var top =
                    terraces.get(
                            GroundOwnership.key(
                                    shape(),
                                    c.face().ordinal(),
                                    c.u() / terraceStep * terraceStep,
                                    c.v() / terraceStep * terraceStep));
            if (top != null) return top;
        }
        var s = surface(key);
        if (s != null) return s.height(c.u() & 15, c.v() & 15) + 1 - 128;
        if (unknown(key) && heights == null) return 0;
        return GroundTransition.generated(
                heights, CubeSurfaceTopology.Face.VALUES[face], edgeX, edgeZ);
    }

    private GroundCoverSnapshot() {
        throw new AssertionError();
    }
}
