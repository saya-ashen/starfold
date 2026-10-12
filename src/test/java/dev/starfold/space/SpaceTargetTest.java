package dev.starfold.space;

import static dev.starfold.space.SpaceTest.point;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.GroundEphemeris;

import org.junit.jupiter.api.Test;

class SpaceTargetTest {
    @Test
    void sidewaysMovingTargetEndsOnNoseInItsOwnFrame() {
        var nose = new Point(0, 0, 1);
        var target = new Point(4, 0, 0);
        var drift = new Point(0, 0, 10);
        double speed = drift.sub(target).length();
        for (int i = 0; i < 200; i++) drift = SpaceServer.assist(drift, nose, true, target);
        var relative = drift.sub(target);
        assertEquals(speed, relative.length(), 1e-10);
        assertEquals(0, relative.x(), 1e-5);
        assertEquals(0, relative.y());
        assertEquals(speed, relative.z(), 1e-5);
        assertTrue(drift.x() > 3.99);
    }

    @Test
    void referenceTargetAndNoTargetAreExactlyTheExistingAssist() {
        var nose = new Point(.6, 0, .8);
        for (var d : new Point[] {new Point(2, 3, 4), new Point(-3, 4, -8), GroundEphemeris.ZERO})
            for (boolean forward : new boolean[] {false, true}) {
                assertEquals(
                        SpaceServer.assist(d, nose, forward),
                        SpaceServer.assist(d, nose, forward, GroundEphemeris.ZERO));
                assertEquals(
                        SpaceServer.assist(d, nose, forward),
                        SpaceServer.assist(d, nose, forward, null));
            }
    }

    @Test
    void coastingIsNotChangedByLockOrUnlock() {
        var drift = new Point(2, 3, 4);
        var nose = new Point(0, 0, 1);
        assertSame(drift, SpaceServer.assist(drift, nose, false, new Point(4, 0, 0)));
        assertSame(drift, SpaceServer.assist(drift, nose, false, null));
    }

    @Test
    void movingFrameIsEquivalentForArbitraryNoses() {
        var model = SpaceTest.tilted();
        for (int reference = 0; reference < 2; reference++)
            for (int target = 0; target < 2; target++)
                for (long tick : new long[] {0, 1000, 12345}) {
                    var offset =
                            model.at(target, tick)
                                    .velocity()
                                    .sub(model.at(reference, tick).velocity());
                    var nose = new Point(.6, 0, .8);
                    var relative = new Point(3, -2, 7);
                    point(
                            SpaceServer.assist(relative, nose, true),
                            SpaceServer.assist(relative.add(offset), nose, true, offset)
                                    .sub(offset));
                }
    }

    @Test
    void targetAndClearPayloadRoundTrip() {
        for (int planet : new int[] {-1, 0, 7, Integer.MAX_VALUE}) {
            var b =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            io.netty.buffer.Unpooled.buffer(),
                            net.minecraft.core.RegistryAccess.EMPTY);
            try {
                var p = new SpacePackets.Target(planet);
                SpacePackets.Target.CODEC.encode(b, p);
                assertEquals(4, b.readableBytes());
                assertEquals(p, SpacePackets.Target.CODEC.decode(b));
                assertEquals(0, b.readableBytes());
            } finally {
                b.release();
            }
        }
    }

    @Test
    void indicesAndLifecycleAreServerValidated() throws Exception {
        assertFalse(SpaceServer.knownTarget(-1, 8));
        assertFalse(SpaceServer.knownTarget(Integer.MAX_VALUE, 8));
        assertFalse(SpaceServer.knownTarget(8, 8));
        assertTrue(SpaceServer.knownTarget(0, 8));
        assertTrue(SpaceServer.knownTarget(7, 8));
        var s = Sources.text("SpaceServer");
        for (String guard :
                new String[] {
                    "player.serverLevel()!=target.level()",
                    "!in(player)",
                    "!helm(player,target.body())",
                    "lead(target.level(),server)==target.body()",
                    "targets.entrySet().removeIf",
                    "targets.remove(event.getEntity())",
                    "targets.clear()"
                }) assertTrue(s.contains(guard), guard);
        assertFalse(
                s.substring(s.indexOf("@Override public CompoundTag save")).contains("targets"));
    }
}
