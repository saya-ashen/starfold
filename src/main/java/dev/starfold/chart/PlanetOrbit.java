package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetShape;

/** GroundOrbit for a planet of any shape: the same variable-curvature join of the planar chart to the whole sphere,
 *  with the planet's own radius. For the standard shape every value equals GroundOrbit's. */
public final class PlanetOrbit {
    public final PlanetShape shape;
    private final double RADIUS;
    public final GroundTopology.Pose pose;
    public final Point eye, normal, basisU, basisV, offset;
    public final GroundOrbit.Frame observerFrame;
    public final double weight, clip, minimumFacing;

    private static Point global(Face f, Point p) {
        return f.u.scale(p.x()).add(f.normal.scale(p.y())).add(f.v.scale(p.z()));
    }

    /** The upright frame of an observer standing at this pose. */
    public static GroundOrbit.Frame frame(PlanetShape shape, GroundTopology.Pose pose) {
        return frame(
                pose.face(),
                new PlanetAir(shape, pose.face(), pose.point(), GroundAir.DEFAULT, true));
    }

    public static GroundOrbit.Frame frame(PlanetShape shape, GroundSpace.Pose pose) {
        return frame(shape, new GroundTopology.Pose(pose.face(), pose.point()));
    }

    private static GroundOrbit.Frame frame(Face face, PlanetAir air) {
        var n = global(face, air.normal);
        var a = global(face, air.basisU);
        var b = global(face, air.basisV);
        // Polar orthogonalization treats the two axes symmetrically across rotated seams.
        var u = PlanetSpace.unit(a.add(n.cross(b)));
        return new GroundOrbit.Frame(u, n, u.cross(n));
    }

    public PlanetOrbit(PlanetShape shape, GroundSpace.Pose pose, Point eye, int view) {
        this(shape, new GroundTopology.Pose(pose.face(), pose.point()), eye, view);
    }

    public PlanetOrbit(PlanetShape shape, GroundTopology.Pose pose, Point eye, int view) {
        this.shape = shape;
        RADIUS = shape.radius();
        this.pose = pose;
        this.eye = eye;
        weight = GroundOrbit.weight(eye.y());
        var air = new PlanetAir(shape, pose.face(), eye, GroundAir.DEFAULT, true);
        var f = frame(pose.face(), air);
        observerFrame = f;
        normal = f.up();
        basisU = global(pose.face(), air.basisU).scale(1 - weight).add(f.u().scale(weight));
        basisV = global(pose.face(), air.basisV).scale(1 - weight).add(f.v().scale(weight));
        clip = weight == 1 ? Double.POSITIVE_INFINITY : view + 24 + 4 * RADIUS * weight;
        // The old planar pole starts outside its permitted view disk. As curvature grows
        // the back hemisphere enters behind the opaque planet, never as a second image.
        minimumFacing = -GroundGap.smooth(.1, .5, weight);
        offset =
                new Point(eye.x(), 0, eye.z())
                        .sub(grid(pose.face(), new Point(eye.x(), 0, eye.z())));
    }

    public Point raw(Face face, Point p) {
        var s = shape.direction(face, p.x(), p.z());
        double c = s.dot(normal), k = weight;
        double d = Math.max(1e-9, 1 + c + k * k * (1 - c));
        var tangent = s.sub(normal.scale(c));
        double scale = 2 * (RADIUS + k * p.y()) / d;
        return new Point(
                eye.x() + tangent.dot(basisU) * scale,
                p.y() * (1 - 2 * k * k * (1 - c) / d) - 2 * RADIUS * k * (1 - c) / d,
                eye.z() + tangent.dot(basisV) * scale);
    }

    private Point grid(Face f, Point p) {
        double x = Math.floor(p.x() / 16) * 16,
                z = Math.floor(p.z() / 16) * 16,
                u = (p.x() - x) / 16,
                v = (p.z() - z) / 16;
        var a = raw(f, new Point(x, p.y(), z));
        var b = raw(f, new Point(x + 16, p.y(), z));
        var c = raw(f, new Point(x, p.y(), z + 16));
        var d = raw(f, new Point(x + 16, p.y(), z + 16));
        return a.scale((1 - u) * (1 - v))
                .add(b.scale(u * (1 - v)))
                .add(c.scale((1 - u) * v))
                .add(d.scale(u * v));
    }

    public Point point(Face f, Point p) {
        return grid(f, p).add(offset);
    }

    public boolean visible(Face f, Point p) {
        var q = point(f, p);
        return shape.direction(f, p.x(), p.z()).dot(normal) >= minimumFacing
                && Math.hypot(q.x() - eye.x(), q.z() - eye.z()) <= clip;
    }
}
