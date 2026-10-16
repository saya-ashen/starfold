package dev.starfold.ship;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Body-relative places independent of the level's moving labels. Does not move a hull. */
public final class ShipParking {
    private ShipParking() {}

    public static ShipRecord.Place capture(
            GroundEphemeris.Model model,
            int body,
            Point anchorPlot,
            Point position,
            Quaterniond attitude,
            long tick) {
        GroundEphemeris.quaternion(attitude);
        var frame = model.frame(body, tick);
        return new ShipRecord.Place(
                body,
                anchorPlot,
                frame.local(position),
                frame.rotation().conjugate().mul(attitude).normalize(),
                tick);
    }

    /** Velocities are blocks/tick and radians/tick in universe axes. */
    public static Space.Motion evaluate(
            GroundEphemeris.Model model, ShipRecord.Place place, double tick) {
        var frame = model.frame(place.body(), tick);
        var angular = frame.angular();
        return new Space.Motion(
                frame.world(place.position()),
                frame.rotation().mul(place.attitude()).normalize(),
                frame.worldVelocity(place.position(), GroundEphemeris.ZERO),
                new Vector3d(angular.x(), angular.y(), angular.z()));
    }

    public static ShipRecord.Place fromLevel(
            GroundEphemeris.Model model,
            Space.Frame frame,
            int body,
            Point anchorPlot,
            Point position,
            Quaterniond attitude,
            long tick) {
        return capture(
                model,
                body,
                anchorPlot,
                frame.universe(position, model, tick),
                frame.universeOrientation(attitude),
                tick);
    }

    public static Space.Motion toLevel(
            GroundEphemeris.Model model, Space.Frame frame, ShipRecord.Place place, double tick) {
        return frame.level(evaluate(model, place, tick), model, tick);
    }

    // At capture the half-residual correction is at most .01 blocks/tick and .000005 rad/tick.
    // The remaining excursion is under .02 block / .001 degree before returning to the place.
    public static final double CAPTURE_SPEED = .02, CAPTURE_SPIN = .00001;

    public static boolean slow(Space.Motion motion, Space.Motion target) {
        return motion.velocity().sub(target.velocity()).length() <= CAPTURE_SPEED
                && motion.spin().sub(target.spin()).length() <= CAPTURE_SPIN;
    }

    /** Motion of the turning body's point at the hull's current position, without a held place. */
    public static Space.Motion coastTarget(
            GroundEphemeris.Model model, int body, Point position, double tick) {
        var frame = model.frame(body, tick);
        var angular = frame.angular();
        return new Space.Motion(
                position,
                new Quaterniond(),
                frame.worldVelocity(frame.local(position), GroundEphemeris.ZERO),
                new Vector3d(angular.x(), angular.y(), angular.z()));
    }

    public static Point decay(Point current, Point target, double factor) {
        if (target.equals(GroundEphemeris.ZERO)) return current.scale(factor);
        return target.add(current.sub(target).scale(factor));
    }

    /** Feed the changing target forward so only the motion error is damped. */
    public static Point decay(Point current, Point target, Point nextTarget, double factor) {
        return decay(current, target, factor).add(nextTarget.sub(target));
    }

    /** Coast without positional feedback; native physics still owns the hull pose. */
    public static Space.Motion coast(
            Space.Motion motion, Space.Motion target, Space.Motion nextTarget, double factor) {
        return new Space.Motion(
                motion.position(),
                motion.orientation(),
                decay(motion.velocity(), target.velocity(), nextTarget.velocity(), factor),
                nextTarget.spin().add(motion.spin().sub(target.spin()).mul(factor)));
    }

    /** Predict the next point from the damped Level velocity, without imposing a pose. */
    public static Space.Motion nextCoastTarget(
            GroundEphemeris.Model model,
            int body,
            Space.Frame frame,
            Point point,
            Point velocity,
            long tick) {
        return coastTarget(
                model, body, frame.universe(point.add(velocity), model, tick + 1), tick + 1);
    }

    /** Nonspinning bodies retain direct drift scaling when their origin matches the region. */
    public static Space.Frame coastFrame(
            Space.Frame frame,
            GroundEphemeris.Model model,
            int body,
            Point point,
            double factor,
            long tick) {
        var target = coastTarget(model, body, frame.universe(point, model, tick), tick);
        var drift = target.velocity().sub(model.at(frame.planet(), tick).velocity());
        var damped = decay(frame.drift(), drift, factor);
        if (target.spin().lengthSquared() != 0) {
            var prediction =
                    new Space.Frame(
                            frame.rotation(),
                            frame.shift(),
                            frame.planet(),
                            frame.rebased(tick).offset(),
                            damped,
                            tick);
            var next = nextCoastTarget(model, body, prediction, point, GroundEphemeris.ZERO, tick);
            var nextDrift = next.velocity().sub(model.at(frame.planet(), tick + 1).velocity());
            damped = damped.add(nextDrift.sub(drift));
        } else if (drift.equals(GroundEphemeris.ZERO)) return frame.dragged(factor, tick);
        var f = frame.rebased(tick);
        return new Space.Frame(f.rotation(), f.shift(), f.planet(), f.offset(), damped, tick);
    }

