package dev.starfold.waypoint;

import dev.starfold.chart.PlanetChart;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;

import java.util.ArrayList;
import java.util.List;

/** Shortest straight route in an unfolded cube net, in chart blocks (height ignored).
 * Enumerate simple face paths; only accept lines actually crossing those faces in order. */
public final class WaypointNavigation {
    record Route(Point direction, double distance) {}

    static boolean accepts(long packet, long chart, long region) {
        long expected = chart != 0 ? chart : region;
        return expected != 0 && packet == expected;
    }

    public static boolean grounded(double clearance, double speed) {
        return Double.isFinite(clearance)
                && clearance >= -2
                && clearance < 2
                && Double.isFinite(speed)
                && speed < .5;
    }

    /** Minecraft yaw: positive turns right. Signed shortest turn to a bearing in the same chart. */
    static double angle(double facing, Point bearing) {
        double yaw = Math.toDegrees(Math.atan2(-bearing.x(), bearing.z()));
        return Math.atan2(
                        Math.sin(Math.toRadians(yaw - facing)),
                        Math.cos(Math.toRadians(yaw - facing)))
                * 180
                / Math.PI;
    }

    static Route route(PlanetShape shape, PlanetShape.Pose from, PlanetShape.Pose to) {
        var path = new ArrayList<PlanetChart.Tile>();
        path.add(new PlanetChart.Tile(shape, from.face(), 0, 0, 0));
        return search(shape, from.point(), to, path, 1 << from.face().ordinal(), null);
    }

    private static Route search(
            PlanetShape shape,
            Point from,
            PlanetShape.Pose to,
            List<PlanetChart.Tile> path,
            int visited,
            Route best) {
        var tile = path.getLast();
        if (tile.face() == to.face()) {
            var target = tile.point(to.point());
            var delta = new Point(target.x() - from.x(), 0, target.z() - from.z());
            double distance = delta.length();
            if ((best == null || distance < best.distance() - 1e-7)
                    && valid(shape.size(), from, delta, path)) return new Route(delta, distance);
            return best;
        }
        for (var step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            var next = tile.adjacent(step[0], step[1]);
            int bit = 1 << next.face().ordinal();
            if ((visited & bit) != 0) continue;
            path.add(next);
            best = search(shape, from, to, path, visited | bit, best);
            path.removeLast();
        }
        return best;
    }

    private static boolean valid(
            double size, Point from, Point delta, List<PlanetChart.Tile> path) {
        double previous = 0;
        for (var tile : path) {
            double lo = 0, hi = 1;
            double[] p = {from.x(), from.z()},
                    d = {delta.x(), delta.z()},
                    min = {tile.x() * size, tile.z() * size};
            for (int axis = 0; axis < 2; axis++) {
                if (Math.abs(d[axis]) < 1e-10) {
                    if (p[axis] < min[axis] - 1e-7 || p[axis] > min[axis] + size + 1e-7)
                        return false;
                } else {
                    double a = (min[axis] - p[axis]) / d[axis],
                            b = (min[axis] + size - p[axis]) / d[axis];
                    lo = Math.max(lo, Math.min(a, b));
                    hi = Math.min(hi, Math.max(a, b));
                }
            }
            if (hi < lo - 1e-8 || Math.abs(lo - previous) > 1e-7) return false;
            previous = hi;
        }
        return previous >= 1 - 1e-7;
    }

    private WaypointNavigation() {}
}
