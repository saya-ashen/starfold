package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Analytic ephemeris in blocks and simulation ticks (20 ticks/second), never wall time. */
public final class GroundEphemeris {
    public static final long MAX_TICK = 1_000_000_000_000L;
    public static final Point ZERO = new Point(0, 0, 0), Y = new Point(0, 1, 0);

    /** Fixed body-local axis; frozen pose preserves continuity when rotation is paused. */
    public record Spin(
            boolean enabled,
            Point axis,
            Quaterniond reference,
            double phase,
            double period,
            long epoch,
            Quaterniond frozen) {
        public Spin(
                boolean enabled,
                Point axis,
                Quaterniond reference,
                double phase,
                double period,
                long epoch) {
            this(enabled, axis, reference, phase, period, epoch, new Quaterniond());
        }

        public Spin {
            unit(axis);
            quaternion(reference);
            quaternion(frozen);
            finite(phase);
            if (period < (enabled ? 24000 : 20)
                    || period > 1e9
                    || !Double.isFinite(period)
                    || epoch < 0
                    || epoch > MAX_TICK) throw new IllegalArgumentException("Spin parameters");
            reference = new Quaterniond(reference);
            frozen = new Quaterniond(frozen);
        }

        @Override
        public Quaterniond reference() {
            return new Quaterniond(reference);
        }

        @Override
        public Quaterniond frozen() {
            return new Quaterniond(frozen);
        }

        public Quaterniond at(double tick) {
            time(tick);
            return enabled
                    ? reference()
                            .mul(
                                    new Quaterniond()
                                            .rotationAxis(
                                                    phase
                                                            + 2
                                                                    * Math.PI
                                                                    * ((tick - epoch) % period)
                                                                    / period,
                                                    axis.x(),
                                                    axis.y(),
                                                    axis.z()))
                            .normalize()
                    : frozen();
        }

        public Point angular() {
            return enabled ? rotate(reference, axis).scale(2 * Math.PI / period) : ZERO;
        }

        public Spin toggle(boolean on, long tick, double slowPeriod) {
            return new Spin(on, axis, at(tick), 0, slowPeriod, tick, at(tick));
        }
    }

    /** A further planet of a system, on its own circle about its own centre. */
    public record Orbit(
            Point center,
            Point axis,
            Point radial,
            double radius,
            double period,
            double phase,
            long epoch,
            Spin spin) {
        public Orbit {
            point(center);
            unit(axis);
            unit(radial);
            finite(phase);
            if (Math.abs(axis.dot(radial)) > 1e-9
                    || !Double.isFinite(radius)
                    || radius < 0
                    || radius > 1e7
                    || !Double.isFinite(period)
                    || period < 12000
                    || period > 1e9
                    || epoch < 0
                    || epoch > MAX_TICK
                    || spin == null) throw new IllegalArgumentException("Orbit parameters");
        }

        Frame at(double tick) {
            double omega = 2 * Math.PI / period, angle = phase + omega * ((tick - epoch) % period);
            var offset =
                    rotate(
                                    new Quaterniond()
                                            .rotationAxis(angle, axis.x(), axis.y(), axis.z()),
                                    radial)
                            .scale(radius);
            return new Frame(
                    center.add(offset),
                    spin.at(tick),
                    axis.scale(omega).cross(offset),
                    spin.angular());
        }
    }

    /** A sun: the first one is the model's own, at its centre; any others are further stars with planets about them. */
    public record Star(Point position, double radius) {
        public Star {
            point(position);
            if (!Double.isFinite(radius) || radius < 32 || radius > 4096 || position.length() > 1e9)
                throw new IllegalArgumentException("Star parameters");
        }
    }

