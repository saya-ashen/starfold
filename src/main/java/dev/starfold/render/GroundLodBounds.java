package dev.starfold.render;

import dev.starfold.chart.PlanetGap;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import net.minecraft.world.phys.AABB;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Conservative chart-space bounds of a far group: the repaired map of its rectangle's corners plus MARGIN.
 *  Facing is linear on one face, so corners in front mean the whole rectangle is in front of the pole. */
final class GroundLodBounds {
    /** Twice the worst bulge GroundLodBoundsTest measures (16.3 blocks, next to the eye). */
    static final double MARGIN = 40;
    private static final Point POLE = new Point(Double.NaN, Double.NaN, Double.NaN);

    /** {@code nodes} caches repaired corner nodes for one gap; neighbouring groups share them. */
    static AABB planar(AABB source, PlanetGap gap, Face face, Long2ObjectOpenHashMap<Point> nodes) {
        var tile = gap.chart.tile(face);
        if (tile == null) return null;
        // Stay on this face: past its edge the chart folds onto another face and the map kinks there.
        // The 1/16-block overhang of edge vertices is within MARGIN.
        int size = gap.shape.size();
        var a = tile.point(new Point(Math.clamp(source.minX, 0, size), source.minY, Math.clamp(source.minZ, 0, size)));
        var b = tile.point(new Point(Math.clamp(source.maxX, 0, size), source.maxY, Math.clamp(source.maxZ, 0, size)));
        int x0 = (int) Math.floor(Math.min(a.x(), b.x()) / 16);
        int z0 = (int) Math.floor(Math.min(a.z(), b.z()) / 16);
        int x1 = (int) Math.ceil(Math.max(a.x(), b.x()) / 16);
        int z1 = (int) Math.ceil(Math.max(a.z(), b.z()) / 16);
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
        // Only the rectangle's corners: the repaired map is smooth and centred on the eye, so the
        // interior bulges past the corner hull by at most MARGIN (GroundLodBoundsTest).
        for (int x = x0; x <= x1; x += Math.max(1, x1 - x0))
            for (int z = z0; z <= z1; z += Math.max(1, z1 - z0)) {
                // A plain (x << 32 | z) key hashes to x ^ z: every diagonal collides.
                long key = x * 0x9E3779B97F4A7C15L + z;
                var p = nodes.get(key);
                if (p == null) nodes.put(key, p = node(gap, x, z));
                if (p == POLE) return null;
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
            }
        return new AABB(minX - MARGIN, source.minY, minZ - MARGIN, maxX + MARGIN, source.maxY, maxZ + MARGIN);
    }

    private static Point node(PlanetGap gap, int x, int z) {
        var p = new Point(x * 16, 0, z * 16);
        // Do not bound the stereographic pole, or rely on CPU/GPU precision near it.
        if (gap.air.weight > 0 && gap.air.facing(p) <= 0) return POLE;
        return gap.flat(p);
    }

    private GroundLodBounds() {}
}
