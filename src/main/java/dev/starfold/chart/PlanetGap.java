package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.PlanetShape;

import java.util.*;

/** GroundGap for a planet of any shape: the same repair of the net's far missing quadrant, measured from the
 *  planet's own vertices. For the standard shape every value equals GroundGap's. */
public final class PlanetGap {
    public static final double NEAR = 32, GRID = 16, GUARD = 56, BLEND = 32, BAND = Math.PI / 6;
    public final PlanetShape shape;
    public final PlanetChart chart;
    public final Point eye, corner;
    public final double sx, sz, distance, clip, strength, outer;
    public final PlanetAir air;

    public PlanetGap(
            PlanetShape shape,
            Face root,
            Point eye,
            int view,
            boolean enabled,
            GroundAir.Config heights,
            boolean airEnabled) {
        if (eye == null || !eye.finite() || view < 32 || view > 1024)
            throw new IllegalArgumentException("Gap view");
        this.shape = shape;
        chart = new PlanetChart(shape, root);
        this.eye = eye;
        clip = view + 24;
        corner =
                new Point(
                        eye.x() < shape.half() ? 0 : shape.size(),
                        0,
                        eye.z() < shape.half() ? 0 : shape.size());
        sx = corner.x() == 0 ? 1 : -1;
        sz = corner.z() == 0 ? 1 : -1;
        distance = Math.hypot(eye.x() - corner.x(), eye.z() - corner.z());
        strength = enabled ? 1 - smooth(clip - BLEND, clip, distance) : 0;
        outer = clip + distance + 32;
        air = new PlanetAir(shape, root, eye, heights, enabled && airEnabled);
    }

    private static double smooth(double low, double high, double value) {
        return GroundGap.smooth(low, high, value);
    }

    /** Affected source, destination and intermediate arcs stay in the half-plane behind the vertex. */
    public double amplitude(double r) {
        double minDistance = Math.hypot(distance, r);
        return strength
                * smooth(GUARD, GUARD + BLEND, minDistance)
                * (1 - smooth(outer, 2 * outer, r));
    }

    public Point raw(Point p) {
        if (strength == 0) return p;
        double x = (p.x() - corner.x()) * sx, z = (p.z() - corner.z()) * sz;
        if (!(x < 0 && z >= 0 || z < 0 && x >= 0)) return p;
        double theta =
                x < 0
                        ? Math.PI - Math.atan2(Math.max(0, z), -x)
                        : -Math.PI / 2 + Math.atan2(Math.max(0, x), -z);
        double eyeAngle = Math.atan2((eye.z() - corner.z()) * sz, (eye.x() - corner.x()) * sx),
                delta = theta - eyeAngle;
        if (Math.abs(delta) <= Math.PI * 7 / 12) return p;
        // Intrinsic cone period is 270 degrees. Referencing the player's radial direction
        // makes the fully repaired far seam independent of which incident face is the root.
        if (delta > Math.PI * .75) delta -= Math.PI * 1.5;
        else if (delta < -Math.PI * .75) delta += Math.PI * 1.5;
        double target =
                eyeAngle
                        + delta
                        + Math.signum(delta)
                                * Math.PI
                                / 4
                                * smooth(Math.PI * 7 / 12, Math.PI * .75, Math.abs(delta));
        double shift = target - theta;
        if (shift > Math.PI) shift -= 2 * Math.PI;
        else if (shift < -Math.PI) shift += 2 * Math.PI;
        double angle = shift * amplitude(Math.hypot(x, z));
        if (angle == 0) return p;
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Point(
                corner.x() + sx * (x * c - z * s), p.y(), corner.z() + sz * (x * s + z * c));
    }

    /** A common fixed grid welds fine and fallback edges. GUARD includes its 16*sqrt(2) footprint. */
    public Point flat(Point p) {
        if (air.weight == 1) return air.flat(p);
        var ground = ground(p);
        return air.weight == 0
                ? ground
                : ground.scale(1 - air.weight).add(air.flat(p).scale(air.weight));
    }

    private Point ground(Point p) {
        if (strength == 0) return p;
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

    public Point point(Face face, Point local) {
        var tile = chart.tile(face);
        if (tile == null) throw new IllegalArgumentException("Outside local net");
        return flat(tile.point(local));
    }

    public record GapQuad(Point a, Point b, Point c, Point d) {}

    /** The ACTUAL remaining hole, not a red plane hidden under replacement terrain. */
    public List<GapQuad> residual() {
        var out = new ArrayList<GapQuad>();
        for (double x : new double[] {0, shape.size()})
            for (double z : new double[] {0, shape.size()}) {
                double xs = x == 0 ? 1 : -1,
                        zs = z == 0 ? 1 : -1,
                        range = Math.min(shape.size(), clip + Math.hypot(eye.x() - x, eye.z() - z));
                if (Math.hypot(eye.x() - x, eye.z() - z) > clip) continue;
                for (double r = 0; r < range && r < shape.size(); r += GRID) {
                    var a = flat(new Point(x - xs * r, 0, z));
                    var b = flat(new Point(x - xs * (r + GRID), 0, z));
                    var c = flat(new Point(x, 0, z - zs * (r + GRID)));
                    var d = flat(new Point(x, 0, z - zs * r));
                    if (b.sub(c).length() > 1e-7 || a.sub(d).length() > 1e-7)
                        out.add(new GapQuad(a, b, c, d));
                }
            }
        return out;
    }
}
