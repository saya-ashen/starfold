package dev.starfold.flight;

import dev.starfold.chart.GroundCameraAim;
import dev.starfold.chart.GroundCosmos;
import dev.starfold.chart.GroundGap;
import dev.starfold.chart.GroundGuidance;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.GroundTransit;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.geometry.UniverseMotion;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetShape;
import dev.starfold.sky.GroundEphemeris;

import org.joml.Quaterniond;

/** Flight above the native handoff only. This kernel never places blocks or simulates ground MC. */
public final class GroundDynamics {
    public static final double HANDOFF = GroundCameraAim.LOOK_START;
    public static final GroundGuidance.Options OFF = new GroundGuidance.Options(0, false, false);

    /** Implementations must query native voxel shapes, not material IDs or a proxy GroundWorld. */
    public interface Terrain {
        boolean clear(GroundTopology.Pose pose);

        double clearance(GroundTopology.Pose pose);

        default boolean clear(int planet, GroundTopology.Pose pose) {
            return clear(pose);
        }

        default double clearance(int planet, GroundTopology.Pose pose) {
            return clearance(pose);
        }
    }

    public record State(
            long tick,
            boolean space,
            Point position,
            Point velocity,
            Quaterniond orientation,
            GroundTopology.Pose local,
            boolean flying,
            boolean braking,
            GroundGuidance.Options guidance,
            int planet) {
        public State(
                long tick,
                boolean space,
                Point position,
                Point velocity,
                Quaterniond orientation,
                GroundTopology.Pose local,
                boolean flying,
                boolean braking,
                GroundGuidance.Options guidance) {
            this(tick, space, position, velocity, orientation, local, flying, braking, guidance, 0);
        }

        public State {
            if (planet < 0) throw new IllegalArgumentException("Planet");
            GroundEphemeris.time(tick);
            GroundEphemeris.point(position);
            GroundEphemeris.point(velocity);
            GroundEphemeris.quaternion(orientation);
            if (position.length() > 200000
                    || velocity.length() > 256
                    || space == (local != null)
                    || guidance == null
                    || braking && guidance.relativeBrake())
                throw new IllegalArgumentException("Native flight state");
            if (!space
                    && GroundTopology.planet(PlanetShape.of(planet), local).sub(position).length()
                            > 1e-7) throw new IllegalArgumentException("Native local ownership");
            orientation = new Quaterniond(orientation);
        }

        @Override
        public Quaterniond orientation() {
            return new Quaterniond(orientation);
        }
    }

    public static State local(
            long tick,
            GroundTopology.Pose pose,
            Point chartVelocity,
            Quaterniond chartAim,
            boolean flying,
            GroundGuidance.Options guidance) {
        return local(tick, 0, pose, chartVelocity, chartAim, flying, guidance);
    }

    public static State local(
            long tick,
            int planet,
            GroundTopology.Pose pose,
            Point chartVelocity,
            Quaterniond chartAim,
            boolean flying,
            GroundGuidance.Options guidance) {
        return localPlanet(
                tick,
                planet,
                pose,
                GroundTopology.planetVelocity(PlanetShape.of(planet), pose, chartVelocity),
                PlanetOrbit.frame(PlanetShape.of(planet), pose)
                        .rotation()
                        .mul(chartAim)
                        .normalize(),
                flying,
                guidance);
    }

    private static State localPlanet(
            long tick,
            int planet,
            GroundTopology.Pose pose,
            Point velocity,
            Quaterniond planetAim,
            boolean flying,
            GroundGuidance.Options guidance) {
        return new State(
                tick,
                false,
                GroundTopology.planet(PlanetShape.of(planet), pose),
                velocity,
                planetAim,
                pose,
                flying,
                false,
                guidance,
                planet);
    }

    private static void active(GroundEphemeris.Model model, State state) {
        if (!model.sun().enabled()
                || state.planet >= model.count()
                || state.guidance.target() >= model.count())
            throw new IllegalArgumentException("Inactive native planet/model");
    }

    public static Point position(GroundEphemeris.Model model, State state, double time) {
        return state.space ? state.position : model.at(state.planet, time).world(state.position);
    }

    public static Point velocity(GroundEphemeris.Model model, State state, double time) {
        return state.space
                ? state.velocity
                : model.at(state.planet, time).worldVelocity(state.position, state.velocity);
    }

    public static Quaterniond orientation(GroundEphemeris.Model model, State state, double time) {
        return state.space
                ? state.orientation()
                : model.at(state.planet, time).rotation().mul(state.orientation()).normalize();
    }

