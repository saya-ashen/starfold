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

    /** Paths use region axes, independent of the parent's rotation. */
    public sealed interface Motion permits Fixed, Orbit {}

    public record Fixed(Point position) implements Motion {
        public Fixed {
            point(position);
        }
    }

    public record Orbit(
            int parent,
            Point axis,
            Point radial,
            double radius,
            double period,
            double phase,
            long epoch,
            Spin spin)
            implements Motion {
        public Orbit {
            unit(axis);
            unit(radial);
            finite(phase);
            if (parent < 0
                    || Math.abs(axis.dot(radial)) > 1e-9
                    || !Double.isFinite(radius)
                    || radius < 0
                    || radius > 1e7
                    || !Double.isFinite(period)
                    || period <= 0
                    || period > 1e9
                    || epoch < 0
                    || epoch > MAX_TICK
                    || spin == null) throw new IllegalArgumentException("Orbit parameters");
        }

        Frame at(Frame parentFrame, double tick) {
            double omega = 2 * Math.PI / period, angle = phase + omega * ((tick - epoch) % period);
            var offset =
                    rotate(
                                    new Quaterniond()
                                            .rotationAxis(angle, axis.x(), axis.y(), axis.z()),
                                    radial)
                            .scale(radius);
            return new Frame(
                    parentFrame.center().add(offset),
                    spin.at(tick),
                    parentFrame.velocity().add(axis.scale(omega).cross(offset)),
                    spin.angular());
        }
    }

    /** How a body is drawn, independent of its lighting and surface-storage capabilities. */
    public enum Appearance {
        SURFACE,
        LIGHT,
        GAS
    }

    /** A surface's slot is its storage address, not its position in the catalog. */
    public record Body(
            Motion motion,
            double radius,
            boolean light,
            int slot,
            double reach,
            Appearance appearance,
            BodyId id,
            dev.starfold.worldgen.PlanetAtmosphere atmosphere,
            double temperatureOffset,
            java.util.Optional<GasGiant> gas) {
        public Body(
                Motion motion,
                double radius,
                boolean light,
                int slot,
                double reach,
                Appearance appearance,
                BodyId id,
                dev.starfold.worldgen.PlanetAtmosphere atmosphere) {
            this(
                    motion,
                    radius,
                    light,
                    slot,
                    reach,
                    appearance,
                    id,
                    atmosphere,
                    0,
                    java.util.Optional.empty());
        }

        public Body(
                Motion motion,
                double radius,
                boolean light,
                int slot,
                double reach,
                Appearance appearance,
                BodyId id) {
            this(
                    motion,
                    radius,
                    light,
                    slot,
                    reach,
                    appearance,
                    id,
                    dev.starfold.worldgen.PlanetAtmosphere.NONE);
        }

        public Body withAtmosphere(dev.starfold.worldgen.PlanetAtmosphere atmosphere) {
            return new Body(
                    motion, radius, light, slot, reach, appearance, id, atmosphere, temperatureOffset, gas);
        }

        public Body withEnvironment(dev.starfold.worldgen.PlanetEnvironment environment) {
            return new Body(
                    motion, radius, light, slot, reach, appearance, id,
                    environment.atmosphere(), environment.temperatureOffset(), gas);
        }

        public static Body gas(Motion motion, double radius, GasGiant policy) {
            return new Body(
                    motion,
                    radius,
                    false,
                    -1,
                    0,
                    Appearance.GAS,
                    null,
                    dev.starfold.worldgen.PlanetAtmosphere.NONE,
                    0,
                    java.util.Optional.of(policy));
        }

        /** Unassigned templates receive home identities when first made into a catalog. */
        public Body(
                Motion motion,
                double radius,
                boolean light,
                int slot,
                double reach,
                Appearance appearance) {
            this(motion, radius, light, slot, reach, appearance, null);
        }

        public Body identified(BodyId id) {
            return new Body(
                    motion,
                    radius,
                    light,
                    slot,
                    reach,
                    appearance,
                    java.util.Objects.requireNonNull(id),
                    atmosphere,
                    temperatureOffset,
                    gas);
        }

        public static final double DEFAULT_REACH = 2048;

        /** Generated surfaces use the standard departure height; lights have no surface band. */
        public Body(Motion motion, double radius, boolean light, int slot, Appearance appearance) {
            this(motion, radius, light, slot, slot < 0 ? 0 : DEFAULT_REACH, appearance);
        }

        public Body {
            if (atmosphere == null
                    || gas == null
                    || motion == null
                    || appearance == null
                    || (appearance == Appearance.SURFACE && slot < 0)
                    || (appearance == Appearance.LIGHT && !light)
                    || (appearance == Appearance.GAS
                            && (light
                                    || slot != -1
                                    || !atmosphere.equals(
                                            dev.starfold.worldgen.PlanetAtmosphere.NONE)))
                    || (appearance == Appearance.GAS) != gas.isPresent()
                    || !Double.isFinite(radius)
                    || radius < 0
                    || (light && (radius < 32 || radius > 16384))
                    || slot < -1
                    || !Double.isFinite(reach)
                    || (slot < 0 ? reach != 0 : reach < 512 || reach > DEFAULT_REACH)
                    || (!light && slot < 0 && appearance != Appearance.GAS))
                throw new IllegalArgumentException("Body parameters");
            gas.ifPresent(policy -> policy.validateRadius(radius));
        }

        /** Heights above the nominal surface sphere, for handover and hull weight. */
        public double hullOut() {
            return reach;
        }

        public double hullIn() {
            return .75 * reach;
        }

        public double flightExit() {
            return 2 * reach;
        }

        public double flightEnter() {
            return 1.5 * reach;
        }

        public double weightNone() {
            return 1.5 * reach;
        }

        public double weightFull() {
            return .5 * reach;
        }
    }

    /** Evaluated luminous sphere, used by lighting and sky rendering. */
    public record Star(Point position, double radius) {
        public Star {
            point(position);
            if (!Double.isFinite(radius) || radius < 32 || radius > 16384 || position.length() > 1e9)
                throw new IllegalArgumentException("Star parameters");
        }
    }

    private static final Spin STILL = new Spin(false, Y, new Quaterniond(), 0, 24000, 0);

    /** Immutable bodies in dependency order, with a separately indexed surface view. */
    public static final class Model {
        private final java.util.List<Body> bodies;
        private final int[] slots;
        private final java.util.Map<BodyId, Integer> identities = new java.util.HashMap<>();
        private final Point[] anchors;
        private final double[] reaches, speeds;
        private final double drift, turn, extent;

        /** The lights when none of them moves, so that asking for them every frame builds nothing. */
        private final java.util.List<Star> still;

        private final java.util.List<Star> visibleStars;

        public Model(java.util.List<Body> bodies) {
            if (bodies.size() > 3264
                    || bodies.stream().filter(b -> b.appearance() == Appearance.GAS).count()
                            > GasGiant.MAX_COUNT)
                throw new IllegalArgumentException("Celestial catalog capacity");
            var assigned = new java.util.ArrayList<Body>(bodies.size());
            int next =
                    bodies.stream()
                            .filter(b -> b.id() != null && b.id().region() == 0)
                            .mapToInt(b -> b.id().index())
                            .max()
                            .orElse(-1);
            for (var body : bodies) {
                if (body.id() == null)
                    body = body.identified(new BodyId(0, Math.incrementExact(next)));
                if (body.id().region() == 0) next = Math.max(next, body.id().index());
                if (identities.putIfAbsent(body.id(), assigned.size()) != null)
                    throw new IllegalArgumentException("Duplicate body identity");
                assigned.add(body);
            }
            this.bodies = java.util.List.copyOf(assigned);
            bodies = this.bodies;
            if (bodies.stream().noneMatch(Body::light))
                throw new IllegalArgumentException("A celestial model requires a light");
            slots = new int[(int) bodies.stream().filter(b -> b.slot() >= 0).count()];
            java.util.Arrays.fill(slots, -1);
            anchors = new Point[bodies.size()];
            reaches = new double[bodies.size()];
            speeds = new double[bodies.size()];
            double fastest = 0, turning = 0;
            for (int i = 0; i < bodies.size(); i++) {
                var body = bodies.get(i);
                if (body.slot() >= 0) {
                    if (body.slot() >= slots.length || slots[body.slot()] != -1)
                        throw new IllegalArgumentException(
                                "Surface slots must be unique and contiguous");
                    slots[body.slot()] = i;
                }
                if (body.motion() instanceof Fixed f) anchors[i] = f.position();
                else {
                    var o = (Orbit) body.motion();
                    if (o.parent() >= i)
                        throw new IllegalArgumentException("Orbit parent must be an earlier body");
                    anchors[i] = anchors[o.parent()];
                    reaches[i] = reaches[o.parent()] + o.radius();
                    speeds[i] = speeds[o.parent()] + o.radius() / o.period();
                    if (body.slot() >= 0) turning = Math.max(turning, o.spin().angular().length());
                }
                if (body.gas().isPresent()
                        && anchors[i].length()
                                        + reaches[i]
                                        + body.radius()
                                        + body.gas().orElseThrow().outerHeight()
                                > 1e9)
                    throw new IllegalArgumentException(
                            "Gas body envelope outside celestial bounds");
                if (body.slot() >= 0) fastest = Math.max(fastest, speeds[i]);
            }
            drift = 4 * Math.PI * fastest;
            turn = turning;
            double boundary = GroundTransit.SPACE_RADIUS;
            for (int i = 0; i < bodies.size(); i++) {
                var body = bodies.get(i);
                double shell = body.gas().map(g -> body.radius() + g.outerHeight()).orElse(0.0);
                boundary =
                        Math.max(
                                boundary,
                                anchors[i].sub(origin()).length()
                                        + reaches[i]
                                        + Math.max(8192, shell));
            }
            extent = boundary;
            still =
                    bodies.stream().allMatch(b -> !b.light() || b.motion() instanceof Fixed)
                            ? lights(0)
                            : null;
            var drawn = new java.util.ArrayList<Star>();
            for (int i = 0; i < bodies.size(); i++)
                if (bodies.get(i).appearance() == Appearance.LIGHT)
                    drawn.add(new Star(frame(i, 0).center(), bodies.get(i).radius()));
            visibleStars = java.util.List.copyOf(drawn);
        }

        public java.util.List<Body> bodies() {
            return bodies;
        }

        /** Surface-storage count, NOT the number of catalog bodies. */
        public int count() {
            return slots.length;
        }

        /** Catalog-index-only traversal; never pass these indices to body(slot) or at(slot). */
        public java.util.List<Integer> catalogIndices() {
            return java.util.stream.IntStream.range(0, bodies.size()).boxed().toList();
        }

        public Body catalogBody(int index) {
            if (index < 0 || index >= bodies.size())
                throw new IllegalArgumentException("Catalog body index");
            return bodies.get(index);
        }

        public int index(int slot) {
            if (slot < 0 || slot >= slots.length) throw new IllegalArgumentException("Planet");
            return slots[slot];
        }

        public int index(BodyId id) {
            var index = identities.get(id);
            if (index == null) throw new IllegalArgumentException("Unknown body identity " + id);
            return index;
        }

        public int slot(BodyId id) {
            int slot = bodies.get(index(id)).slot();
            if (slot < 0) throw new IllegalArgumentException("Body has no surface " + id);
            return slot;
        }

        public BodyId id(int slot) {
            return body(slot).id();
        }

        public Body body(int slot) {
            return bodies.get(index(slot));
        }

        public Orbit orbit(int slot) {
            return (Orbit) body(slot).motion();
        }

        /** The fixed root of the home surface, used as the flight boundary and discovery origin. */
        public Point origin() {
            return anchors[slots.length == 0 ? 0 : slots[0]];
        }

        public Frame frame(int index, double tick) {
            time(tick);
            var motion = bodies.get(index).motion();
            if (motion instanceof Fixed f)
                return new Frame(f.position(), new Quaterniond(), ZERO, ZERO);
            var orbit = (Orbit) motion;
            return orbit.at(frame(orbit.parent(), tick), tick);
        }

        public Frame at(int slot, double tick) {
            return frame(index(slot), tick);
        }

        public Frame parent(int slot, double tick) {
            return frame(orbit(slot).parent(), tick);
        }

        public Spin spin(int slot) {
            return body(slot).motion() instanceof Orbit o ? o.spin() : STILL;
        }

        /** A whole ancestor envelope gives a lower bound without evaluating the orbit. */
        public double least(int slot, Point world) {
            int i = index(slot);
            return Math.max(0, world.sub(anchors[i]).length() - reaches[i]);
        }

        public double extent() {
            return extent;
        }

        /** Maximum centre travel per tick, including every ancestor orbit. */
        public double speed(int slot) {
            return 2 * Math.PI * speeds[index(slot)];
        }

        public double drift() {
            return drift;
        }

        public double turn() {
            return turn;
        }

        public java.util.List<Star> everyStar() {
            return everyStar(0);
        }

        public java.util.List<Star> everyStar(double tick) {
            return still != null ? still : lights(tick);
        }

        private java.util.List<Star> lights(double tick) {
            var stars = new java.util.ArrayList<Star>();
            for (int i = 0; i < bodies.size(); i++)
                if (bodies.get(i).light())
                    stars.add(new Star(frame(i, tick).center(), bodies.get(i).radius()));
            return java.util.List.copyOf(stars);
        }

        /** Spheres selected for drawing, at the same reference tick as everyStar(). */
        public java.util.List<Star> visibleStars() {
            return visibleStars;
        }

        /** The first light is the primary source; equal nearest distances retain catalog order. */
        public Star primary() {
            return everyStar().getFirst();
        }

        public Star star(Point world) {
            return star(world, 0);
        }

        public Star star(Point world, double tick) {
            Star best = null;
            double nearest = Double.POSITIVE_INFINITY;
            for (var star : everyStar(tick)) {
                double distance = star.position().sub(world).length();
                if (best == null || distance < nearest) {
                    best = star;
                    nearest = distance;
                }
            }
            return best;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Model m && bodies.equals(m.bodies);
        }

        @Override
        public int hashCode() {
            return bodies.hashCode();
        }

        @Override
        public String toString() {
            return "Model[bodies=" + bodies + "]";
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

    public static Model spinning(Model model, boolean on, long tick) {
        return new Model(
                model.bodies().stream()
                        .map(
                                body -> {
                                    if (!(body.motion() instanceof Orbit o)
                                            || (body.slot() < 0
                                                    && body.appearance() != Appearance.GAS))
                                        return body;
                                    var motion =
                                            new Orbit(
                                                    o.parent(),
                                                    o.axis(),
                                                    o.radial(),
                                                    o.radius(),
                                                    o.period(),
                                                    o.phase(),
                                                    o.epoch(),
                                                    o.spin()
                                                            .toggle(
                                                                    on,
                                                                    tick,
                                                                    body.appearance()
                                                                                    == Appearance
                                                                                            .GAS
                                                                            ? o.spin().period()
                                                                            : body.slot() == 0
                                                                                    ? 48000
                                                                                    : 72000));
                                    return new Body(
                                            motion,
                                            body.radius(),
                                            body.light(),
                                            body.slot(),
                                            body.reach(),
                                            body.appearance(),
                                            body.id(),
                                            body.atmosphere(),
                                            body.temperatureOffset(),
                                            body.gas());
                                })
                        .toList());
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
