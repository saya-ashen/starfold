package dev.starfold.ship;

import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.sky.Space;
import org.joml.Vector3d;

/** Paired PRE/POST gravity impulses and per-tick hull damping and fluid support. */
final class HullPhysics {
    static void clear() {
        WEIGHING.clear();
        FLOATING.clear();
    }

    /** Position weight of Sable 2.0.5's native step: two 0.025 s sub-steps, gravity 11.
     *  PRE-only rose 0.130005 blocks/s over 60 s with velocity ~0: a=0.130005/0.275=0.47275.
     *  The 42 sub-step pose measurements averaged 0.47267 (spread 0.0236 from float quantisation).
     *  tools/probe/rocket-hover.txt re-measures the drift this cancellation is meant to remove. */
    static final double POSITION_WEIGHT = 0.47275;

    /** POST consumes PRE's world impulse snapshot: same lost weight, mass, gravity, drag and dt in both phases. */
    private static final java.util.Map<
                    dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem,
                    java.util.Map<dev.ryanhcode.sable.sublevel.ServerSubLevel, Vector3d>>
            WEIGHING = new java.util.WeakHashMap<>();

    private static final java.util.Map<dev.ryanhcode.sable.sublevel.ServerSubLevel, Double>
            FLOATING = new java.util.WeakHashMap<>();

    static void weighPre(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics, double step) {
        WEIGHING.remove(physics);
        var level = physics.getLevel();
        if (!PlanetLevels.is(level)) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;
        var pending =
                new java.util.IdentityHashMap<
                        dev.ryanhcode.sable.sublevel.ServerSubLevel, Vector3d>();
        double c =
                dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                                .getUniversalDrag(level)
                        * step;
        for (var body : container.getAllSubLevels()) {
            if (body.isRemoved() || pending.containsKey(body)) continue;
            var at = body.logicalPose().position();
            double lost =
                    1
                            - HullWeight.gravity(
                                            level, new BentStripGeometry.Point(at.x, at.y, at.z))
                                    * LiftControl.HULL_GRAVITY;
            double mass = body.getMassTracker().getMass();
            var impulses =
                    weighPair(
                            dev.ryanhcode.sable.physics.config.dimension_physics
                                    .DimensionPhysicsData.getGravity(level),
                            lost,
                            mass,
                            step,
                            c);
            pending.put(body, impulses.post());
            if (lost != 0) weigh(physics, body, impulses.pre());
            LiftServer.pre(physics, body, step, c);
            // Only actual fluid contact may supply external support; no airborne velocity clamp.
            land(level, container, body, false, mass > 0 ? impulses.pre().y / mass : 0);
        }
        WEIGHING.put(physics, pending);
    }

    static void weighPost(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics) {
        var pending = WEIGHING.remove(physics);
        if (pending == null) return;
        for (var entry : pending.entrySet()) {
            var body = entry.getKey();
            Double floor = FLOATING.remove(body);
            if (body.isRemoved()) continue;
            if (entry.getValue().lengthSquared() > 0) weigh(physics, body, entry.getValue());
            LiftServer.post(physics, body);
            // Enforce zero downward displacement too: native float integration can creep despite
            // zero speed.
            // This is the preceding submerged pose, never a lift to the water height (including a
            // sunken start).
            if (floor != null) {
                var at = body.logicalPose().position();
                if (at.y < floor)
                    physics.getPipeline()
                            .teleport(
                                    body,
                                    new Vector3d(at.x, HullLanding.supportedY(floor, at.y), at.z),
                                    body.logicalPose().orientation());
            }
        }
    }

    record WeightPair(Vector3d pre, Vector3d post) {}

    static WeightPair weighPair(Vector3d gravity, double lost, double mass, double step, double c) {
        var G = new Vector3d(gravity).mul(-lost * mass * step);
        var p =
                new Vector3d(G)
                        .mul(
                                (1 - POSITION_WEIGHT)
                                        / (POSITION_WEIGHT * (1 + c) + (1 - POSITION_WEIGHT)));
        var q = new Vector3d(G).sub(p).div(1 + c);
        return new WeightPair(p, q);
    }