    public static Quaterniond chartAim(State state) {
        if (state.space) throw new IllegalArgumentException("Universe aim needs an epoch");
        return PlanetOrbit.frame(PlanetShape.of(state.planet), state.local)
                .rotation()
                .conjugate()
                .mul(state.orientation())
                .normalize();
    }

    public static Point chartVelocity(State state) {
        if (state.space) throw new IllegalArgumentException("Universe velocity needs attachment");
        return GroundTopology.chartVelocity(
                PlanetShape.of(state.planet), state.local, state.velocity);
    }

    public static State guidance(State state, GroundGuidance.Options options) {
        return new State(
                state.tick,
                state.space,
                state.position,
                state.velocity,
                state.orientation,
                state.local,
                state.flying,
                options.relativeBrake() ? false : state.braking,
                options,
                state.planet);
    }

    public static State detach(GroundEphemeris.Model model, State state) {
        if (state.space) return state;
        return new State(
                state.tick,
                true,
                position(model, state, state.tick),
                velocity(model, state, state.tick),
                orientation(model, state, state.tick),
                null,
                true,
                false,
                state.guidance,
                state.planet);
    }

    public static State attach(GroundEphemeris.Model model, State state) {
        return attach(model, state, state.planet);
    }

    public static State attach(GroundEphemeris.Model model, State state, int planet) {
        if (!state.space || planet < 0 || planet >= model.count())
            throw new IllegalArgumentException("Native attachment");
        var frame = model.at(planet, state.tick);
        var pose = GroundTopology.locate(PlanetShape.of(planet), frame.local(state.position));
        return localPlanet(
                state.tick,
                planet,
                pose,
                frame.localVelocity(state.position, state.velocity),
                frame.rotation().conjugate().mul(state.orientation()).normalize(),
                true,
                state.guidance);
    }

    private static boolean clear(
            GroundEphemeris.Model model, Terrain terrain, Point world, double time) {
        boolean many = model.count() > 1;
        for (int id = 0; id < model.count(); id++) {
            if (many && model.least(id, world) - PlanetShape.of(id).radius() > GroundOrbit.LIMIT)
                continue;
            var local = model.at(id, time).local(world);
            double height = local.length() - PlanetShape.of(id).radius();
            if (height < GroundSpace.MIN + 1) return false;
            if (height > GroundOrbit.LIMIT) {
                if (model.count() == 1) return false;
                continue;
            }
            if (!terrain.clear(id, GroundTopology.locate(PlanetShape.of(id), local))) return false;
        }
        return true;
    }

    public static int select(GroundEphemeris.Model model, Point world, int current, double time) {
        if (model.count() == 1) return 0;
        int best = current;
        double nearest =
                world.sub(model.at(current, time).center()).length() - GroundCosmos.HYSTERESIS;
        for (int id = 0; id < model.count(); id++) {
            if (id == current || model.least(id, world) >= nearest) continue;
            double d = world.sub(model.at(id, time).center()).length();
            if (d < nearest) {
                nearest = d;
                best = id;
            }
        }
        return best;
    }

    private static double height(GroundEphemeris.Model model, Point world, double time) {
        double h = Double.POSITIVE_INFINITY;
        for (int id = 0; id < model.count(); id++) {
            double radius = PlanetShape.of(id).radius();
            if (model.least(id, world) - radius >= h) continue;
            h = Math.min(h, world.sub(model.at(id, time).center()).length() - radius);
        }
        return h;
    }

    public static State resume(
            GroundEphemeris.Model model, Terrain terrain, State saved, long tick) {
        active(model, saved);
        if (saved.tick > tick) throw new IllegalArgumentException("Native vessel epoch/model");
        var state =
                new State(
                        tick,
                        saved.space,
                        saved.position,
                        saved.velocity,
                        saved.orientation,
                        saved.local,
                        saved.flying,
                        saved.braking,
                        saved.guidance,
                        saved.planet);
        if (state.space) {
            if (state.position.sub(model.center()).length() > model.extent()
                    || !clear(model, terrain, state.position, tick))
                throw new IllegalStateException("Saved native vessel occupied");
        } else if (!terrain.clear(state.planet, state.local))
            throw new IllegalStateException("Saved native location occupied");
        return state;
    }

    /** The speed a vessel in space is driven to. Held fast and far from every planet it cruises: as fast as it can
     *  still shed at half its thrust before it reaches the nearest one, so that a star is seconds away and none is hit. */
    public static final double CRUISE = 240;

    public static double cruise(double height, boolean fast) {
        double near = UniverseMotion.speed(height, fast);
        return fast && near >= 64
                ? Math.min(CRUISE, Math.sqrt(GroundTransit.ACCEL * height))
                : near;
    }

