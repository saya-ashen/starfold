package dev.starfold.ground;

import dev.starfold.chart.GroundChart;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.ChartOrientation;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.mixin.BlockEntityPacketAccess;
import dev.starfold.mixin.TeleportPacketAccess;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Per-recipient relabelling between canonical storage coordinates and one observer's continuous
 * client coordinates (see ObserverFrame). Never mutates a shared broadcast packet or a real entity,
 * and never changes what the server simulates: inbound packets are restored to the root storage
 * chart before any vanilla handler sees them. */
public final class PacketMap {
    private static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> false);
    private static final ClientboundBundlePacket NOTHING = new ClientboundBundlePacket(List.of());

    /** Send a packet that is already expressed in the observer's client coordinates. */
    public static void raw(ServerPlayer player, Packet<?> packet) {
        boolean before = RAW.get();
        RAW.set(true);
        try {
            player.connection.send(packet);
        } finally {
            RAW.set(before);
        }
    }

    /** Quarter turns from the storage chart that owns {@code x} to the plane. */
    public static int turns(
            PlanetAccess access, ObserverFrame frame, double x, double y, double z) {
        try {
            if (!access.inChart(BlockPos.containing(x, y, z))
                    || access.storagePlanet(x, z) != frame.planet()
                    || access.face(x) == frame.root()) return frame.turns();
            var tile =
                    new GroundChart(CubeSurfaceTopology.Face.values()[frame.root()])
                            .tile(CubeSurfaceTopology.Face.values()[access.face(x)]);
            return tile == null ? frame.turns() : frame.turns() + tile.turn();
        } catch (IllegalArgumentException outside) {
            return frame.turns();
        }
    }

    /** Storage position -> plane; null when that place is not drawn in the observer's chart. */
    public static Point plane(
            ServerPlayer player, ObserverFrame frame, double x, double y, double z) {
        var access = PlanetAccess.of(player.level());
        var point = new Point(x, y, z);
        try {
            // What lies outside every planet's charts is no place on a plane: another mod's own
            // coordinates
            // (a Sable plot, and whoever stands on its body) are the same for every observer.
            if (!access.inChart(BlockPos.containing(x, y, z))) return point;
            if (access.storagePlanet(x, z) != frame.planet()) return null;
            if (access.face(x) == frame.root()) return frame.toClient(point);
            var root = access.middle(frame.planet(), frame.root());
            var image = access.image(root.x(), root.z(), point);
            return image == null ? null : frame.toClient(image);
        } catch (IllegalArgumentException outside) {
            return point;
        }
    }

    static BlockPos plane(ServerPlayer player, ObserverFrame frame, BlockPos pos) {
        var p = plane(player, frame, pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        return p == null
                ? null
                : new BlockPos((int) Math.floor(p.x()), pos.getY(), (int) Math.floor(p.z()));
    }

    private static Vec3 rotate(double x, double y, double z, int turns) {
        var v = ChartLabSpace.rotate(new Point(x, y, z), turns);
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static boolean outside(PlanetAccess access, int x, int z) {
        return !access.inChart(new BlockPos(x, 128, z));
    }

    public static Packet<?> outbound(ServerPlayer p, Packet<?> packet) {
        if (RAW.get() || !ObserverFrames.active(p)) return packet;
        var access = PlanetAccess.of(p.level());
        if (packet instanceof ClientboundPlayerPositionPacket pos) {
            // Vanilla has already moved the body to the absolute destination; relative arguments
            // are folded in.
            var f = ObserverFrames.teleported(p);
            var c = f.toClient(new Point(p.getX(), p.getY(), p.getZ()));
            return new ClientboundPlayerPositionPacket(
                    c.x(),
                    c.y(),
                    c.z(),
                    f.yawToClient(p.getYRot()),
                    p.getXRot(),
                    Set.of(),
                    pos.getId());
        }
        var f = ObserverFrames.get(p);
        // The vehicle this observer steers is in their root chart, as their own body is.
        if (packet instanceof ClientboundMoveVehiclePacket v) {
            if (f.isIdentity()) return packet;
            var c = f.toClient(new Point(v.getX(), v.getY(), v.getZ()));
            return vehicle(
                    ClientboundMoveVehiclePacket.STREAM_CODEC,
                    c,
                    f.yawToClient(v.getYRot()),
                    v.getXRot());
        }
        if (packet
                instanceof
                net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom) {
            var mapped = PayloadMap.relabel(p, custom.payload());
            return mapped == custom.payload()
                    ? packet
                    : new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                            mapped);
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket) {
            var c = f.toClient(new Point(p.getX(), p.getY(), p.getZ()));
            return new ClientboundSetChunkCacheCenterPacket(
                    (int) Math.floor(c.x()) >> 4, (int) Math.floor(c.z()) >> 4);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            var parts =
                    new ArrayList<
                            Packet<
                                    ? super
                                            net.minecraft.network.protocol.game
                                                    .ClientGamePacketListener>>();
            for (var part : bundle.subPackets()) {
                @SuppressWarnings("unchecked")
                var mapped =
                        (Packet<
                                        ? super
                                                net.minecraft.network.protocol.game
                                                        .ClientGamePacketListener>)
                                outbound(p, part);
                parts.add(mapped);
            }
            return new ClientboundBundlePacket(parts);
        }
        // What lies outside every planet's charts is not a planet's: chunks another mod keeps there
        // for itself
        // (a Sable sub-level's plot) reach the client as that mod sends them.
        if (packet instanceof ClientboundLevelChunkWithLightPacket c
                        && outside(access, c.getX() * 16 + 8, c.getZ() * 16 + 8)
                || packet instanceof ClientboundForgetLevelChunkPacket g
                        && outside(access, g.pos().getMiddleBlockX(), g.pos().getMiddleBlockZ())
                || packet instanceof ClientboundLightUpdatePacket l
                        && outside(access, l.getX() * 16 + 8, l.getZ() * 16 + 8)) return packet;
        if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            var first = new BlockPos[1];
            section.runUpdates(
                    (pos, state) -> {
                        if (first[0] == null) first[0] = pos.immutable();
                    });
            if (first[0] != null && outside(access, first[0].getX(), first[0].getZ()))
                return packet;
        }
        if (packet instanceof ClientboundBlockUpdatePacket b
                        && outside(access, b.getPos().getX(), b.getPos().getZ())
                || packet instanceof ClientboundBlockEntityDataPacket e
                        && outside(access, e.getPos().getX(), e.getPos().getZ())
                || packet instanceof ClientboundBlockEventPacket v
                        && outside(access, v.getPos().getX(), v.getPos().getZ())) return packet;
        // Block and light changes reach every image through ImageStream; vanilla's storage-keyed
        // section/light broadcasts would land on the wrong client chunks.
        if (packet instanceof ClientboundSectionBlocksUpdatePacket
                || packet instanceof ClientboundLightUpdatePacket
                || packet instanceof ClientboundLevelChunkWithLightPacket
                || packet instanceof ClientboundForgetLevelChunkPacket) return NOTHING;
        if (packet instanceof ClientboundBlockUpdatePacket b) {
            var at = plane(p, f, b.getPos());
            return at == null
                    ? NOTHING
                    : new ClientboundBlockUpdatePacket(
                            at,
                            ChartOrientation.state(
                                    b.getBlockState(),
                                    turns(
                                            access,
                                            f,
                                            b.getPos().getX(),
                                            b.getPos().getY(),
                                            b.getPos().getZ())));
        }
        if (packet instanceof ClientboundBlockEntityDataPacket b) {
            var at = plane(p, f, b.getPos());
            if (at == null) return NOTHING;
            var tag = b.getTag().copy();
            if (tag.contains("x")) {
                tag.putInt("x", at.getX());
                tag.putInt("z", at.getZ());
            }
            return BlockEntityPacketAccess.starfold$create(at, b.getType(), tag);
        }
        if (packet instanceof ClientboundBlockEventPacket b) {
            var at = plane(p, f, b.getPos());
            return at == null
                    ? NOTHING
                    : new ClientboundBlockEventPacket(at, b.getBlock(), b.getB0(), b.getB1());
        }
        if (packet instanceof ClientboundLevelEventPacket e) {
            if (e.isGlobalEvent()) return packet;
            var at = plane(p, f, e.getPos());
            return at == null
                    ? NOTHING
                    : new ClientboundLevelEventPacket(e.getType(), at, e.getData(), false);
        }
        if (packet instanceof ClientboundBlockDestructionPacket e) {
            var at = plane(p, f, e.getPos());
            return at == null
                    ? NOTHING
                    : new ClientboundBlockDestructionPacket(e.getId(), at, e.getProgress());
        }
        if (packet instanceof ClientboundOpenSignEditorPacket e) {
            var at = plane(p, f, e.getPos());
            return at == null ? NOTHING : new ClientboundOpenSignEditorPacket(at, e.isFrontText());
        }
        if (packet instanceof ClientboundSoundPacket s) {
            var at = plane(p, f, s.getX(), s.getY(), s.getZ());
            return at == null
                    ? NOTHING
                    : new ClientboundSoundPacket(
                            s.getSound(),
                            s.getSource(),
                            at.x(),
                            at.y(),
                            at.z(),
                            s.getVolume(),
                            s.getPitch(),
                            s.getSeed());
        }
        if (packet instanceof ClientboundLevelParticlesPacket s) {
            var at = plane(p, f, s.getX(), s.getY(), s.getZ());
            if (at == null) return NOTHING;
            var d =
                    rotate(
                            s.getXDist(),
                            s.getYDist(),
                            s.getZDist(),
                            turns(access, f, s.getX(), s.getY(), s.getZ()));
            return new ClientboundLevelParticlesPacket(
                    s.getParticle(),
                    s.isOverrideLimiter(),
                    at.x(),
                    at.y(),
                    at.z(),
                    (float) Math.abs(d.x),
                    (float) d.y,
                    (float) Math.abs(d.z),
                    s.getMaxSpeed(),
                    s.getCount());
        }
        if (packet instanceof ClientboundExplodePacket e) {
            var at = plane(p, f, e.getX(), e.getY(), e.getZ());
            if (at == null) return NOTHING;
            var blocks = new ArrayList<BlockPos>();
            for (var b : e.getToBlow()) {
                var image = plane(p, f, b);
                if (image != null) blocks.add(image);
            }
            return new ClientboundExplodePacket(
                    at.x(),
                    at.y(),
                    at.z(),
                    e.getPower(),
                    blocks,
                    rotate(e.getKnockbackX(), e.getKnockbackY(), e.getKnockbackZ(), f.turns()),
                    e.getBlockInteraction(),
                    e.getSmallExplosionParticles(),
                    e.getLargeExplosionParticles(),
                    e.getExplosionSound());
        }
        if (packet instanceof ClientboundAddEntityPacket a) {
            var at = plane(p, f, a.getX(), a.getY(), a.getZ());
            if (at == null) return NOTHING;
            int t = turns(access, f, a.getX(), a.getY(), a.getZ());
            if (f.isIdentity() && t == 0) return packet;
            return new ClientboundAddEntityPacket(
                    a.getId(),
                    a.getUUID(),
                    at.x(),
                    at.y(),
                    at.z(),
                    a.getXRot(),
                    a.getYRot() + t * 90,
                    a.getType(),
                    a.getData(),
                    rotate(a.getXa(), a.getYa(), a.getZa(), t),
                    a.getYHeadRot() + t * 90);
        }
        if (packet instanceof ClientboundAddExperienceOrbPacket orb) {
            var at = plane(p, f, orb.getX(), orb.getY(), orb.getZ());
            if (at == null) return NOTHING;
            // No public constructor takes coordinates: re-encode with the image position.
            var buffer =
                    new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                buffer.writeVarInt(orb.getId());
                buffer.writeDouble(at.x());
                buffer.writeDouble(at.y());
                buffer.writeDouble(at.z());
                buffer.writeShort(orb.getValue());
                return ClientboundAddExperienceOrbPacket.STREAM_CODEC.decode(buffer);
            } finally {
                buffer.release();
            }
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            var e = p.level().getEntity(motion.getId());
            if (e == null) return packet;
            int t = e == p ? f.turns() : turns(access, f, e.getX(), e.getY(), e.getZ());
            return t == 0
                    ? packet
                    : new ClientboundSetEntityMotionPacket(
                            motion.getId(),
                            rotate(motion.getXa(), motion.getYa(), motion.getZa(), t));
        }
        if (packet instanceof ClientboundRotateHeadPacket head) {
            var e = head.getEntity(p.level());
            if (e == null || e == p) return packet;
            int t = turns(access, f, e.getX(), e.getY(), e.getZ());
            return t == 0
                    ? packet
                    : new ClientboundRotateHeadPacket(e, (byte) (head.getYHeadRot() + t * 64));
        }
        Entity entity = null;
        if (packet instanceof ClientboundMoveEntityPacket move) entity = move.getEntity(p.level());
        else if (packet instanceof ClientboundTeleportEntityPacket move)
            entity = p.level().getEntity(move.getId());
        if (entity != null && entity != p) {
            int t = turns(access, f, entity.getX(), entity.getY(), entity.getZ());
            var at = plane(p, f, entity.getX(), entity.getY(), entity.getZ());
            if (at == null) return NOTHING;
            if (t == 0 && at.x() == entity.getX() && at.z() == entity.getZ()) return packet;
            // Relative moves are replaced by the absolute image: the image is not a rigid copy
            // across a seam.
            var copy = new ClientboundTeleportEntityPacket(entity);
            var image = (TeleportPacketAccess) copy;
            image.starfold$coverX(at.x());
            image.starfold$coverZ(at.z());
            image.starfold$coverYaw((byte) (copy.getyRot() + t * 64));
            return copy;
        }
        return packet;
    }

    /** Client coordinates -> the root storage chart (possibly its halo), exactly what a pre-frame client sent. */
    public static Packet<?> inbound(ServerPlayer p, Packet<?> packet) {
        if (!ObserverFrames.active(p)) return packet;
        var f = ObserverFrames.get(p);
        if (f.isIdentity()) return packet;
        if (packet instanceof ServerboundMovePlayerPacket move) {
            float yaw = f.yawToStorage(move.getYRot(0)), pitch = move.getXRot(0);
            if (move.hasPosition()) {
                var s = f.toStorage(new Point(move.getX(0), move.getY(0), move.getZ(0)));
                if (elsewhere(p, f, move.getX(0), move.getY(0), move.getZ(0)))
                    s = new Point(move.getX(0), move.getY(0), move.getZ(0));
                return move.hasRotation()
                        ? new ServerboundMovePlayerPacket.PosRot(
                                s.x(), s.y(), s.z(), yaw, pitch, move.isOnGround())
                        : new ServerboundMovePlayerPacket.Pos(
                                s.x(), s.y(), s.z(), move.isOnGround());
            }
            return move.hasRotation()
                    ? new ServerboundMovePlayerPacket.Rot(yaw, pitch, move.isOnGround())
                    : packet;
        }
        if (packet instanceof ServerboundMoveVehiclePacket v) {
            var c = f.toStorage(new Point(v.getX(), v.getY(), v.getZ()));
            return vehicle(
                    ServerboundMoveVehiclePacket.STREAM_CODEC,
                    c,
                    f.yawToStorage(v.getYRot()),
                    v.getXRot());
        }
        // A block that is in no chart is another mod's (a block of a Sable body, in its plot):
        // where it is, which face of
        // it was touched and where on it are that mod's own coordinates, and go on as sent.
        if (packet instanceof ServerboundUseItemOnPacket use
                        && elsewhere(p, f, use.getHitResult().getBlockPos())
                || packet instanceof ServerboundPlayerActionPacket action
                        && elsewhere(p, f, action.getPos())
                || packet instanceof ServerboundSignUpdatePacket sign
                        && elsewhere(p, f, sign.getPos())) return packet;
        if (packet instanceof ServerboundUseItemOnPacket use) {
            var hit = use.getHitResult();
            var at =
                    f.toStorage(
                            new Point(
                                    hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));
            return new ServerboundUseItemOnPacket(
                    use.getHand(),
                    new BlockHitResult(
                            new Vec3(at.x(), at.y(), at.z()),
                            f.directionToStorage(hit.getDirection()),
                            f.toStorage(hit.getBlockPos()),
                            hit.isInside()),
                    use.getSequence());
        }
        if (packet instanceof ServerboundPlayerActionPacket action)
            return new ServerboundPlayerActionPacket(
                    action.getAction(),
                    f.toStorage(action.getPos()),
                    f.directionToStorage(action.getDirection()),
                    action.getSequence());
        if (packet instanceof ServerboundUseItemPacket use)
            return new ServerboundUseItemPacket(
                    use.getHand(), use.getSequence(), f.yawToStorage(use.getYRot()), use.getXRot());
        if (packet instanceof ServerboundSignUpdatePacket sign) {
            var l = sign.getLines();
            return new ServerboundSignUpdatePacket(
                    f.toStorage(sign.getPos()), sign.isFrontText(), l[0], l[1], l[2], l[3]);
        }
        return packet;
    }

    /** A position a client sent that was never on its observer's plane (see plane): brought back to storage it lies in no chart. */
    private static boolean elsewhere(
            ServerPlayer p, ObserverFrame f, double x, double y, double z) {
        var s = f.toStorage(new Point(x, y, z));
        try {
            return !PlanetAccess.of(p.level()).inChart(BlockPos.containing(s.x(), s.y(), s.z()));
        } catch (IllegalArgumentException outside) {
            return true;
        }
    }

    private static boolean elsewhere(ServerPlayer p, ObserverFrame f, BlockPos pos) {
        return elsewhere(p, f, pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    }

    /** Neither vehicle packet has a constructor that takes coordinates: both are five numbers on the wire. */
    private static <T> T vehicle(
            net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, T> codec,
            Point at,
            float yaw,
            float pitch) {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeDouble(at.x());
            buffer.writeDouble(at.y());
            buffer.writeDouble(at.z());
            buffer.writeFloat(yaw);
            buffer.writeFloat(pitch);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private PacketMap() {}
}