    /** The first two planets share one circle, opposite each other, as the system began; any further ones are in more. */
    public record Model(
            Point center,
            Point axis,
            Point radial,
            double radius,
            double period,
            double phase,
            long epoch,
            Spin a,
            Spin b,
            GroundSolar.Sun sun,
            boolean multiple,
            java.util.List<Orbit> more,
            java.util.List<Star> stars) {
        public Model(
                Point center,
                Point axis,
                Point radial,
                double radius,
                double period,
                double phase,
                long epoch,
                Spin a,
                Spin b,
                GroundSolar.Sun sun,
                boolean multiple,
                java.util.List<Orbit> more) {
            this(
                    center,
                    axis,
                    radial,
                    radius,
                    period,
                    phase,
                    epoch,
                    a,
                    b,
                    sun,
                    multiple,
                    more,
                    java.util.List.of());
        }

        public Model(
                Point center,
                Point axis,
                Point radial,
                double radius,
                double period,
                double phase,
                long epoch,
                Spin a,
                Spin b,
                GroundSolar.Sun sun,
                boolean multiple) {
            this(
                    center,
                    axis,
                    radial,
                    radius,
                    period,
                    phase,
                    epoch,
                    a,
                    b,
                    sun,
                    multiple,
                    java.util.List.of());
        }

        public Model(
                Point center,
                Point axis,
                Point radial,
                double radius,
                double period,
                double phase,
                long epoch,
                Spin a,
                Spin b,
                GroundSolar.Sun sun) {
            this(center, axis, radial, radius, period, phase, epoch, a, b, sun, false);
        }

        public Model(
                Point center,
                Point axis,
                Point radial,
                double radius,
                double period,
                double phase,
                long epoch,
                Spin a,
                Spin b) {
            this(center, axis, radial, radius, period, phase, epoch, a, b, GroundSolar.Sun.OFF);
        }

        public int count() {
            return sun.enabled() && !multiple ? 1 : 2 + more.size();
        }

        public Model {
            more = java.util.List.copyOf(more);
            stars = java.util.List.copyOf(stars);
            if ((!more.isEmpty() || !stars.isEmpty()) && !multiple)
                throw new IllegalArgumentException("Further planets need a system");
            if (sun == null
                    || sun.enabled()
                            && (sun.position().sub(center).length() > 1e-8
                                    || sun.radius() + GroundOrbit.RADIUS + GroundSpace.MAX
                                            >= radius))
                throw new IllegalArgumentException("Solar orbit parameters");
            point(center);
            unit(axis);
            unit(radial);
            finite(phase);
            if (Math.abs(axis.dot(radial)) > 1e-9
                    || center.length() > 100000
                    || radius < 6000
                    || radius > 8192
                    || period < 12000
                    || period > 1e9
                    || !Double.isFinite(radius)
                    || !Double.isFinite(period)
                    || epoch < 0
                    || epoch > MAX_TICK
                    || a == null
                    || b == null) throw new IllegalArgumentException("Binary orbit parameters");
        }

        public Frame at(int planet, double tick) {
            time(tick);
            if (planet < 0 || planet >= 2 + more.size())
                throw new IllegalArgumentException("Planet");
            if (planet >= 2) return more.get(planet - 2).at(tick);
            double omega = 2 * Math.PI / period;
            double angle = phase + omega * ((tick - epoch) % period) + (planet == 0 ? Math.PI : 0);
            var rotation = new Quaterniond().rotationAxis(angle, axis.x(), axis.y(), axis.z());
            var offset = rotate(rotation, radial).scale(radius);
            var angular = axis.scale(omega);
            var spin = spin(planet);
            return new Frame(
                    center.add(offset), spin.at(tick), angular.cross(offset), spin.angular());
        }

        public Spin spin(int planet) {
            if (planet < 0 || planet >= 2 + more.size())
                throw new IllegalArgumentException("Planet");
            return planet == 0 ? a : planet == 1 ? b : more.get(planet - 2).spin();
        }

        /** No nearer to a place than this is a planet, wherever on its circle it is: told without asking where it is,
         *  which among the planets of a universe is nearly always enough to pass one over. */
        public double least(int planet, Point world) {
            if (planet < 0 || planet >= 2 + more.size())
                throw new IllegalArgumentException("Planet");
            if (planet < 2) return Math.max(0, world.sub(center).length() - radius);
            var orbit = more.get(planet - 2);
            return Math.max(0, world.sub(orbit.center()).length() - orbit.radius());
        }

        /** Flight is confined to this distance from the centre: far enough to reach every planet of the system. */
        public double extent() {
            double extent = GroundTransit.SPACE_RADIUS;
            for (var orbit : more)
                extent =
                        Math.max(
                                extent,
                                orbit.center().sub(center).length() + orbit.radius() + 8192);
            for (var star : stars)
                extent = Math.max(extent, star.position().sub(center).length() + 8192);
            return extent;
        }

        /** Every sun of the model, its own first. */
        public java.util.List<Star> everyStar() {
            var all = new java.util.ArrayList<Star>();
            all.add(new Star(sun.position(), sun.radius()));
            all.addAll(stars);
            return all;
        }

        /** The sun that lights a place: the nearest. */
        public Star star(Point world) {
            Star best = new Star(sun.position(), sun.radius());
            double nearest = best.position().sub(world).length();
            for (var star : stars) {
                double d = star.position().sub(world).length();
                if (d < nearest) {
                    nearest = d;
                    best = star;
                }
            }
            return best;
        }

        /** The fastest any two planets move against each other, and the fastest any of them turns. */
        public double drift() {
            double drift = 4 * Math.PI * radius / period;
            for (var orbit : more)
                drift =
                        Math.max(
                                drift,
                                2 * Math.PI * (radius / period + orbit.radius() / orbit.period()));
            return drift;
        }

        public double turn() {
            double turn = Math.max(a.angular().length(), b.angular().length());
            for (var orbit : more) turn = Math.max(turn, orbit.spin().angular().length());
            return turn;
        }
    }