    private static Point approach(Point current, Point target) {
        var delta = target.sub(current);
        return current.add(
                delta.scale(Math.min(1, GroundTransit.ACCEL / Math.max(1e-12, delta.length()))));
    }

    /** Flight controls change gradually with height instead of switching at one altitude.
     * Near the ground (weight 0) they are vanilla creative flight: forward/strafe stay level, Space/Shift follow gravity.
     * High up (weight 1) they are direct: thrust along the view, Space/Shift along the camera's up. Between
     * {@link #DIVE_START} and {@link #DIVE_END} the axes turn smoothly from one to the other, so diving at a planet
     * flares into level flight instead of stopping dead, and climbing out tilts into free flight. */
    public static final double DIVE_START = 256, DIVE_END = 1024;

    public static double directness(double height, boolean direct) {
        return direct ? 1 : GroundGap.smooth(DIVE_START, DIVE_END, height);
    }

    /** Level heading of a view, defined for every attitude: looking straight down it is where the top of the screen
     * points (as in vanilla, where W keeps following the yaw), never the normalised remainder of a vanishing projection. */
    static Point heading(Quaterniond q, Point up) {
        var forward = PlanetMotion.direction(q, 0, 0, 1);
        var top = PlanetMotion.direction(q, 0, 1, 0);
        double sink = -forward.dot(up);
        var level =
                forward.sub(up.scale(forward.dot(up)))
                        .add(top.sub(up.scale(top.dot(up))).scale(sink));
        if (level.length() < 1e-6) level = PlanetMotion.direction(q, 1, 0, 0).cross(up);
        return PlanetSpace.unit(level);
    }

    private static Point blend(Point ground, Point direct, double weight) {
        var d = ground.scale(1 - weight).add(direct.scale(weight));
        return d.length() < 1e-9 ? direct : PlanetSpace.unit(d);
    }

    private static Point command(Quaterniond q, PlanetMotion.Input input, Point up, double weight) {
        var forward = PlanetMotion.direction(q, 0, 0, 1);
        var left = PlanetMotion.direction(q, 1, 0, 0);
        var rise = PlanetMotion.direction(q, 0, 1, 0);
        if (weight < 1) {
            var level = heading(q, up);
            forward = blend(level, forward, weight);
            left = blend(up.cross(level), left, weight);
            rise = blend(up, rise, weight);
        }
        var d =
                forward.scale(input.forward())
                        .add(left.scale(input.left()))
                        .add(rise.scale(input.rise()));
        return d.scale(1 / Math.max(1, d.length()));
    }

    private static Point assistedVelocity(
            GroundEphemeris.Model model, Terrain terrain, State state, Point desired, double tick) {
        if (!state.guidance.landing()) return desired;
        int id = select(model, position(model, state, tick), state.planet, tick);
        var frame = model.at(id, tick);
        var p = position(model, state, tick);
        var relative = frame.local(p);
        double h = relative.length() - PlanetShape.of(id).radius();
        if (h >= 2048) return desired;
        var up = PlanetSpace.unit(relative);
        var pose = GroundTopology.locate(PlanetShape.of(id), relative);
        var reference = GroundGuidance.frameVelocity(model, id, p, tick);
        var v = frame.inverseVector(desired.sub(reference));
        return reference.add(
                frame.vector(GroundGuidance.limitVelocity(v, up, terrain.clearance(id, pose))));
    }

    private static int steps(GroundEphemeris.Model model, Point velocity) {
        double motion =
                velocity.length()
                        + model.drift()
                        + model.turn() * (GroundOrbit.RADIUS + GroundOrbit.LIMIT);
        return Math.max(1, (int) Math.ceil(motion / .2));
    }

