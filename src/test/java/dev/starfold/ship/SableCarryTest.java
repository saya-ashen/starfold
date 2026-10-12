package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.space.Space;
import dev.starfold.space.SpacePackets;
import dev.starfold.space.SpaceTest;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.file.*;

class SableCarryTest {
    private static Pose3d pose() {
        return new Pose3d(
                new Vector3d(-16384, 8192, -16384),
                new Quaterniond().rotationXYZ(.2, .3, .4),
                new Vector3d(20481000, -103, 20481000),
                new Vector3d(1.2, .8, 1.1));
    }

    private static SableCarry packet() {
        return new SableCarry(
                27,
                400,
                pose(),
                pose(),
                new Point(-16383, 8190, -16382),
                37,
                ObserverFrame.identity(0, 0),
                new SpacePackets.Frame(Space.Frame.IDENTITY, 500));
    }

    @Test
    void wireKeepsBothFullPosesObserverAndRegion() {
        var p = packet();
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            SableCarry.CODEC.encode(b, p);
            var copy = SableCarry.CODEC.decode(b);
            assertEquals(0, b.readableBytes());
            assertEquals(p.plot(), copy.plot());
            assertEquals(p.tick(), copy.tick());
            assertEquals(p.player(), copy.player());
            assertEquals(p.yaw(), copy.yaw());
            assertEquals(p.observer(), copy.observer());
            assertEquals(p.region(), copy.region());
            assertEquals(p.pose().position(), copy.pose().position());
            assertEquals(p.pose().orientation(), copy.pose().orientation());
            assertEquals(p.pose().rotationPoint(), copy.pose().rotationPoint());
            assertEquals(p.pose().scale(), copy.pose().scale());
            assertEquals(p.last().position(), copy.last().position());
            assertEquals("starfold:native_sable_carry", p.type().id().toString());
        } finally {
            b.release();
        }
        var value = p.pose();
        value.position().zero();
        assertNotEquals(value.position(), p.pose().position());
    }

    @Test
    void invalidPosesAndPlayerFieldsCannotEnterFromWire() {
        for (int offset = 12; offset < 244; offset += 8)
            for (double bad :
                    new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    SableCarry.CODEC.encode(b, packet());
                    b.setDouble(offset, bad);
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SableCarry.CODEC.decode(b),
                            "offset " + offset);
                } finally {
                    b.release();
                }
            }
        for (int offset : new int[] {8, 244}) {
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                SableCarry.CODEC.encode(b, packet());
                if (offset == 8) b.setInt(offset, -1);
                else b.setFloat(offset, Float.NaN);
                assertThrows(IllegalArgumentException.class, () -> SableCarry.CODEC.decode(b));
            } finally {
                b.release();
            }
        }
        var invalid = pose();
        invalid.orientation().set(0, 0, 0, 2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SableCarry(
                                0,
                                1,
                                invalid,
                                pose(),
                                new Point(0, 0, 0),
                                0,
                                ObserverFrame.identity(0, 0),
                                packet().region()));
        invalid.orientation().identity();
        invalid.scale().x = 0;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SableCarry(
                                0,
                                1,
                                invalid,
                                pose(),
                                new Point(0, 0, 0),
                                0,
                                ObserverFrame.identity(0, 0),
                                packet().region()));
    }

    @Test
    void onlyThisPlayersCarriedBodyIsRetained() {
        Object body = new Object(), other = new Object();
        assertTrue(SableFrames.retain(body, body));
        assertFalse(SableFrames.retain(body, other));
        assertFalse(SableFrames.retain(body, null));
    }

    @Test
    void trackedMotionIsOnlyRotatedWhileGraceReceivesTheVelocityChange() {
        var q = new Quaterniond().rotateY(.7);
        var own = new Point(.1, -.08, .2);
        var old = new Point(1, 2, 3);
        var next = new Point(4, 5, 6);
        var v = q.transform(new Vector3d(own.x(), own.y(), own.z()));
        SpaceTest.point(
                new Point(v.x, v.y, v.z), SableFrames.riderVelocity(q, own, old, next, true));
        var previous = q.transform(new Vector3d(old.x(), old.y(), old.z()));
        SpaceTest.point(
                new Point(v.x + 4 - previous.x, v.y + 5 - previous.y, v.z + 6 - previous.z),
                SableFrames.riderVelocity(q, own, old, next, false));
    }

    @Test
    void resetExclusionAndAtomicClientPathStayWired() throws Exception {
        var server = Sources.text("SableFrames");
        assertTrue(
                server.contains(
                        "!retain(body,CARRYING.get(player))&&body.getTrackingPlayers().remove"));
        assertTrue(
                server.contains(
                        "finally{if(rider instanceof ServerPlayer player)CARRYING.remove(player);}"));
        var snapshots =
                server.substring(
                        server.indexOf(
                                "PayloadMap.register(ClientboundSableSnapshotDualPacket.class"),
                        server.indexOf("private record Aboard"));
        assertTrue(snapshots.contains("new Pose3d(at)"));
        assertFalse(snapshots.contains("entries.add(e)"));
        assertFalse(snapshots.contains(":s;"));
        var client = Sources.text("SableCarryClient");
        for (String text :
                new String[] {
                    "interpolation.buffer.clear()",
                    "body.forceUpdateBounds()",
                    "GroundClient.frame(packet.observer())",
                    "SpaceClient.carried(packet.region())",
                    "player.xo=player.xOld=at.x",
                    "FENCES.put(interpolation,packet.tick())",
                    "tick>=FENCES.getOrDefault"
                }) assertTrue(client.contains(text), text);
        for (String forbidden :
                new String[] {
                    "removeSubLevel",
                    "allocateSubLevel",
                    "updateRenderData",
                    "enqueueWork",
                    "teleport"
                }) assertFalse(client.contains(forbidden), forbidden);
        var udp = Sources.text("SableUdpMixin");
        assertTrue(udp.contains("SableFrames.reliable(player)"));
        assertTrue(udp.contains("PacketMap.raw"));
        var fence = Sources.text("SableSnapshotFenceMixin");
        assertTrue(fence.contains("SableCarryClient.accepts"));
        assertTrue(fence.contains("ci.cancel()"));
    }
}