    private static void weigh(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            Vector3d impulse) {
        physics.getPhysicsHandle(body)
                .applyLinearImpulse(
                        body.logicalPose().transformNormalInverse(impulse, new Vector3d()));
    }

    /** Hold drift once per game tick, outside gravity; let upward thrust gather climb speed, not horizontal speed. */
    static void damp(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var at = body.logicalPose().position();
        double weight = HullWeight.at(level, new BentStripGeometry.Point(at.x, at.y, at.z));
        boolean steadied = Thrusters.steadied(body, level.getGameTime());
        if (steadied && weight > 0) {
            var held = container.physicsSystem().getPipeline();
            boolean chart =
                    !Space.holds(at.x, at.z)
                            && PlanetAccess.of(level)
                                    .inChart(
                                            net.minecraft.core.BlockPos.containing(
                                                    at.x, GroundCoordinates.FLOOR, at.z));
            var push = Thrusters.push(body, level.getGameTime());
            held.addLinearAndAngularVelocity(
                    body,
                    drift(
                            held.getLinearVelocity(body, new Vector3d()),
                            weight,
                            chart
                                    ? horizontalHold(body.logicalPose().orientation(), push)
                                    : Thrusters.HOLD),
                    new Vector3d());
        }
    }

    /** Unlike drift holding, landing protection also works above the air and without a pilot. */
    static void land(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            boolean sample) {
        land(level, container, body, sample, 0);
    }

    static void land(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            boolean sample,
            double weightCancellation) {
        var at = body.logicalPose().position();
        if (Space.holds(at.x, at.z)
                || !Thrusters.steadied(body, level.getGameTime())
                || !PlanetAccess.of(level)
                        .inChart(
                                net.minecraft.core.BlockPos.containing(
                                        at.x, GroundCoordinates.FLOOR, at.z))) return;
        var box = HullLanding.bounds(body);
        var ground = HullLanding.ground(body);
        double clearance = ground.clearance();
        var pipeline = container.physicsSystem().getPipeline();
        var velocity = pipeline.getLinearVelocity(body, new Vector3d());
        // At fluid contact preserve the PRE weight cancellation: native gravity has yet to
        // integrate it.
        if (!ground.fluid() || clearance > 0) return;
        double support = weightCancellation;
        if (support > 0) FLOATING.put(body, at.y);
        double allowed = -support;
        if (allowed < -velocity.y)
            pipeline.addLinearAndAngularVelocity(
                    body, new Vector3d(0, -allowed - velocity.y, 0), new Vector3d());
        if (sample && Boolean.getBoolean("starfold.carryProbe") && level.getGameTime() % 10 == 0)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE LAND tick {} pos {} {} {} bottom {} clearance {} vy {} allowed {} horizontal {} up {}",
                            level.getGameTime(),
                            at.x,
                            at.y,
                            at.z,
                            box.minY(),
                            clearance,
                            velocity.y,
                            allowed,
                            Math.hypot(velocity.x, velocity.z),
                            body.logicalPose().orientation().transform(new Vector3d(0, 1, 0)).y);
    }

    static final double HOLD_STILL = 4;

    /** Read the summed push in the body's axes: deck tilt is not a request to cruise sideways. */
    static double horizontalHold(org.joml.Quaterniondc orientation, BentStripGeometry.Point push) {
        var local = orientation.transformInverse(new Vector3d(push.x(), push.y(), push.z()));
        return local.lengthSquared() == 0 || Math.hypot(local.x, local.z) < .1 * local.length()
                ? HOLD_STILL
                : Thrusters.HOLD;
    }

    static Vector3d drift(Vector3d velocity, double weight, double horizontalHold) {
        return new Vector3d(velocity.x * horizontalHold, 0, velocity.z * horizontalHold)
                .mul(-weight / 20);
    }

    private HullPhysics() {}
}