    /** Per-tick feedback; correction limits are relative to the moving body, not universe speed. */
    public static final double DAMPING = .5, GAIN = .05, MAX_SPEED = .5, MAX_SPIN = .01;

    public static final double YIELD_BLOCKS = 32, YIELD_RADIANS = Math.PI / 4;

    /** Evaluate the recorded material point at the hull's current COM, even if blocks changed. */
    public static Space.Motion target(
            GroundEphemeris.Model model, ShipRecord.Place place, Point anchorPlot, double tick) {
        var target = evaluate(model, place, tick);
        var delta = anchorPlot.sub(place.anchorPlot());
        var offset = target.orientation().transform(new Vector3d(delta.x(), delta.y(), delta.z()));
        var velocity = target.spin().cross(offset, new Vector3d());
        return new Space.Motion(
                target.position().add(new Point(offset.x, offset.y, offset.z)),
                target.orientation(),
                target.velocity().add(new Point(velocity.x, velocity.y, velocity.z)),
                target.spin());
    }

    /** Shortest rotation vector in the same axes as the two orientations. */
    public static Point angle(Quaterniond from, Quaterniond to) {
        var q = new Quaterniond(to).mul(new Quaterniond(from).conjugate()).normalize();
        if (q.w < 0) q.set(-q.x, -q.y, -q.z, -q.w);
        double length = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
        if (length < 1e-15) return GroundEphemeris.ZERO;
        double scale = 2 * Math.atan2(length, q.w) / length;
        return new Point(q.x * scale, q.y * scale, q.z * scale);
    }

    /** A large displacement yields to a collision or push rather than towing back through it. */
    public static boolean yields(Point position, Quaterniond attitude, Space.Motion target) {
        return position.sub(target.position()).length() > YIELD_BLOCKS
                || angle(attitude, target.orientation()).length() > YIELD_RADIANS;
    }

    public static Point correction(Point error, Point residual, double limit) {
        var speed = error.scale(GAIN).add(residual.scale(DAMPING));
        double length = speed.length();
        return length <= limit ? speed : speed.scale(limit / length);
    }

    /** Feed forward the recorded next point; damp errors without imposing a live pose. */
    public static Space.Motion settle(
            Space.Motion motion, Space.Motion target, Space.Motion nextTarget) {
        var velocity = nextTarget.position().sub(target.position());
        var spin = angle(target.orientation(), nextTarget.orientation());
        var residual = motion.spin().sub(target.spin());
        var turn =
                spin.add(
                        correction(
                                angle(motion.orientation(), target.orientation()),
                                new Point(residual.x, residual.y, residual.z),
                                MAX_SPIN));
        return new Space.Motion(
                motion.position(),
                motion.orientation(),
                velocity.add(
                        correction(
                                target.position().sub(motion.position()),
                                motion.velocity().sub(target.velocity()),
                                MAX_SPEED)),
                new Vector3d(turn.x(), turn.y(), turn.z()));
    }

    /** The lead's Level velocity is already folded into drift. Change speed, not its current point. */
    public static Space.Frame settleFrame(
            Space.Frame frame, GroundEphemeris.Model model, Point velocity, long tick) {
        var f = frame.rebased(tick);
        var step = model.at(f.planet(), tick + 1).center().sub(model.at(f.planet(), tick).center());
        return new Space.Frame(
                f.rotation(), f.shift(), f.planet(), f.offset(), velocity.sub(step), tick);
    }

    /** Turning the labels carries the lead's attitude; other hulls are relabelled separately. */
    public static Space.Frame turnFrame(Space.Frame frame, Point turn, Point pivot) {
        double angle = turn.length();
        if (angle == 0) return frame;
        var axis = frame.levelVector(turn.scale(-1 / angle));
        return frame.turned(
                new Quaterniond().rotationAxis(angle, axis.x(), axis.y(), axis.z()), pivot);
    }

    /** Distance to the nominal sphere; exact ties keep the first catalog body. */
    public static int nearestBody(GroundEphemeris.Model model, Point position, double tick) {
        GroundEphemeris.point(position);
        int nearest = -1;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < model.bodies().size(); i++) {
            var body = model.bodies().get(i);
            double radius = body.slot() >= 0 ? PlanetShape.of(body.slot()).radius() : body.radius();
            double gap = position.sub(model.frame(i, tick).center()).length() - radius;
            if (gap < distance) {
                distance = gap;
                nearest = i;
            }
        }
        return nearest;
    }
}
