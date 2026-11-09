package dev.starfold.planet;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

/** Six disjoint native upright owners. GroundTopology routes halo queries, never stored copies. */
public final class GroundCoordinates {
    public static final int STRIDE = 4096, FLOOR = 128;
    private static final Point[] ORIGINS = {
        new Point(0, FLOOR, 0), new Point(STRIDE, FLOOR, 0),
        new Point(2 * STRIDE, FLOOR, 0), new Point(3 * STRIDE, FLOOR, 0),
        new Point(4 * STRIDE, FLOOR, 0), new Point(5 * STRIDE, FLOOR, 0)
    };

    public static int face(double x) {
        return Math.clamp((int) Math.floor((x + 1024) / STRIDE), 0, 5);
    }

    public static Point origin(int face) {
        if (face < 0 || face > 5) throw new IllegalArgumentException("Face");
        return ORIGINS[face];
    }

    public static Point chart(Point world) {
        return world.sub(origin(face(world.x())));
    }

    public static Point world(Face face, Point chart) {
        return origin(face.ordinal()).add(chart);
    }

    public static boolean owned(Point world) {
        return owned(PlanetShape.STANDARD, world);
    }

    public static boolean owned(PlanetShape shape, Point world) {
        var p = chart(world);
        return shape.owned(p.x(), p.z());
    }

    private GroundCoordinates() {}
}
