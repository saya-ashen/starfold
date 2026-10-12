package dev.starfold.geometry;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace.Triangle;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/** Same fixed-space capsule sweep for server authority and client prediction. Units per tick. */
public final class PlanetMotion {
    public static final double RADIUS = .3,
            HALF = .6,
            SKIN = .002,
            MIN_DISTANCE = 2,
            MAX_DISTANCE = 256;
    public static final Point ZERO = new Point(0, 0, 0);

    private PlanetMotion() {}

    public record Input(
            long sequence,
            double qx,
            double qy,
            double qz,
            double qw,
            float forward,
            float left,
            float rise,
            boolean direct,
            boolean fast) {
        public Input {
            double norm = qx * qx + qy * qy + qz * qz + qw * qw;
            if (sequence < 0
                    || !Double.isFinite(norm)
                    || Math.abs(norm - 1) > .001
                    || !Float.isFinite(forward)
                    || !Float.isFinite(left)
                    || !Float.isFinite(rise)
                    || Math.abs(forward) > 1
                    || Math.abs(left) > 1
                    || Math.abs(rise) > 1)
                throw new IllegalArgumentException("Invalid flight intent");
        }

        public Quaterniond orientation() {
            return new Quaterniond(qx, qy, qz, qw).normalize();
        }

        public static Input idle(long seq, Quaterniond q) {
            return new Input(seq, q.x, q.y, q.z, q.w, 0, 0, 0, false, false);
        }
    }

    public static Point direction(Quaterniond q, double x, double y, double z) {
        var v = q.transform(new Vector3d(x, y, z));
        return new Point(v.x, v.y, v.z);
    }

    public record Closest(Point body, Point surface) {
        double distance() {
            return body.sub(surface).length();
        }
    }

    public static Closest closest(PlanetSpace space, Point center, Triangle t) {
        return closest(center, t, space.up(center));
    }

    public static Closest closest(Point center, Triangle t) {
        return closest(center, t, PlanetSpace.unit(center));
    }

    public static Closest closest(Point center, Triangle t, Point up) {
        var axis = up.scale(HALF);
        var a = center.sub(axis);
        var b = center.add(axis);
        var dir = b.sub(a);
        double hit = PlanetSpace.ray(t, a, PlanetSpace.unit(dir), dir.length());
        if (Double.isFinite(hit)) {
            var p = a.add(PlanetSpace.unit(dir).scale(hit));
            return new Closest(p, p);
        }
        var best = new Closest(a, pointTriangle(a, t));
        var candidate = new Closest(b, pointTriangle(b, t));
        if (candidate.distance() < best.distance()) best = candidate;
        for (var edge :
                List.of(
                        new Point[] {t.a(), t.b()},
                        new Point[] {t.b(), t.c()},
                        new Point[] {t.c(), t.a()})) {
            candidate = segments(a, b, edge[0], edge[1]);
            if (candidate.distance() < best.distance()) best = candidate;
        }
        return best;
    }

    public static boolean intersects(PlanetSpace space, Point center, List<Triangle> closedBlock) {
        return intersects(center, closedBlock, space.up(center));
    }

    public static boolean intersects(Point center, List<Triangle> closedBlock) {
        return intersects(center, closedBlock, PlanetSpace.unit(center));
    }

    public static boolean intersects(Point center, List<Triangle> closedBlock, Point up) {
        if (closedBlock.isEmpty()) return false; // An absent volume is not an all-space solid.
        boolean inside = true;
        for (var t : closedBlock) {
            if (closest(center, t, up).distance() < RADIUS + SKIN) return true;
            if (t.normal().dot(center.sub(t.a())) > 1e-8) inside = false;
        }
        return inside;
    }

    private record Impact(double time, Point normal) {}

