package dev.starfold.render;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetHeightCache;

import java.util.*;

/** Readonly derived surface geometry. Shared 16-block nodes, disjoint water/land polygons.
 * Water exists only where the body's own sea covers the ground; it is never inferred from height. */
public final class GroundSurfaceMesh {
    public static final int GRID = 16;

    public record Polygon(boolean water, List<Point> points) {
        public Polygon {
            points = List.copyOf(points);
        }
    }

    public static List<Polygon> tile(PlanetHeightCache cache, Face face, int x, int z) {
        var a = new Point(x, cache.node(face, x, z), z);
        var b = new Point(x, cache.node(face, x, z + GRID), z + GRID);
        var c = new Point(x + GRID, cache.node(face, x + GRID, z + GRID), z + GRID);
        var d = new Point(x + GRID, cache.node(face, x + GRID, z), z);
        var points = List.of(a, b, c, d);
        var sea = cache.sea();
        if (sea == null) return List.of(new Polygon(false, points));
        double level = sea.elevation();
        if (points.stream().allMatch(p -> p.y() >= level))
            return List.of(new Polygon(false, points));
        if (points.stream().allMatch(p -> p.y() < level))
            return List.of(
                    new Polygon(
                            true,
                            points.stream().map(p -> new Point(p.x(), level, p.z())).toList()));
        var out = new ArrayList<Polygon>(4);
        split(List.of(a, b, c), level, out);
        split(List.of(a, c, d), level, out);
        return List.copyOf(out);
    }

    private static void split(List<Point> triangle, double level, List<Polygon> out) {
        var land = clip(triangle, level, false);
        if (land.size() >= 3) out.add(new Polygon(false, land));
        // Exactly sea-level land belongs to land only, never a coplanar water sheet.
        if (triangle.stream().anyMatch(p -> p.y() < level)) {
            var water = clip(triangle, level, true);
            if (water.size() >= 3)
                out.add(
                        new Polygon(
                                true,
                                water.stream().map(p -> new Point(p.x(), level, p.z())).toList()));
        }
    }

    static List<Point> clip(List<Point> triangle, double level, boolean water) {
        var out = new ArrayList<Point>();
        var previous = triangle.getLast();
        boolean before = water ? previous.y() <= level : previous.y() >= level;
        for (var next : triangle) {
            boolean inside = water ? next.y() <= level : next.y() >= level;
            if (inside != before) {
                double t = (previous.y() - level) / (previous.y() - next.y());
                var p = previous.add(next.sub(previous).scale(t));
                out.add(new Point(p.x(), level, p.z()));
            }
            if (inside) out.add(next);
            previous = next;
            before = inside;
        }
        return out;
    }

    public static BitSet exclusions(BitSet nativeReady, Collection<Long> residentDetails) {
        var out = (BitSet) nativeReady.clone();
        for (long key : residentDetails) {
            if (key < 0 || key >= 98304) throw new IllegalArgumentException("Coverage tile");
            out.set((int) key);
        }
        return out;
    }

    public static boolean surfaceSectionsReady(int[] heights, BitSet compiled) {
        for (int y : heights)
            if (y >= -64 && (y >= 320 || !compiled.get((y + 64) >> 4))) return false;
        return true;
    }

    /** Only a complete full-sphere cover may take over, never during the ground/orbit blend. */
    public static boolean replaceNative(double eyeHeight, boolean allPatchesReady) {
        return eyeHeight >= GroundOrbit.END && allPatchesReady;
    }

    private GroundSurfaceMesh() {}
}
