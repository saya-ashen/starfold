package dev.starfold.sky;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetStorage;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Pure geometry for bodies between planets and the rigid frame of their remote Level region. */
public final class Space {
    /** Small native floats matter: at two million, 0.25 spacing swallowed sideways substeps in the crewed probe. */
    public static final int X = -16_384;

    /** Both axes stay below the charts, which begin at -2048. */
    public static final int Z = -16_384;

    /** Small float spacing for vertical integration too; membership still ignores y. */
    public static final int Y = 8192;

    /** Sable removes bodies whose bounds leave this default Level height band. */
    public static final int SABLE_MIN_Y = -10_000, SABLE_MAX_Y = 100_000;

    /** Re-anchor before float coordinates reach magnitude 32768 or leave Sable's height band. */
    public static final int ANCHOR_REACH = 12_000;

    public static final double HYSTERESIS = 512;

    /** Half-width in Level x and z of the terrain-free space region. */
    public static final int REACH = 13_312;

    public static final Point ORIGIN = new Point(X, Y, Z);

    /** A body's position, orientation, velocity (blocks/tick), and spin (radians/tick) in chart or universe axes.
     * Chart spin is relative to the planet, not a body-axis vector or Euler-angle rate.
     * @param position body anchor, in chart blocks or universe blocks (never translated Level coordinates)
     * @param orientation rotation from body axes to the named coordinate axes
     * @param velocity anchor velocity in the named coordinate axes
     * @param spin angular velocity in the named coordinate axes
     */
    public record Motion(Point position, Quaterniond orientation, Point velocity, Vector3d spin) {
        /** Validates a body's finite motion and copies mutable orientation and spin values. */
        public Motion {
            GroundEphemeris.point(position);
            GroundEphemeris.quaternion(orientation);
            GroundEphemeris.point(velocity);
            if (spin == null
                    || !Double.isFinite(spin.x)
                    || !Double.isFinite(spin.y)
                    || !Double.isFinite(spin.z))
                throw new IllegalArgumentException("Finite spin required");
            orientation = new Quaterniond(orientation);
            spin = new Vector3d(spin);
        }

        /** Body-to-chart or body-to-universe rotation, returned as a defensive copy. */
        @Override
        public Quaterniond orientation() {
            return new Quaterniond(orientation);
        }

        /** Angular velocity in chart or universe axes, returned as a defensive copy. */
        @Override
        public Vector3d spin() {
            return new Vector3d(spin);
        }
    }

