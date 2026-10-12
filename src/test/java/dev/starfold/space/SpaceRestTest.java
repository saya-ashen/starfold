package dev.starfold.space;

import static dev.starfold.space.SpaceTest.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundTopology;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ship.SableCarry;
import dev.starfold.sky.GroundSolar;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.file.*;

class SpaceRestTest {
    private static Space.Frame moving() {
        return new Space.Frame(
                new Quaterniond().rotationXYZ(.3, -.7, 1.1),
                Space.ORIGIN.add(new Point(12, 34, 56)),
                1,
                new Point(800, -900, 1200),
                new Point(2, -3, 4),
                100);
    }

    @Test
    void movingFrameUsesOffsetAndEpochInBothDirectionsButNotInAxes() {
        var frame = moving();
        var model = tilted();
        var level = chart(Space.ORIGIN.add(new Point(17, 81, -35)));
        for (double tick : new double[] {0, 100, 1234.5, 9876}) {
            var universe = frame.universe(level, model, tick);
            motion(level, frame.level(universe, model, tick));
            motion(universe, frame.universe(frame.level(universe, model, tick), model, tick));
            point(
                    model.at(1, tick)
                            .center()
                            .add(frame.offset())
                            .add(frame.drift().scale(tick - 100))
                            .add(frame.universeVector(level.position().sub(frame.shift()))),
                    universe.position());
            point(
                    model.at(1, tick)
                            .velocity()
                            .add(frame.drift())
                            .add(frame.universeVector(level.velocity())),
                    universe.velocity());
            vector(frame.rotation().conjugate().transform(level.spin()), universe.spin());
            aim(frame.rotation().conjugate().mul(level.orientation()), universe.orientation());
        }
    }

    @Test
    void restMovesWithPlanetAndDriftAndNotPlanetSpin() {
        var frame = moving();
        var model = tilted();
        var body = resting(Space.ORIGIN);
        for (double tick : new double[] {100, 1234.5, 9876}) {
            var universe = frame.universe(body, model, tick);
            point(model.at(1, tick).velocity().add(frame.drift()), universe.velocity());
            vector(new Vector3d(), universe.spin());
            point(
                    universe.velocity(),
                    frame.universe(body.position(), model, tick + .5)
                            .sub(frame.universe(body.position(), model, tick - .5)));
        }
    }

    @Test
    void rebasePreservesTheEntireTrajectoryAndCanMoveEpochBackwards() {
        var old = moving();
        var model = tilted();
        var body = chart(Space.ORIGIN);
        for (long epoch : new long[] {0, 100, 1234, 9876}) {
            var next = old.rebased(epoch);
            assertEquals(epoch, next.epoch());
            point(old.offset().add(old.drift().scale(epoch - old.epoch())), next.offset());
            for (double tick : new double[] {0, 100, 1234.5, 9876})
                motion(old.universe(body, model, tick), next.universe(body, model, tick));
        }
    }

    @Test
    void foldingStopsLeadAndKeepsEveryOtherVelocityRelativeToIt() {
        var model = tilted();
        var old = moving();
        long tick = 1234;
        var lead = chart(Space.ORIGIN);
        var v = lead.velocity();
        var next = old.folded(v, tick);
        assertEquals(tick, next.epoch());
        point(old.drift().add(old.universeVector(v)), next.drift());
        for (var body :
                new Space.Motion[] {
                    lead,
                    resting(Space.ORIGIN.add(new Point(20, 30, 40))),
                    chart(new Point(-16300, 8200, -16400))
                }) {
            var after =
                    new Space.Motion(
                            body.position(),
                            body.orientation(),
                            body.velocity().sub(v),
                            body.spin());
            motion(old.universe(body, model, tick), next.universe(after, model, tick));
            point(
                    body.position(),
                    next.level(old.universe(body.position(), model, tick), model, tick));
        }
        point(ZERO, next.level(old.universe(lead, model, tick), model, tick).velocity());
    }

    @Test
    void dragRebasesBeforeScalingAndFollowsTheSpecifiedRecurrence() {
        var model = tilted();
        var frame = moving();
        double k = .09, factor = 1 / (1 + k / 20), speed = frame.drift().length();
        var point = Space.ORIGIN.add(new Point(1, 2, 3));
        for (long tick = 101; tick <= 10100; tick++) {
            var before = frame.universe(point, model, tick);
            frame = frame.dragged(factor, tick);
            point(before, frame.universe(point, model, tick));
            assertEquals(speed * Math.pow(factor, tick - 100), frame.drift().length(), 1e-11);
        }
        point(model.at(1, 10100).velocity(), frame.universeVelocity(ZERO, model, 10100));
        var unchanged = moving().dragged(1, 1234);
        motion(
                moving().universe(chart(point), model, 5678),
                unchanged.universe(chart(point), model, 5678));
        var stopped = moving().dragged(0, 1234);
        point(ZERO, stopped.drift());
        point(moving().universe(point, model, 1234), stopped.universe(point, model, 1234));
    }