    public record Frame(Point center, Quaterniond rotation, Point velocity, Point angular) {
        public Frame {
            point(center);
            quaternion(rotation);
            point(velocity);
            point(angular);
            rotation = new Quaterniond(rotation);
        }

        @Override
        public Quaterniond rotation() {
            return new Quaterniond(rotation);
        }

        public Point world(Point local) {
            return center.add(rotate(rotation, local));
        }

        public Point local(Point world) {
            return rotate(new Quaterniond(rotation).conjugate(), world.sub(center));
        }

        public Point vector(Point local) {
            return rotate(rotation, local);
        }

        public Point inverseVector(Point world) {
            return rotate(new Quaterniond(rotation).conjugate(), world);
        }

        public Point worldVelocity(Point localPosition, Point localVelocity) {
            var r = vector(localPosition);
            return velocity.add(angular.cross(r)).add(vector(localVelocity));
        }

        public Point localVelocity(Point worldPosition, Point worldVelocity) {
            return inverseVector(
                    worldVelocity.sub(velocity).sub(angular.cross(worldPosition.sub(center))));
        }
    }

    public static Model defaults() {
        var a = new Spin(false, Y, new Quaterniond(), 0, 24000, 0);
        var b = new Spin(false, Y, new Quaterniond(), 0, 36000, 0);
        return new Model(new Point(0, 0, 8192), Y, new Point(0, 0, 1), 8192, 24000, 0, 0, a, b);
    }

    public static Model spinningDefaults() {
        return spinning(defaults(), true, 0);
    }

    public static Model spinning(Model m, boolean on, long tick) {
        return new Model(
                m.center,
                m.axis,
                m.radial,
                m.radius,
                m.period,
                m.phase,
                m.epoch,
                m.a.toggle(on, tick, 48000),
                m.b.toggle(on, tick, 72000),
                m.sun,
                m.multiple,
                m.more.stream()
                        .map(
                                o ->
                                        new Orbit(
                                                o.center,
                                                o.axis,
                                                o.radial,
                                                o.radius,
                                                o.period,
                                                o.phase,
                                                o.epoch,
                                                o.spin.toggle(on, tick, 72000)))
                        .toList(),
                m.stars);
    }

    public static Point rotate(Quaterniond q, Point p) {
        var v = q.transform(new Vector3d(p.x(), p.y(), p.z()));
        return new Point(v.x, v.y, v.z);
    }

    public static void point(Point p) {
        if (p == null || !p.finite()) throw new IllegalArgumentException("Finite vector required");
    }

    static void unit(Point p) {
        point(p);
        if (Math.abs(p.length() - 1) > 1e-8)
            throw new IllegalArgumentException("Unit axis required");
    }

    public static void quaternion(Quaterniond q) {
        if (q == null
                || !Double.isFinite(q.lengthSquared())
                || Math.abs(q.lengthSquared() - 1) > 1e-6)
            throw new IllegalArgumentException("Unit quaternion required");
    }

    static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite number required");
    }

    public static void time(double t) {
        if (!Double.isFinite(t) || t < 0 || t > MAX_TICK)
            throw new IllegalArgumentException("Simulation time exhausted/invalid");
    }

    private GroundEphemeris() {}
}