    /** Level = R * (Universe - planet centre(t) - offset - drift*(t-epoch)) + T. */
    public record Frame(
            Quaterniond rotation, Point shift, int planet, Point offset, Point drift, long epoch) {
        public Frame(Quaterniond rotation, Point shift, int planet) {
            this(rotation, shift, planet, new Point(0, 0, 0), new Point(0, 0, 0), 0);
        }

        public static final Frame IDENTITY = new Frame(new Quaterniond(), ORIGIN, 0);

        public Frame {
            GroundEphemeris.quaternion(rotation);
            GroundEphemeris.point(shift);
            GroundEphemeris.point(offset);
            GroundEphemeris.point(drift);
            if (epoch < 0) throw new IllegalArgumentException("Space epoch");
            if (planet < 0 || planet >= PlanetStorage.MAX_PLANETS)
                throw new IllegalArgumentException("Space reference planet");
            rotation = new Quaterniond(rotation);
        }

        @Override
        public Quaterniond rotation() {
            return new Quaterniond(rotation);
        }

        public Point level(Point point, GroundEphemeris.Model model, double tick) {
            GroundEphemeris.point(point);
            return levelVector(
                            point.sub(frame(model, planet, tick).center()).sub(displacement(tick)))
                    .add(shift);
        }

        public Point universe(Point point, GroundEphemeris.Model model, double tick) {
            GroundEphemeris.point(point);
            return universeVector(point.sub(shift))
                    .add(frame(model, planet, tick).center())
                    .add(displacement(tick));
        }

        public Point levelVelocity(Point velocity, GroundEphemeris.Model model, double tick) {
            return levelVector(velocity.sub(frame(model, planet, tick).velocity()).sub(drift));
        }

        public Point universeVelocity(Point velocity, GroundEphemeris.Model model, double tick) {
            return universeVector(velocity).add(frame(model, planet, tick).velocity()).add(drift);
        }

        public Point levelVector(Point v) {
            GroundEphemeris.point(v);
            var r = rotation.transform(vector(v));
            return new Point(r.x, r.y, r.z);
        }

        public Point universeVector(Point v) {
            GroundEphemeris.point(v);
            var r = rotation().conjugate().transform(vector(v));
            return new Point(r.x, r.y, r.z);
        }

        public Quaterniond levelOrientation(Quaterniond q) {
            GroundEphemeris.quaternion(q);
            return rotation().mul(q).normalize();
        }

        public Quaterniond universeOrientation(Quaterniond q) {
            GroundEphemeris.quaternion(q);
            return rotation().conjugate().mul(q).normalize();
        }

        public Motion level(Motion motion, GroundEphemeris.Model model, double tick) {
            return new Motion(
                    level(motion.position(), model, tick),
                    levelOrientation(motion.orientation()),
                    levelVelocity(motion.velocity(), model, tick),
                    rotation.transform(motion.spin()));
        }

        public Motion universe(Motion motion, GroundEphemeris.Model model, double tick) {
            return new Motion(
                    universe(motion.position(), model, tick),
                    universeOrientation(motion.orientation()),
                    universeVelocity(motion.velocity(), model, tick),
                    rotation().conjugate().transform(motion.spin()));
        }

        private Point displacement(double tick) {
            if (!Double.isFinite(tick) || tick < 0)
                throw new IllegalArgumentException("Space time");
            return offset.add(drift.scale(tick - epoch));
        }

        /** Move the epoch without changing the labelling at any time. */
        public Frame rebased(long tick) {
            return new Frame(rotation, shift, planet, displacement(tick), drift, tick);
        }

        /** Absorb Level motion into universe-axis drift without moving positions at this tick. */
        public Frame folded(Point levelVelocity, long tick) {
            return new Frame(
                    rotation,
                    shift,
                    planet,
                    displacement(tick),
                    drift.add(universeVector(levelVelocity)),
                    tick);
        }

        /** Damp drift about the instantaneous origin, not about the old epoch. */
        public Frame dragged(double perTickFactor, long tick) {
            if (!Double.isFinite(perTickFactor) || perTickFactor < 0 || perTickFactor > 1)
                throw new IllegalArgumentException("Space drag");
            return new Frame(
                    rotation, shift, planet, displacement(tick), drift.scale(perTickFactor), tick);
        }

        /** Change reference without changing any Level position or velocity at this tick. */
        public Frame toward(int newPlanet, GroundEphemeris.Model model, double tick) {
            var old = frame(model, planet, tick);
            var next = frame(model, newPlanet, tick);
            var w = drift.add(old.velocity().sub(next.velocity()));
            long epoch = (long) tick;
            return new Frame(
                    rotation,
                    shift,
                    newPlanet,
                    displacement(tick)
                            .add(old.center().sub(next.center()))
                            .sub(w.scale(tick - epoch)),
                    w,
                    epoch);
        }

        public Frame shifted(Point delta) {
            GroundEphemeris.point(delta);
            return new Frame(rotation, shift.add(delta), planet, offset, drift, epoch);
        }

        /** Turn the labels about a Level pivot, leaving that pivot's universe point unchanged. */
        public Frame turned(Quaterniond delta, Point levelPivot) {
            GroundEphemeris.quaternion(delta);
            GroundEphemeris.point(levelPivot);
            var offset = delta.transform(vector(levelPivot.sub(shift)));
            return new Frame(
                    new Quaterniond(delta).mul(rotation).normalize(),
                    levelPivot.sub(new Point(offset.x, offset.y, offset.z)),
                    planet,
                    this.offset,
                    drift,
                    epoch);
        }

        /** Shortest bounded arc from reference-centre radial up to Level +Y; no planetary spin. */
        public static Quaterniond turn(
                Frame frame,
                GroundEphemeris.Model model,
                double tick,
                Point position,
                double maxAngle) {
            if (!Double.isFinite(maxAngle) || maxAngle < 0)
                throw new IllegalArgumentException("Turn angle");
            var up =
                    frame.rotation()
                            .transform(
                                    Space.up(
                                            model,
                                            frame.planet,
                                            frame.universe(position, model, tick),
                                            tick));
            var axis = up.cross(new Vector3d(0, 1, 0), new Vector3d());
            double sine = axis.length(), angle = Math.atan2(sine, up.y);
            if (angle <= 1e-9 || maxAngle == 0) return new Quaterniond();
            if (sine < 1e-15) axis.set(1, 0, 0);
            else axis.div(sine);
            return new Quaterniond()
                    .rotationAxis(Math.min(angle, maxAngle), axis.x, axis.y, axis.z);
        }

        /** Slerp axes, but lerp the eye in universe space: lerping T alone makes the camera orbit the pivot. */
        public static Frame interpolate(
                Frame before,
                Frame after,
                Point eye,
                double partial,
                GroundEphemeris.Model model,
                double tick) {
            double a = Math.max(0, Math.min(1, partial));
            if (a == 0) return before;
            if (a == 1) return after;
            var rotation = before.rotation().slerp(after.rotation, a).normalize();
            var position =
                    before.universe(eye, model, tick)
                            .scale(1 - a)
                            .add(after.universe(eye, model, tick).scale(a));
            var velocity =
                    before.universeVelocity(new Point(0, 0, 0), model, tick)
                            .scale(1 - a)
                            .add(after.universeVelocity(new Point(0, 0, 0), model, tick).scale(a));
            var w = velocity.sub(frame(model, after.planet, tick).velocity());
            long epoch = (long) tick;
            var relative =
                    rotation.transform(
                            vector(
                                    position.sub(frame(model, after.planet, tick).center())
                                            .sub(after.displacement(tick))));
            return new Frame(
                    rotation,
                    eye.sub(new Point(relative.x, relative.y, relative.z)),
                    after.planet,
                    after.displacement(tick).sub(w.scale(tick - epoch)),
                    w,
                    epoch);
        }

        /** An empty region can turn under the departing body, so its deck and heading do not turn under its crew. */
        public static Frame keeping(
                Motion universeMotion,
                Quaterniond levelOrientation,
                GroundEphemeris.Model model,
                int planet,
                double tick) {
            GroundEphemeris.quaternion(levelOrientation);
            var r =
                    new Quaterniond(levelOrientation)
                            .mul(universeMotion.orientation().conjugate())
                            .normalize();
            var reference = frame(model, planet, tick);
            var w = universeMotion.velocity().sub(reference.velocity());
            long epoch = (long) tick;
            return new Frame(
                    r,
                    ORIGIN,
                    planet,
                    universeMotion.position().sub(reference.center()).sub(w.scale(tick - epoch)),
                    w,
                    epoch);
        }
    }