    @Test
    void referenceSwitchPreservesAllObservablesIncludingNonzeroDriftAtFractionalTime() {
        var old = moving();
        var model = tilted();
        for (double tick : new double[] {100, 1234.5, 9876}) {
            var next = old.toward(0, model, tick);
            assertEquals(old.shift(), next.shift());
            aim(old.rotation(), next.rotation());
            point(
                    old.drift().add(model.at(1, tick).velocity()).sub(model.at(0, tick).velocity()),
                    next.drift());
            for (var body :
                    new Space.Motion[] {
                        chart(Space.ORIGIN), resting(new Point(-16500, 8500, -16600))
                    }) motion(old.universe(body, model, tick), next.universe(body, model, tick));
            motion(
                    old.universe(chart(Space.ORIGIN), model, tick),
                    next.toward(1, model, tick).universe(chart(Space.ORIGIN), model, tick));
        }
    }

    @Test
    void shiftingAndTurningKeepUniverseDriftAndRestingLeadVelocity() {
        var old = moving();
        var model = tilted();
        var pivot = Space.ORIGIN;
        var delta = new Point(120, -340, 560);
        var turn =
                Space.Frame.turn(
                        old, model, 1234, pivot, Math.toRadians(SpaceServer.ALIGN_TURN) / 20);
        var shifted = old.shifted(delta);
        var turned = old.turned(turn, pivot);
        for (var next : new Space.Frame[] {shifted, turned}) {
            assertEquals(old.offset(), next.offset());
            assertEquals(old.drift(), next.drift());
            assertEquals(old.epoch(), next.epoch());
            point(
                    old.universeVelocity(ZERO, model, 1234),
                    next.universeVelocity(ZERO, model, 1234));
        }
        point(old.universe(pivot, model, 1234), shifted.universe(pivot.add(delta), model, 1234));
        point(old.universe(pivot, model, 1234), turned.universe(pivot, model, 1234));
        for (var position : new Point[] {pivot, pivot.add(new Point(100, 200, 300))}) {
            var body = chart(position);
            motion(
                    old.universe(body, model, 1234),
                    turned.universe(
                            turned.level(old.universe(body, model, 1234), model, 1234),
                            model,
                            1234));
        }
    }

    @Test
    void keepingPlacesBodyAtRestAndArrivalRecoversItsEntireMotion() {
        var model = tilted();
        double tick = 1234.5;
        for (int planet = 0; planet < 2; planet++)
            for (var shape : SHAPES)
                for (var face : CubeSurfaceTopology.Face.values()) {
                    var pose =
                            new GroundTopology.Pose(
                                    face, new Point(.27 * shape.size(), 1500, .71 * shape.size()));
                    var body = chart(pose.point());
                    var universe = Space.out(model, planet, shape, pose, body, tick);
                    var frame =
                            Space.Frame.keeping(universe, body.orientation(), model, planet, tick);
                    var placed = frame.level(universe, model, tick);
                    point(Space.ORIGIN, placed.position());
                    point(ZERO, placed.velocity());
                    aim(body.orientation(), placed.orientation());
                    motion(
                            body,
                            Space.in(
                                            model,
                                            planet,
                                            shape,
                                            frame.universe(placed, model, tick),
                                            tick)
                                    .motion());
                }
    }

    @Test
    void blendsDifferentEpochsAndReferencesAtTheSameSkyTime() {
        var model = tilted();
        var before = moving();
        var eye = Space.ORIGIN.add(new Point(12, 34, 56));
        var after =
                before.folded(new Point(1, -2, 3), 1234)
                        .dragged(.99, 1234)
                        .toward(0, model, 1234)
                        .turned(new Quaterniond().rotationZ(.2), Space.ORIGIN);
        for (double tick : new double[] {1234, 1234.5, 1240})
            for (double partial : new double[] {0, .25, .5, .75, 1}) {
                var sample = Space.Frame.interpolate(before, after, eye, partial, model, tick);
                point(
                        before.universe(eye, model, tick)
                                .scale(1 - partial)
                                .add(after.universe(eye, model, tick).scale(partial)),
                        sample.universe(eye, model, tick));
                point(
                        before.universeVelocity(ZERO, model, tick)
                                .scale(1 - partial)
                                .add(after.universeVelocity(ZERO, model, tick).scale(partial)),
                        sample.universeVelocity(ZERO, model, tick));
                aim(before.rotation().slerp(after.rotation(), partial), sample.rotation());
            }
        var frames = new SpaceView.Frames();
        frames.install(new SpacePackets.Frame(before, 100), eye, 10, model, 1234);
        frames.install(new SpacePackets.Frame(after, 1234, true), eye, 11, model, 1234);
        assertEquals(before, frames.sample(eye, 11, model, 1234));
        assertEquals(after, frames.sample(eye, 11.5, null, 0));
        assertEquals(after, frames.sample(eye, 11.5, GroundSolar.defaults(), 1234));
        assertEquals(after, frames.sample(eye, 12, model, 1235));
        var distant = frames.sample(eye, 1012, model, 2235);
        point(after.universe(eye, model, 2235), distant.universe(eye, model, 2235));
        assertTrue(
                distant.universe(eye, model, 2235).sub(after.universe(eye, model, 1235)).length()
                        > 100);
    }

