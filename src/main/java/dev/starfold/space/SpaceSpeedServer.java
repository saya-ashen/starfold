package dev.starfold.space;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.ThrusterFuel;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.Space;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Guidance uses only the unused nozzle force in the requested direction, not extra arcade thrust. */
public final class SpaceSpeedServer {
    static Point maneuver(ServerSubLevel body, Space.Frame frame, Point velocity, Point wanted) {
        var change = wanted.sub(velocity);
        double length = change.length();
        if (length == 0) return velocity;
        var direction = frame.levelVector(change.scale(1 / length));
        var local = body.logicalPose().orientation().transformInverse(
                new org.joml.Vector3d(direction.x(), direction.y(), direction.z()));
        double thrust = 0;
        for (var actor : body.getPlot().getBlockEntityActors())
            if (actor instanceof Thrusters.Thruster jet && !jet.isRemoved()) {
                var state = jet.getBlockState();
                var axis = state.getValue(BlockStateProperties.FACING).getOpposite().getNormal();
                double along = local.x * axis.getX() + local.y * axis.getY() + local.z * axis.getZ();
                double spare = 1 - HelmServer.power(body, jet.getBlockPos(), state) / 15.0;
                thrust += Math.max(0, along) * spare * Thrusters.THRUST;
            }
        if (Thrusters.boosted(body, body.getLevel().getGameTime())) thrust *= Thrusters.BOOST;
        return SpaceSpeed.maneuver(velocity, wanted, thrust / ThrusterFuel.mass(body) / 400,
                dv -> ThrusterFuel.change(body, dv));
    }

    private SpaceSpeedServer() {}
}
