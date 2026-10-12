package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

/** High-altitude CLOSED PLANAR chart, not a rendered sphere or a new contact world.
 * Cube directions are only an intermediate stereographic parameterization. All output Y
 * coordinates stay unchanged. The opposite face/pole is outside the local view.
 * The dual frame uses symmetric cross-seam samples, so changing the root transports the
 * same planar picture. No time filter, yaw dependence, world mutation or mesh rebuild.
 * CPU counterpart of airRaw in ground_gap.vsh; tuning lives here, not in motion/save code. */
public final class GroundAir {
    public static final double HALF = GroundSpace.SIZE / 2.0,
            RADIUS = GroundSpace.SIZE * 2 / Math.PI,
            GRID = 16;

    public record Config(int start, int end) {
        public Config {
            if (start < 0 || end > 128 || start >= end)
                throw new IllegalArgumentException(
                        "Height: 0 <= start < end <= 128 (local eye height)");
        }

        public double weight(double height) {
            return GroundGap.smooth(start, end, height);
        }
    }

    // User-accepted final visual profile: /cpgroundair heights 30 128, mask 16 128.
    // This fixes defaults only; it does not change mapping or unit-contact semantics.
    public static final Config DEFAULT = new Config(30, 128);
    public final Point eye, normal, basisU, basisV, offset;
    public final double weight;

    public GroundAir(Face root, Point eye, Config config, boolean enabled) {
        this.eye = eye;
        weight = enabled ? config.weight(eye.y()) : 0;
        Point anchor = new Point(eye.x(), 0, eye.z());
        // Interpolated camera positions can still be just outside the new root after a seam.
        var n = sample(root, anchor);
        double cornerDistance =
                Math.hypot(
                        Math.min(eye.x(), GroundSpace.SIZE - eye.x()),
                        Math.min(eye.z(), GroundSpace.SIZE - eye.z()));
        // Never differentiate through a true vertex along two different topology paths.
        double e = Math.max(1e-4, Math.min(32, cornerDistance * .25));
        var du =
                sample(root, anchor.add(new Point(e, 0, 0)))
                        .sub(sample(root, anchor.sub(new Point(e, 0, 0))))
                        .scale(RADIUS / (2 * e));
        var dv =
                sample(root, anchor.add(new Point(0, 0, e)))
                        .sub(sample(root, anchor.sub(new Point(0, 0, e))))
                        .scale(RADIUS / (2 * e));
        du = du.sub(n.scale(du.dot(n)));
        dv = dv.sub(n.scale(dv.dot(n)));
        double a = du.dot(du), b = du.dot(dv), c = dv.dot(dv), det = a * c - b * b;
        if (!(det > 1e-12)) throw new IllegalArgumentException("Degenerate air frame");
        normal = frame(root, n);
        basisU = frame(root, du.scale(c / det).sub(dv.scale(b / det)));
        basisV = frame(root, dv.scale(a / det).sub(du.scale(b / det)));
        // Anchor the interpolated chart too, rather than allowing a sub-grid camera drift.
        offset = anchor.sub(grid(anchor));
    }

    private static Point frame(Face f, Point p) {
        return new Point(p.dot(f.u), p.dot(f.normal), p.dot(f.v));
    }

    private static Point cube(Face f, Point p) {
        return f.normal.scale(HALF).add(f.u.scale(p.x() - HALF)).add(f.v.scale(p.z() - HALF));
    }

    private static Point unit(Point p) {
        return p.scale(1 / p.length());
    }

    private static Point sample(Face f, Point p) {
        var c = GroundSpace.canonical(f, p).pose();
        return unit(cube(c.face(), c.point()));
    }

    /** Fold the five rigid net tiles into cube DIRECTIONS before flattening them again.
     * Both representations of each cut edge give exactly the same vector. */
    public static Point direction(Point p) {
        double x = p.x(), z = p.z(), n = GroundSpace.SIZE;
        if (x < 0) return unit(new Point(-HALF, HALF + x, z - HALF));
        if (x > n) return unit(new Point(HALF, HALF - (x - n), z - HALF));
        if (z < 0) return unit(new Point(x - HALF, HALF + z, -HALF));
        if (z > n) return unit(new Point(x - HALF, HALF - (z - n), HALF));
        return unit(new Point(x - HALF, HALF, z - HALF));
    }

    /** The far/back half is outside the displayed local chart. It includes its pole;
     * the entire permitted view disk lies strictly inside the front half. */
    public double facing(Point p) {
        return direction(p).dot(normal);
    }

    public Point raw(Point p) {
        var s = direction(p);
        var d = s.sub(normal).scale(2 * RADIUS / Math.max(1e-6, 1 + s.dot(normal)));
        return new Point(eye.x() + d.dot(basisU), p.y(), eye.z() + d.dot(basisV));
    }

    private Point grid(Point p) {
        double x = Math.floor(p.x() / GRID) * GRID,
                z = Math.floor(p.z() / GRID) * GRID,
                u = (p.x() - x) / GRID,
                v = (p.z() - z) / GRID;
        var a = raw(new Point(x, 0, z));
        var b = raw(new Point(x + GRID, 0, z));
        var c = raw(new Point(x, 0, z + GRID));
        var d = raw(new Point(x + GRID, 0, z + GRID));
        return new Point(
                a.x() * (1 - u) * (1 - v)
                        + b.x() * u * (1 - v)
                        + c.x() * (1 - u) * v
                        + d.x() * u * v,
                p.y(),
                a.z() * (1 - u) * (1 - v)
                        + b.z() * u * (1 - v)
                        + c.z() * (1 - u) * v
                        + d.z() * u * v);
    }

    public Point flat(Point p) {
        return grid(p).add(offset);
    }
}
