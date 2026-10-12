package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.starfold.StarfoldNetwork;
import dev.starfold.render.GroundClient;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.Minecraft;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Keep the live client hull and its plot. Only its coordinate labels and interpolation history change. */
public final class SableCarryClient {
    private static final java.util.Map<SubLevelSnapshotInterpolator, Integer> FENCES =
            new java.util.WeakHashMap<>();

    public static boolean accepts(SubLevelSnapshotInterpolator interpolator, int tick) {
        return tick >= FENCES.getOrDefault(interpolator, Integer.MIN_VALUE);
    }

    public static void initialize() {
        StarfoldNetwork.clientReceiver(SableCarry.TYPE, (packet, context) -> apply(packet));
        StarfoldNetwork.clientReceiver(
                SableBoardingPacket.TYPE, (packet, context) -> apply(packet.carry(), true));
    }

    private static void apply(SableCarry packet) {
        apply(packet, false);
    }

    private static void apply(SableCarry packet, boolean boarding) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        if (container == null) return;
        if (!(container.getSubLevel(
                        net.minecraft.world.level.ChunkPos.getX(packet.plot()),
                        net.minecraft.world.level.ChunkPos.getZ(packet.plot()))
                instanceof ClientSubLevel body)) {
            if (boarding) return;
            throw new IllegalStateException("Carried client hull missing");
        }
        var player = mc.player;
        var before = new Pose3d(body.logicalPose());
        var now = packet.pose();
        // The local player may have walked since the server sampled them. Preserve that
        // plot-relative place too.
        var at =
                boarding
                        ? new Vector3d(
                                packet.player().x(), packet.player().y(), packet.player().z())
                        : SableFrames.riderPoint(
                                before,
                                now,
                                new Vector3d(player.getX(), player.getY(), player.getZ()));
        var turn =
                new Quaterniond(now.orientation())
                        .mul(new Quaterniond(before.orientation()).conjugate())
                        .normalize();
        var velocity = player.getDeltaMovement();
        var moved = turn.transform(new Vector3d(velocity.x, velocity.y, velocity.z));
        var interpolation = body.getInterpolator();
        FENCES.put(interpolation, packet.tick());
        synchronized (interpolation.buffer) {
            interpolation.buffer.clear();
            interpolation.buffer.add(
                    new SubLevelSnapshotInterpolator.Snapshot(packet.tick() - 1, packet.last()));
            interpolation.buffer.add(new SubLevelSnapshotInterpolator.Snapshot(packet.tick(), now));
        }
        ((Pose3d) interpolation.getInterpolatedPose()).set(now);
        body.logicalPose().set(now);
        ((Pose3d) body.lastPose()).set(now);
        ((dev.starfold.mixin.SableClientPoseAccess) body).starfold$renderPartial(-1);
        body.forceUpdateBounds();
        float yaw = SableFrames.yaw(turn, player.getYRot());
        player.setPos(at.x, at.y, at.z);
        player.xo = player.xOld = at.x;
        player.yo = player.yOld = at.y;
        player.zo = player.zOld = at.z;
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setYHeadRot(yaw);
        player.setDeltaMovement(moved.x, moved.y, moved.z);
        // Weightlessness stops a falling deck escaping during loading; a small relative downward
        // step lets
        // Sable acquire its normal collision from above, without assigning tracking ourselves.
        if (boarding) {
            player.setDeltaMovement(0, -.08, 0);
            player.fallDistance = 0;
        }
        GroundClient.frame(packet.observer());
        if (!boarding) SpaceClient.carried(packet.region());
        if (System.getProperty("starfold.probe") != null) {
            var relative = now.transformPositionInverse(at, new Vector3d());
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE CARRY client tick {} plot {} relative {} {} {} tracking {} ground {}",
                            packet.tick(),
                            packet.plot(),
                            relative.x,
                            relative.y,
                            relative.z,
                            dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(player) == body,
                            player.onGround());
        }
    }

    private SableCarryClient() {}
}
