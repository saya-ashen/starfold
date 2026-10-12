package dev.starfold.geometry;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

/** Fixed rounded box = [-flat,flat]^3 plus a ball. C1 surface, not a cube/sphere lerp.
 * Storage coordinates remain unchanged. Heights are normal offsets, not radial distances.
 * Only the same 24 true-corner columns are absent. No observer-dependent deformation. */
public final class PlanetRoundedGeometry {
    public static final double FLAT_FRACTION = .375;
    public static final double BASE = -.09,
            OWN = .065,
            OTHER = .1775,
            SEAM = .47375,
            SPREAD = .1775;
    private static final PlanetBalancedGeometry.Warp WARP =
            new PlanetBalancedGeometry.Warp(BASE, OWN, OTHER, SEAM, SPREAD);
    // Smooth body-up, independent of mesh: avoids a derivative jump at plane/cylinder joins.
    static final double UP_EPS = .25;
    public final double half, flat, band, roundRadius;

    public PlanetRoundedGeometry(int size) {
        this(size, FLAT_FRACTION);
    }

    PlanetRoundedGeometry(int size, double flatFraction) {
        if (flatFraction < 0 || flatFraction >= 1)
            throw new IllegalArgumentException("Invalid rounding");
        half = size * .5;
        flat = half * flatFraction;
        band = half - flat;
        roundRadius = 4 * band / Math.PI;
    }

    private Point core(Point p) {
        return new Point(
                Math.clamp(p.x(), -flat, flat),
                Math.clamp(p.y(), -flat, flat),
                Math.clamp(p.z(), -flat, flat));
    }

    public Point point(Point cube, double h) {
        Point logical = cube.scale(half), c = core(logical);
        return c.add(WARP.direction(logical.sub(c).scale(1 / band)).scale(roundRadius + h));
    }

    public double altitude(Point p) {
        double inside =
                Math.min(
                        0,
                        Math.max(Math.abs(p.x()), Math.max(Math.abs(p.y()), Math.abs(p.z())))
                                - flat);
        return p.sub(core(p)).length() + inside - roundRadius;
    }

    public PlanetSpace.Coordinates coordinates(Face f, Point p) {
        double h = altitude(p);
        Point shell = p.sub(core(p)).length() < 1e-9 ? onRay(p, 0) : p;
        Point n = shell.sub(core(shell));
        var pair = WARP.inverse(n.dot(f.u) / n.dot(f.normal), n.dot(f.v) / n.dot(f.normal));
        return new PlanetSpace.Coordinates(
                half + inverseAxis(shell.dot(f.u), pair.a()),
                h,
                half + inverseAxis(shell.dot(f.v), pair.b()));
    }

    private double inverseAxis(double physical, double a) {
        return Math.abs(physical) <= flat
                ? physical
                : Math.copySign(flat + band * 4 / Math.PI * Math.atan(Math.abs(a)), a);
    }

    /** Nearest point on a reference-height shell; used by orbit mode only. */
    public Point onAltitude(Point p, double h) {
        Point c = core(p), d = p.sub(c);
        double r = roundRadius + h;
        if (r <= 0 || d.length() < 1e-9) return onRay(p, h);
        return c.add(d.scale(r / d.length()));
    }

    /** Monotone radial intersection, including the empty inner box. Not a mesh raycast. */
    public Point onRay(Point direction, double h) {
        Point n = PlanetSpace.unit(direction);
        double r = roundRadius + h;
        if (r <= 0)
            return n.scale(
                    Math.max(0, flat + r)
                            / Math.max(
                                    Math.abs(n.x()), Math.max(Math.abs(n.y()), Math.abs(n.z()))));
        double lo = 0, hi = Math.sqrt(3) * flat + r;
        for (int i = 0; i < 55; i++) {
            double mid = (lo + hi) * .5;
            if (altitude(n.scale(mid)) < h) lo = mid;
            else hi = mid;
        }
        return n.scale((lo + hi) * .5);
    }

    private double residual(double x) {
        double a = Math.hypot(x - flat, UP_EPS), b = Math.hypot(x + flat, UP_EPS);
        return x * (1 - 2 * flat / (a + b));
    }

    private double derivative(double x) {
        return 1
                + .5
                        * ((x - flat) / Math.hypot(x - flat, UP_EPS)
                                - (x + flat) / Math.hypot(x + flat, UP_EPS));
    }

    private Point residual(Point p) {
        return new Point(residual(p.x()), residual(p.y()), residual(p.z()));
    }

    public Point up(Point p) {
        return PlanetSpace.unit(residual(p));
    }

    /** Gradient of n dot up(P), i.e. transpose Jacobian, used by capsule sliding. */
    public Point upGradient(Point p, Point n) {
        Point r = residual(p),
                u = PlanetSpace.unit(r),
                v = n.sub(u.scale(n.dot(u))).scale(1 / Math.max(1e-12, r.length()));
        return new Point(
                v.x() * derivative(p.x()), v.y() * derivative(p.y()), v.z() * derivative(p.z()));
    }

    /** Conservative bound for |d² up(start+t delta)/dt²| over 0<=t<=1. */
    public double upCurvature(Point start, Point delta) {
        double length = delta.length(), min = Math.max(1e-9, residual(start).length() - length);
        double second =
                Math.max(
                        secondBound(start.x(), delta.x()),
                        Math.max(
                                secondBound(start.y(), delta.y()),
                                secondBound(start.z(), delta.z())));
        return length * length * (2 * second / min + 6 / (min * min));
    }

    private double secondBound(double start, double delta) {
        double lo = Math.min(start, start + delta), hi = Math.max(start, start + delta), sum = 0;
        for (double join : new double[] {-flat, flat}) {
            double d = join < lo ? lo - join : join > hi ? join - hi : 0;
            sum += .5 * UP_EPS * UP_EPS / Math.pow(d * d + UP_EPS * UP_EPS, 1.5);
        }
        return sum;
    }
}
