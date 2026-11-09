package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundRecentlySplitSubLevelPacket;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.PacketMap;
import dev.starfold.ground.PayloadMap;
import dev.starfold.planet.PlanetAccess;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.ArrayList;

/** Observer projection of Sable tracking and snapshot packets. */
final class SableObserverPackets {
    static void register() {
        PayloadMap.register(
                ClientboundStartTrackingSubLevelPacket.class,
                (p, f, s) -> {
                    var last = pose(p, f, s.lastPose());
                    var now = pose(p, f, s.pose());
                    return last == s.lastPose() && now == s.pose()
                            ? s
                            : new ClientboundStartTrackingSubLevelPacket(
                                    s.plotCoordinate(),
                                    s.subLevelID(),
                                    last,
                                    new Pose3d(now),
                                    s.bounds(),
                                    s.name(),
                                    s.gameTick());
                });
        PayloadMap.register(
                ClientboundRecentlySplitSubLevelPacket.class,
                (p, f, s) -> {
                    var at = pose(p, f, s.pose());
                    return at == s.pose()
                            ? s
                            : new ClientboundRecentlySplitSubLevelPacket(
                                    s.splitSubLevelID(), s.splitFromID(), new Pose3d(at));
                });
        PayloadMap.register(
                ClientboundSableSnapshotDualPacket.class,
                (p, f, s) -> {
                    var entries = new ArrayList<ClientboundSableSnapshotDualPacket.Entry>();
                    for (var e : s.entries()) {
                        var at = pose(p, f, e.pose());
                        int t = turns(p, f, e.pose());
                        // UDP encodes later. Even an identity observer needs a copy: move mutates
                        // the live pose in place.
                        entries.add(
                                new ClientboundSableSnapshotDualPacket.Entry(
                                        e.plotCoordinate(),
                                        new Pose3d(at),
                                        turn(e.linearVelocity(), t),
                                        turn(e.angularVelocity(), t)));
                    }
                    return new ClientboundSableSnapshotDualPacket(
                            ((dev.starfold.mixin.SableSnapshotAccess) (Object) s)
                                    .starfold$interpolationTick(),
                            entries);
                });
    }

    private static int turns(ServerPlayer p, ObserverFrame f, Pose3dc pose) {
        var at = pose.position();
        return PacketMap.turns(PlanetAccess.of(p.level()), f, at.x(), at.y(), at.z());
    }

    private static Vector3fc turn(Vector3fc v, int turns) {
        var r = ChartLabSpace.rotate(new BentStripGeometry.Point(v.x(), v.y(), v.z()), turns);
        return new Vector3f((float) r.x(), (float) r.y(), (float) r.z());
    }

    static Pose3dc pose(ServerPlayer p, ObserverFrame f, Pose3dc pose) {
        var at = pose.position();
        return SableFrames.pose(PacketMap.plane(p, f, at.x(), at.y(), at.z()), turns(p, f, pose), pose);
    }

    private SableObserverPackets() {}
}