    public static State step(
            GroundEphemeris.Model model,
            Terrain terrain,
            State old,
            PlanetMotion.Input input,
            boolean toggle) {
        active(model, old);
        if (!old.space && old.local.point().y() <= HANDOFF)
            throw new IllegalStateException("Native MC owns movement below handoff");
        long tick = old.tick + 1;
        GroundEphemeris.time(tick);
        boolean thrust =
                Math.abs(input.forward()) + Math.abs(input.left()) + Math.abs(input.rise()) > 1e-6;
        if (toggle || thrust) old = guidance(old, old.guidance.manual());
        if (!old.space) {
            var frame = model.at(old.planet, tick);
            var q = frame.rotation().conjugate().mul(input.orientation()).normalize();
            var up = PlanetSpace.unit(old.position);
            double height = old.local.point().y(),
                    blend = GroundGap.smooth(HANDOFF, GroundOrbit.END, height);
            double speed =
                    (input.fast() ? 1.2 : .35) * (1 - blend)
                            + UniverseMotion.speed(height, input.fast()) * blend;
            var direction = command(q, input, up, directness(height, input.direct()));
            var desired = direction.scale(speed);
            if (old.guidance.relativeBrake()) {
                var p = position(model, old, old.tick);
                desired =
                        model.at(old.planet, old.tick)
                                .localVelocity(
                                        p,
                                        GroundGuidance.frameVelocity(
                                                model, old.guidance.target(), p, old.tick));
            }
            if (old.guidance.landing())
                desired =
                        GroundGuidance.limitVelocity(
                                desired, up, terrain.clearance(old.planet, old.local));
            var v = approach(old.velocity, desired);
            var p = old.position;
            int count = steps(model, v);
            var part = v.scale(1.0 / count);
            for (int i = 0; i < count; i++) {
                var next = p.add(part);
                double t = old.tick + (i + 1.0) / count;
                if (!clear(model, terrain, model.at(old.planet, t).world(next), t)) {
                    v = GroundEphemeris.ZERO;
                    break;
                }
                p = next;
            }
            if (!clear(model, terrain, frame.world(p), tick))
                throw new IllegalStateException("Native moving terrain collision");
            var pose = GroundTopology.locate(PlanetShape.of(old.planet), p);
            var result = localPlanet(tick, old.planet, pose, v, q, true, old.guidance);
            return pose.point().y() > GroundTransit.EXIT ? detach(model, result) : result;
        }
        var q = input.orientation();
        var direction = command(q, input, GroundEphemeris.Y, 1);
        boolean brake = thrust ? false : toggle ? !old.braking : old.braking;
        double height = height(model, old.position, old.tick);
        var desired =
                thrust
                        ? direction.scale(cruise(height, input.fast()))
                        : brake
                                ? GroundEphemeris.ZERO
                                : old.guidance.relativeBrake()
                                        ? GroundGuidance.frameVelocity(
                                                model,
                                                old.guidance.target(),
                                                old.position,
                                                old.tick)
                                        : old.velocity;
        desired = assistedVelocity(model, terrain, old, desired, old.tick);
        var v = approach(old.velocity, desired);
        var p = old.position;
        int count =
                height - v.length() - model.drift() - 64 > GroundOrbit.LIMIT ? 1 : steps(model, v);
        var part = v.scale(1.0 / count);
        for (int i = 0; i < count; i++) {
            var next = p.add(part);
            double t = old.tick + (i + 1.0) / count;
            if (next.sub(model.center()).length() > model.extent()
                    || !clear(model, terrain, next, t)) {
                v = GroundEphemeris.ZERO;
                break;
            }
            p = next;
        }
        if (!clear(model, terrain, p, tick))
            throw new IllegalStateException("Native moving terrain collision");
        int id = select(model, p, old.planet, tick);
        var result = new State(tick, true, p, v, q, null, true, brake, old.guidance, id);
        return p.sub(model.at(id, tick).center()).length() - PlanetShape.of(id).radius()
                        < GroundTransit.ENTER
                ? attach(model, result, id)
                : result;
    }

    public static GroundGuidance.Reading reading(
            GroundEphemeris.Model model, Terrain terrain, State state, double time) {
        var p = position(model, state, time);
        var v = velocity(model, state, time);
        int id = state.guidance.target();
        var f = model.at(id, time);
        var relative = p.sub(f.center());
        double length = relative.length(), h = length - PlanetShape.of(id).radius();
        var up = length > 1e-9 ? relative.scale(1 / length) : GroundEphemeris.Y;
        double closing = -v.sub(f.velocity()).dot(up),
                speed = v.sub(GroundGuidance.frameVelocity(model, id, p, time)).length(),
                eta = h > 0 && closing > .005 ? h / (closing * 20) : Double.NaN;
        if (!Double.isFinite(eta) || eta > 3600) eta = Double.NaN;
        double gap = h - .901;
        if (h >= GroundSpace.MIN && h <= GroundOrbit.LIMIT)
            gap = terrain.clearance(id, GroundTopology.locate(PlanetShape.of(id), f.local(p)));
        double descent = Math.max(0, closing);
        boolean danger =
                gap < 2048
                        && descent > .2
                        && (gap < descent * 5 + 2
                                || descent * descent / (2 * GroundTransit.ACCEL)
                                        > Math.max(0, gap - 2));
        return new GroundGuidance.Reading(h, speed, closing, eta, descent, gap, danger);
    }

    private GroundDynamics() {}
}
