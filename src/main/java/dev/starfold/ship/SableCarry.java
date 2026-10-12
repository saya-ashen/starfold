package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.util.SableBufferUtils;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.ground.ObserverFrame;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One coordinate relabel, including its snapshot fence; plot blocks and control sessions never restart. */
public record SableCarry(
        long plot,
        int tick,
        Pose3d pose,
        Pose3d last,
        Point player,
        float yaw,
        ObserverFrame observer,
        SpacePackets.Frame region)
        implements CustomPacketPayload {
    public SableCarry {
        validate(pose);
        validate(last);
        GroundEphemeris.point(player);
        if (tick < 0 || !Float.isFinite(yaw) || observer == null || region == null)
            throw new IllegalArgumentException("Carried observer");
        pose = new Pose3d(pose);
        last = new Pose3d(last);
    }

    private static void validate(Pose3d pose) {
        if (pose == null
                || !pose.position().isFinite()
                || !pose.rotationPoint().isFinite()
                || !pose.scale().isFinite()
                || pose.scale().x <= 0
                || pose.scale().y <= 0
                || pose.scale().z <= 0) throw new IllegalArgumentException("Carried pose");
        GroundEphemeris.quaternion(new org.joml.Quaterniond(pose.orientation()));
    }

    @Override
    public Pose3d pose() {
        return new Pose3d(pose);
    }

    @Override
    public Pose3d last() {
        return new Pose3d(last);
    }

    public static final Type<SableCarry> TYPE =
            new Type<>(ResourceLocation.parse("starfold:native_sable_carry"));

    private static void writePose(RegistryFriendlyByteBuf b, Pose3d p) {
        SableBufferUtils.write(b, p.position());
        var q = p.orientation();
        b.writeDouble(q.x);
        b.writeDouble(q.y);
        b.writeDouble(q.z);
        b.writeDouble(q.w);
        SableBufferUtils.write(b, p.rotationPoint());
        SableBufferUtils.write(b, p.scale());
    }

    private static Pose3d readPose(RegistryFriendlyByteBuf b) {
        return new Pose3d(
                SableBufferUtils.read(b, new org.joml.Vector3d()),
                new org.joml.Quaterniond(
                        b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()),
                SableBufferUtils.read(b, new org.joml.Vector3d()),
                SableBufferUtils.read(b, new org.joml.Vector3d()));
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SableCarry> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeLong(p.plot);
                        b.writeInt(p.tick);
                        writePose(b, p.pose);
                        writePose(b, p.last);
                        b.writeDouble(p.player.x());
                        b.writeDouble(p.player.y());
                        b.writeDouble(p.player.z());
                        b.writeFloat(p.yaw);
                        GroundFlightPackets.Frame.CODEC.encode(
                                b, new GroundFlightPackets.Frame(p.observer));
                        SpacePackets.Frame.CODEC.encode(b, p.region);
                    },
                    b ->
                            new SableCarry(
                                    b.readLong(),
                                    b.readInt(),
                                    readPose(b),
                                    readPose(b),
                                    new Point(b.readDouble(), b.readDouble(), b.readDouble()),
                                    b.readFloat(),
                                    GroundFlightPackets.Frame.CODEC.decode(b).frame(),
                                    SpacePackets.Frame.CODEC.decode(b)));

    @Override
    public Type<SableCarry> type() {
        return TYPE;
    }
}
