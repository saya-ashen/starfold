package dev.starfold.atlas;

import dev.starfold.api.PlanetFaces;
import java.util.List;

/** Local five-face chart. Diagonal squares are the missing 90 degrees at cube vertices. */
final class GroundChart {
    record Point(double x, double y) {}
    record Location(int face, double u, double v) {}
    record Tile(int face, int x, int y, int turns) {
        Point project(int size, double u, double v) {
            var p = rotate(u - size / 2., v - size / 2., turns);
            return new Point((x + .5) * size + p.x(), (y + .5) * size + p.y());
        }
        Point local(int size, double x, double y) {
            var p = rotate(x - (this.x + .5) * size, y - (this.y + .5) * size, -turns);
            return new Point(p.x() + size / 2., p.y() + size / 2.);
        }
        Tile neighbour(int size, int dx, int dy) {
            var d = rotate(dx, dy, -turns);
            var r = PlanetFaces.adjacent(size, face, (int) (size / 2. + d.x() * (size / 2. + 1)),
                    (int) (size / 2. + d.y() * (size / 2. + 1)));
            return new Tile(r.face(), x + dx, y + dy, Math.floorMod(turns - r.turns(), 4));
        }
    }
    final int size, root;
    final List<Tile> tiles;
    GroundChart(int size, int root) {
        this.size = size; this.root = root;
        var t = new Tile(root, 0, 0, 0);
        tiles = List.of(t, t.neighbour(size, -1, 0), t.neighbour(size, 1, 0),
                t.neighbour(size, 0, -1), t.neighbour(size, 0, 1));
    }
    Tile face(int face) {
        for (var tile : tiles) if (tile.face() == face) return tile;
        return null;
    }
    Location pick(double x, double y) {
        for (var t : tiles) if (x >= t.x() * size && x < (t.x() + 1) * size
                && y >= t.y() * size && y < (t.y() + 1) * size) {
            var p = t.local(size, x, y);
            return new Location(t.face(), Math.clamp(p.x(), 0, Math.nextDown((double) size)),
                    Math.clamp(p.y(), 0, Math.nextDown((double) size)));
        }
        return null;
    }
    /** Opposite-face targets have four unfoldings; use the shortest straight bearing. */
    Point destination(int face, double u, double v, Point from) {
        var tile = face(face);
        if (tile != null) return tile.project(size, u, v);
        Point best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var t : tiles) if (t.x() != 0 || t.y() != 0) {
            var p = t.neighbour(size, t.x(), t.y()).project(size, u, v);
            double d = Math.hypot(p.x() - from.x(), p.y() - from.y());
            if (d < distance) { distance = d; best = p; }
        }
        return best;
    }
    /** Rotation of the new chart into the old one, preserving transported north. */
    int northAfter(int next, int north) { return Math.floorMod(north + face(next).turns(), 4); }
    /** Segment interval inside a viewport; parallel and entirely offscreen lines stay bounded. */
    static double[] clip(Point a, Point b, int x, int y, int width, int height, int margin) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), low = 0, high = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {a.x() - x - margin, x + width - margin - a.x(), a.y() - y - margin, y + height - margin - a.y()};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return null; }
            else if (p[i] < 0) low = Math.max(low, q[i] / p[i]);
            else high = Math.min(high, q[i] / p[i]);
        }
        return low > high ? null : new double[]{low, high};
    }
    static Point rotate(double x, double y, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 0 -> new Point(x, y);
            case 1 -> new Point(-y, x);
            case 2 -> new Point(-x, -y);
            default -> new Point(y, -x);
        };
    }
}