    @Test
    void v2SaveWireAndCarryKeepAllRestFrameFields() {
        assertEquals("starfold_native_space_v2", SpaceServer.FILE);
        var frame = moving();
        var saved = new SpaceServer(frame).save(new CompoundTag(), null);
        var loaded = SpaceServer.load(saved, null);
        assertEquals(saved, loaded.save(new CompoundTag(), null));
        assertFalse(loaded.isDirty());
        for (String key : new String[] {"ox", "oy", "oz", "wx", "wy", "wz", "originEpoch"}) {
            var bad = saved.copy();
            bad.remove(key);
            assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(bad, null));
        }
        for (String key : new String[] {"ox", "oy", "oz", "wx", "wy", "wz"}) {
            var bad = saved.copy();
            bad.putDouble(key, Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(bad, null));
        }
        var bad = saved.copy();
        bad.putLong("originEpoch", -1);
        assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(bad, null));
        var pose = new dev.ryanhcode.sable.companion.math.Pose3d();
        var packet =
                new SableCarry(
                        1,
                        2,
                        pose,
                        pose,
                        Space.ORIGIN,
                        0,
                        ObserverFrame.identity(0, 0),
                        new SpacePackets.Frame(frame, 1234, true));
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            SableCarry.CODEC.encode(b, packet);
            var decoded = SableCarry.CODEC.decode(b);
            assertEquals(packet.region(), decoded.region());
            assertEquals(0, b.readableBytes());
            motion(
                    frame.universe(chart(Space.ORIGIN), tilted(), 2345),
                    decoded.region().frame().universe(chart(Space.ORIGIN), tilted(), 2345));
        } finally {
            b.release();
        }
    }

    @Test
    void newFieldsAndOperationsRejectNonfiniteValuesAndNegativeEpochs() {
        var frame = moving();
        for (double bad :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            for (int i = 0; i < 6; i++) {
                double[] values = {1, 2, 3, 4, 5, 6};
                values[i] = bad;
                var offset = new Point(values[0], values[1], values[2]);
                var drift = new Point(values[3], values[4], values[5]);
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new Space.Frame(
                                        frame.rotation(), frame.shift(), 1, offset, drift, 100));
                var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    b.writeBoolean(true);
                    for (double n : new double[] {0, 0, 0, 1, 0, 0, 0}) b.writeDouble(n);
                    b.writeInt(1);
                    for (double n : values) b.writeDouble(n);
                    b.writeLong(100);
                    b.writeLong(100);
                    b.writeBoolean(true);
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SpacePackets.Frame.CODEC.decode(b));
                } finally {
                    b.release();
                }
            }
        assertThrows(IllegalArgumentException.class, () -> frame.rebased(-1));
        assertThrows(IllegalArgumentException.class, () -> frame.folded(ZERO, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> frame.folded(new Point(Double.NaN, 0, 0), 100));
        for (double bad : new double[] {-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> frame.dragged(bad, 100));
    }

    @Test
    void runtimeUsesConfiguredDragAndOnlyUntrackedRegionEntitiesReceiveTheFold() throws Exception {
        var source = Sources.text("SpaceServer");
        var fold =
                source.substring(
                        source.indexOf("var before=value.frame"),
                        source.indexOf("var universe=value.frame.universe(point"));
        for (String required :
                new String[] {
                    "getLinearVelocity(lead,new org.joml.Vector3d()).div(20)",
                    "v.length()>1e-9",
                    "gain(f.drift(),blendPush(f.universeVector(v),towardEntry,blend),piloted,0)",
                    "if(!piloted)value.frame=value.frame.dragged",
                    "for(var body:bodies)pipeline.addLinearAndAngularVelocity",
                    "if(in(entity))",
                    "body!=null&&bodies.contains(body)&&SableFrames.tracked(entity,body)",
                    "getDeltaMovement().subtract(v.x(),v.y(),v.z())",
                    "player.hurtMarked=true",
                    "DimensionPhysicsData.getUniversalDrag(level)/20",
                    "value.setDirty()"
                }) assertTrue(fold.contains(required), required);
        var reference =
                source.substring(
                        source.indexOf("if(nearer(model"),
                        source.indexOf("if(Thrusters.steadied(lead"));
        assertTrue(reference.contains("toward"));
        assertFalse(reference.contains("pipeline"));
        assertFalse(reference.contains("setDeltaMovement"));
        assertFalse(reference.contains("send"));
        assertTrue(source.contains("!before.rebased(tick).equals(value.frame)"));
        assertTrue(source.contains("send(player,true)"));
        assertTrue(source.contains("if(fresh)send(player);if(fresh||"));
        var client = Sources.text("SpaceClient");
        assertTrue(
                client.contains(
                        "frames.sample(eye(),renderTick(),GroundClient.ephemeris(),time())"));
    }
}
