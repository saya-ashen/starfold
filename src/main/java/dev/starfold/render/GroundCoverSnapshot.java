package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.worldgen.PlanetHeightCache;

import java.util.*;
import java.util.function.LongFunction;

/** Immutable descriptions of the geometry actually baked into one cover mesh (including halo). */
public final class GroundCoverSnapshot {
    private final PlanetHeightCache heights;
    private final Map<Long, GroundViewCache.Surface> surfaces;
    private final BitSet unknown;

    public GroundCoverSnapshot(
            PlanetHeightCache heights,
            Map<Long, GroundViewCache.Surface> surfaces,
            BitSet unknown) {
        this.heights = heights;
        this.surfaces = Map.copyOf(surfaces);
        this.unknown = (BitSet) unknown.clone();
    }

    public static GroundCoverSnapshot parent(PlanetHeightCache heights, BitSet changed) {
        return new GroundCoverSnapshot(heights, Map.of(), changed);
    }

    public static GroundCoverSnapshot patch(
            PlanetHeightCache heights,
            BitSet changed,
            int face,
            int x0,
            int z0,
            LongFunction<GroundViewCache.Surface> lookup) {
        var surfaces = new HashMap<Long, GroundViewCache.Surface>();
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

    public GroundViewCache.Surface surface(long key) {
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
                                        CubeSurfaceTopology.Face.values()[face],
                                        columnX,
                                        0,
                                        columnZ))
                        .cell();
        var s = surface(key);
        if (s != null) return s.height(c.u() & 15, c.v() & 15) + 1 - 128;
        if (unknown(key) && heights == null) return 0;
        return GroundTransition.generated(
                heights, CubeSurfaceTopology.Face.values()[face], edgeX, edgeZ);
    }

    private GroundCoverSnapshot() {
        throw new AssertionError();
    }
}