    private static Impact sweep(
            Point start, Point delta, Triangle t, Point fixedUp, PlanetRoundedGeometry rounded) {
        double length = delta.length();
        if (length < 1e-12) return null;
        // A separating plane bounds BOTH capsule endpoints. Along a straight center path,
        // |d²(normalize(P))/dt²| <= 6 |delta|² / minRadius². This quadratic bound includes
        // radial reorientation and avoids the near-tangent stall of |velocity|-only advancement.
        double minRadius = Math.max(1, start.length() - length);
        double curvature =
                fixedUp != null
                        ? 0
                        : rounded != null
                                ? HALF * rounded.upCurvature(start, delta)
                                : 6 * HALF * length * length / (minRadius * minRadius);
        double time = 0;
        for (int i = 0; i < 100 && time <= 1; i++) {
            var p = start.add(delta.scale(time));
            var up =
                    fixedUp != null
                            ? fixedUp
                            : rounded != null ? rounded.up(p) : PlanetSpace.unit(p);
            var c = closest(p, t, up);
            double gap = c.distance() - RADIUS;
            var n = c.distance() > 1e-9 ? PlanetSpace.unit(c.body.sub(c.surface)) : t.normal();
            var radialDerivative =
                    fixedUp != null
                            ? ZERO
                            : rounded != null
                                    ? rounded.upGradient(p, n)
                                    : n.sub(up.scale(n.dot(up))).scale(1 / p.length());
            double lambda = c.body.sub(p).dot(up);
            var gradient = PlanetSpace.unit(n.add(radialDerivative.scale(lambda)));
            if (gap <= SKIN + 1e-8) {
                if (fixedUp != null && time == 0 && delta.dot(n) >= -1e-10) return null;
                return new Impact(time, gradient);
            }
            double advance = Double.POSITIVE_INFINITY;
            for (double end : new double[] {-HALF, HALF}) {
                double separation = p.add(up.scale(end)).sub(c.surface).dot(n) - RADIUS - SKIN;
                double slope = delta.dot(n.add(radialDerivative.scale(end)));
                double root = Math.sqrt(slope * slope + 2 * curvature * Math.max(0, separation));
                double safe =
                        curvature == 0
                                ? (slope < 0
                                        ? Math.max(0, separation) / (-slope)
                                        : Double.POSITIVE_INFINITY)
                                : slope < 0
                                        ? 2 * Math.max(0, separation) / (root - slope)
                                        : (slope + root) / curvature;
                advance = Math.min(advance, safe);
            }
            if (time + advance > 1) return null;
            time += Math.max(advance, 1e-10);
        }
        // Iteration exhaustion is conservative: stop, never step through untested geometry.
        return time <= 1 ? new Impact(time, t.normal()) : null;
    }

    public static Point move(List<Triangle> candidates, Point start, Point delta, Point up) {
        return move(candidates, start, delta, up, null);
    }

    private static Point move(
            List<Triangle> candidates,
            Point start,
            Point delta,
            Point up,
            PlanetRoundedGeometry rounded) {
        Point p = start, left = delta;
        for (int slide = 0; slide < 5 && left.length() > 1e-8; slide++) {
            Impact first = null;
            for (var t : candidates) {
                var hit = sweep(p, left, t, up, rounded);
                if (hit != null && (first == null || hit.time < first.time)) first = hit;
            }
            if (first == null) {
                p = p.add(left);
                break;
            }
            double time = Math.max(0, first.time - 1e-7);
            p = p.add(left.scale(time));
            left = left.scale(1 - time);
            // Keep a tiny separating margin so a tangent sweep starts outside the contact
            // tolerance.
            p = p.add(first.normal.scale(2e-5));
            left = left.sub(first.normal.scale(Math.min(0, left.dot(first.normal))));
        }
        return p;
    }

    // Closest point on a triangle (Ericson region tests).
    private static Point pointTriangle(Point p, Triangle t) {
        var a = t.a();
        var b = t.b();
        var c = t.c();
        var ab = b.sub(a);
        var ac = c.sub(a);
        var ap = p.sub(a);
        double d1 = ab.dot(ap), d2 = ac.dot(ap);
        if (d1 <= 0 && d2 <= 0) return a;
        var bp = p.sub(b);
        double d3 = ab.dot(bp), d4 = ac.dot(bp);
        if (d3 >= 0 && d4 <= d3) return b;
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) return a.add(ab.scale(d1 / (d1 - d3)));
        var cp = p.sub(c);
        double d5 = ab.dot(cp), d6 = ac.dot(cp);
        if (d6 >= 0 && d5 <= d6) return c;
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) return a.add(ac.scale(d2 / (d2 - d6)));
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0)
            return b.add(c.sub(b).scale((d4 - d3) / ((d4 - d3) + (d5 - d6))));
        double inv = 1 / (va + vb + vc);
        return a.add(ab.scale(vb * inv)).add(ac.scale(vc * inv));
    }

    private static Closest segments(Point p, Point q, Point a, Point b) {
        var d1 = q.sub(p);
        var d2 = b.sub(a);
        var r = p.sub(a);
        double aa = d1.dot(d1),
                ee = d2.dot(d2),
                f = d2.dot(r),
                c = d1.dot(r),
                bb = d1.dot(d2),
                den = aa * ee - bb * bb;
        double s = den > 1e-16 ? Math.clamp((bb * f - c * ee) / den, 0, 1) : 0;
        double t = (bb * s + f) / ee;
        if (t < 0) {
            t = 0;
            s = Math.clamp(-c / aa, 0, 1);
        } else if (t > 1) {
            t = 1;
            s = Math.clamp((bb - c) / aa, 0, 1);
        }
        return new Closest(p.add(d1.scale(s)), a.add(d2.scale(t)));
    }
}
