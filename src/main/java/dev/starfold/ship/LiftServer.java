package dev.starfold.ship;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.Space;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.joml.Vector3d;

/** Powered-gyro vertical path, evaluated after actors and before each native physics substep.
 * It owns only chart vertical nozzles. A fresh scan makes arrival and gyro removal order-independent. */
final class LiftServer {
    private static final java.util.Map<ServerSubLevel, State> states =
            new java.util.WeakHashMap<>();

    private static final class State {
        double target = Double.NaN, lastY = Double.NaN;
        boolean commanded;
        long sent = Long.MIN_VALUE;
        LiftControl.Output output;
        java.util.List<Jet> pending = java.util.List.of();
    }

    private record Jet(net.minecraft.core.BlockPos pos, Vector3d post) {}

    static boolean enabled(ServerSubLevel body) {
        var at = body.logicalPose().position();
        var level = body.getLevel();
        if (!PlanetLevels.is(level)
                || Space.holds(at.x, at.z)
                || !PlanetAccess.of(level)
                        .inChart(
                                net.minecraft.core.BlockPos.containing(
                                        at.x, GroundCoordinates.FLOOR, at.z))) return false;
        for (var actor : body.getPlot().getBlockEntityActors())
            if (actor instanceof Thrusters.Gyro gyro
                    && !gyro.isRemoved()
                    && gyro.getBlockState().getValue(BlockStateProperties.POWERED)) return true;
        return false;
    }

    static boolean owns(ServerSubLevel body, Direction nozzle) {
        return nozzle.getAxis() == Direction.Axis.Y && enabled(body);
    }

    static int power(ServerSubLevel body, Direction nozzle) {
        var s = states.get(body);
        if (s == null || s.output == null) return 0;
        return (int) Math.ceil(15 * (nozzle == Direction.DOWN ? s.output.lift() : s.output.down()));
    }

    static boolean insufficient(ServerSubLevel body) {
        var s = states.get(body);
        return enabled(body) && s != null && s.output != null && s.output.insufficient();
    }