    /** A body's canonical arrival chart and motion, whose position is the chart point (not Level feet).
     * @param pose canonical face and chart point above the destination planet
     * @param motion body motion in that chart's axes
     */
    public record Arrival(PlanetShape.Pose pose, Motion motion) {
        /** Requires the arrival pose and body anchor to describe the same chart point. */
        public Arrival {
            if (pose == null || motion == null || !pose.point().equals(motion.position()))
                throw new IllegalArgumentException("Chart arrival");
        }
    }

    /** Tests the half-open Level square [X-REACH,X+REACH) by [Z-REACH,Z+REACH), at any y. */
    /** Whether an entity is in the region between planets: in one of the mod's levels, at a place the region holds. */
    public static boolean in(net.minecraft.world.entity.Entity entity) {
        return dev.starfold.planet.PlanetLevels.is(entity.level())
                && holds(entity.getX(), entity.getZ());
    }

    public static boolean holds(double x, double z) {
        return x >= X - REACH && x < X + REACH && z >= Z - REACH && z < Z + REACH;
    }

    /** Maps an anchor relative to planet 0's centre into the identity region frame. */
    public static Point level(Point universe, GroundEphemeris.Model model, double tick) {
        return Frame.IDENTITY.level(universe, model, tick);
    }

    /** Inverts the identity region frame at the same ephemeris time. */
    public static Point universe(Point level, GroundEphemeris.Model model, double tick) {
        return Frame.IDENTITY.universe(level, model, tick);
    }

    /** Departure is strict: at its exit height a hull still belongs to the chart. */
    public static boolean leaves(GroundEphemeris.Body body, double height) {
        return height > body.hullOut();
    }

    /** Arrival is strict: at its entry height a hull still belongs to space. */
    public static boolean enters(GroundEphemeris.Body body, double height) {
        return height < body.hullIn();
    }

    /** Finds the nearest active planet centre at this tick; lowest index wins exact ties. */
    public static int nearest(GroundEphemeris.Model model, Point universe, double tick) {
        GroundEphemeris.point(universe);
        int best = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int id = 0; id < model.count(); id++) {
            double d = universe.sub(model.at(id, tick).center()).length();
            if (d < distance) {
                distance = d;
                best = id;
            }
        }
        return best;
    }

    /** Radial height above this shape's nominal surface, including below-surface positions. */
    public static double height(
            GroundEphemeris.Model model,
            int planet,
            PlanetShape shape,
            Point universe,
            double tick) {
        GroundEphemeris.point(universe);
        return universe.sub(frame(model, planet, tick).center()).length() - shape.radius();
    }

    /** Radial height using the destination planet's currently installed shape. */
    public static double height(
            GroundEphemeris.Model model, int planet, Point universe, double tick) {
        return height(model, planet, PlanetShape.of(planet), universe, tick);
    }

    /** Carries a body from its canonical chart to universe axes, preserving motion including planetary drift/spin.
     * Linear transport uses the shape's exact differential, not the orthonormal orientation frame.
     * The pose and chart motion must have the same anchor inside this shape's face.
     */
    public static Motion out(
            GroundEphemeris.Model model,
            int planet,
            PlanetShape shape,
            PlanetShape.Pose pose,
            Motion chartMotion,
            double tick) {
        if (pose == null
                || chartMotion == null
                || !pose.point().equals(chartMotion.position())
                || !shape.owned(pose.point().x(), pose.point().z()))
            throw new IllegalArgumentException("Chart anchor");
        var f = frame(model, planet, tick);
        var local = GroundTopology.planet(shape, pose);
        var axes = f.rotation().mul(PlanetOrbit.frame(shape, pose).rotation()).normalize();
        return new Motion(
                f.world(local),
                new Quaterniond(axes).mul(chartMotion.orientation()).normalize(),
                f.worldVelocity(
                        local, GroundTopology.planetVelocity(shape, pose, chartMotion.velocity())),
                axes.transform(chartMotion.spin()).add(vector(f.angular())));
    }

    /** Carries a body out using its planet's currently installed shape. */
    public static Motion out(
            GroundEphemeris.Model model,
            int planet,
            PlanetShape.Pose pose,
            Motion chartMotion,
            double tick) {
        return out(model, planet, PlanetShape.of(planet), pose, chartMotion, tick);
    }

    /** Inverts universe motion into a canonical chart pose and body motion, without ownership policy or speed resets.
     * At a face seam the canonical face may differ, but converting back preserves the universe motion.
     */
    public static Arrival in(
            GroundEphemeris.Model model,
            int planet,
            PlanetShape shape,
            Motion universeMotion,
            double tick) {
        var f = frame(model, planet, tick);
        var pose = GroundTopology.locate(shape, f.local(universeMotion.position()));
        var inverse =
                f.rotation().mul(PlanetOrbit.frame(shape, pose).rotation()).normalize().conjugate();
        var motion =
                new Motion(
                        pose.point(),
                        new Quaterniond(inverse).mul(universeMotion.orientation()).normalize(),
                        GroundTopology.chartVelocity(
                                shape,
                                pose,
                                f.localVelocity(
                                        universeMotion.position(), universeMotion.velocity())),
                        inverse.transform(universeMotion.spin().sub(vector(f.angular()))));
        return new Arrival(pose, motion);
    }

    /** Attaches universe motion using the destination planet's currently installed shape. */
    public static Arrival in(
            GroundEphemeris.Model model, int planet, Motion universeMotion, double tick) {
        return in(model, planet, PlanetShape.of(planet), universeMotion, tick);
    }

    /** Planet centre's universe velocity in blocks/tick; point-dependent rotational velocity is not included. */
    public static Point drift(GroundEphemeris.Model model, int planet, double tick) {
        return frame(model, planet, tick).velocity();
    }

    /** Radially outward unit vector in universe axes, undefined at the planet centre. */
    public static Vector3d up(
            GroundEphemeris.Model model, int planet, Point universe, double tick) {
        GroundEphemeris.point(universe);
        var delta = universe.sub(frame(model, planet, tick).center());
        double length = delta.length();
        if (!(length > 0)) throw new IllegalArgumentException("Up at planet center");
        return vector(delta.scale(1 / length));
    }

    private static GroundEphemeris.Frame frame(
            GroundEphemeris.Model model, int planet, double tick) {
        if (planet < 0 || planet >= model.count())
            throw new IllegalArgumentException("Inactive planet");
        return model.at(planet, tick);
    }

    private static Vector3d vector(Point p) {
        return new Vector3d(p.x(), p.y(), p.z());
    }

    private Space() {}
}