    static void pre(SubLevelPhysicsSystem physics, ServerSubLevel body, double step, double drag) {
        if (!enabled(body)) {
            if (states.remove(body) != null) publish(body, false, 0, 0, false);
            return;
        }
        var state = states.computeIfAbsent(body, b -> new State());
        var at = body.logicalPose().position();
        double mass = body.getMassTracker().getMass();
        if (mass <= 0) return;
        var handle = physics.getPhysicsHandle(body);
        var velocity = physics.getPipeline().getLinearVelocity(body, new Vector3d());
        var box = HullLanding.bounds(body);
        var ground =
                HullLanding.ground(
                        box.minX(),
                        box.minY(),
                        box.minZ(),
                        box.maxX(),
                        box.maxZ(),
                        (x, z) -> HullLanding.column(body.getLevel(), x, z));
        double gravity = hullGravity(body, at);
        int bits = HelmServer.bits(body);
        boolean command = (bits & (HelmCommand.UP | HelmCommand.DOWN)) != 0;
        boolean capture = captureTarget(state, at.y, command, ground, velocity.y);
        state.commanded = command;
        state.lastY = at.y;
        boolean boost = Thrusters.boosted(body, body.getLevel().getGameTime());
        double force = Thrusters.THRUST * (boost ? Thrusters.BOOST : 1), lift = 0, down = 0;
        var jets = new java.util.ArrayList<Thrusters.Thruster>();
        for (var actor : body.getPlot().getBlockEntityActors())
            if (actor instanceof Thrusters.Thruster jet && !jet.isRemoved()) {
                var nozzle = jet.getBlockState().getValue(BlockStateProperties.FACING);
                if (nozzle.getAxis() != Direction.Axis.Y) continue;
                jets.add(jet);
                double projection = liftProjection(body);
                if (nozzle == Direction.DOWN) lift += force * projection;
                else down += force * projection;
            }
        if (capture)
            state.target =
                    command
                            ? at.y
                            : LiftControl.capture(at.y, velocity.y, gravity, lift / mass - gravity);
        state.output =
                LiftControl.step(
                        mass,
                        gravity,
                        lift,
                        down,
                        at.y,
                        state.target,
                        velocity.y,
                        ground.clearance(),
                        bits,
                        boost,
                        HelmServer.hasPilot(body));
        var pending = new java.util.ArrayList<Jet>();
        for (var jet : jets) {
            var nozzle = jet.getBlockState().getValue(BlockStateProperties.FACING);
            double throttle = nozzle == Direction.DOWN ? state.output.lift() : state.output.down();
            var world =
                    body.logicalPose()
                            .orientation()
                            .transform(
                                    new Vector3d(
                                            0,
                                            nozzle == Direction.DOWN
                                                    ? force * throttle
                                                    : -force * throttle,
                                            0));
            var pair = SableFrames.weighPair(world.negate(new Vector3d()), 1, 1, step, drag);
            var pos = jet.getBlockPos();
            handle.applyImpulseAtPoint(
                    new Vector3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5),
                    body.logicalPose().transformNormalInverse(pair.pre(), new Vector3d()));
            pending.add(new Jet(pos, pair.post()));
        }
        state.pending = pending;
        // Final chart yaw cap, after all (unordered) actors; space steering remains untouched.
        var spin = physics.getPipeline().getAngularVelocity(body, new Vector3d());
        handle.addLinearAndAngularVelocity(
                new Vector3d(),
                new Vector3d(
                        0,
                        Math.clamp(spin.y, -LiftControl.CHART_YAW, LiftControl.CHART_YAW) - spin.y,
                        0));
        long tick = body.getLevel().getGameTime();
        if (state.sent != tick) {
            state.sent = tick;
            publish(
                    body,
                    true,
                    state.output.lift(),
                    state.output.down(),
                    state.output.insufficient());
            if (Boolean.getBoolean("starfold.carryProbe") && tick % 20 == 0)
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE LIFT tick {} mass {} gravity {} maxLift {} clearance {} vy {} lift {} down {} bits {} up {}",
                                tick,
                                mass,
                                gravity,
                                lift,
                                ground.clearance(),
                                velocity.y,
                                state.output.lift(),
                                state.output.down(),
                                bits,
                                body.logicalPose()
                                        .orientation()
                                        .transform(new Vector3d(0, 1, 0))
                                        .y);
        }
    }

    private static double hullGravity(ServerSubLevel body, Vector3d at) {
        return Math.max(
                        0,
                        -dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                                .getGravity(body.getLevel())
                                .y)
                * HullWeight.at(body.getLevel(), new BentStripGeometry.Point(at.x, at.y, at.z))
                * LiftControl.HULL_GRAVITY;
    }

    // Chart relabel/arrival never inherits a storage-coordinate altitude target.
    private static boolean captureTarget(
            State state, double y, boolean command, HullLanding.Ground ground, double velocityY) {
        return !Double.isFinite(state.target)
                || !Double.isFinite(state.lastY)
                || Math.abs(y - state.lastY) > LiftControl.TELEPORT_RESET
                || state.commanded && !command
                || command
                || LiftControl.landed(ground.clearance(), velocityY);
    }

    private static double liftProjection(ServerSubLevel body) {
        return Math.max(0, body.logicalPose().orientation().transform(new Vector3d(0, 1, 0)).y);
    }

    static void post(SubLevelPhysicsSystem physics, ServerSubLevel body) {
        var s = states.get(body);
        if (s == null) return;
        var handle = physics.getPhysicsHandle(body);
        for (var jet : s.pending) {
            var p = jet.pos();
            handle.applyImpulseAtPoint(
                    new Vector3d(p.getX() + .5, p.getY() + .5, p.getZ() + .5),
                    body.logicalPose().transformNormalInverse(jet.post(), new Vector3d()));
        }
        s.pending = java.util.List.of();
    }

    private static void publish(
            ServerSubLevel body, boolean active, double lift, double down, boolean insufficient) {
        for (var player : body.getLevel().players())
            if (body.getTrackingPlayers().contains(player.getUUID())
                    && player.connection.hasChannel(HelmPackets.Lift.TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player,
                        new HelmPackets.Lift(
                                body.getUniqueId(),
                                active,
                                lift,
                                down,
                                insufficient,
                                active ? states.get(body).output.limit() : Double.NaN));
    }

    private LiftServer() {}
}
