package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

class ShipHoldTest {
    static final Point ZERO = GroundEphemeris.ZERO;

    @AfterEach
    void reset() {
        dev.starfold.chart.PlanetShape.reset();
    }

    static double degrees(Quaterniond a, Quaterniond b) {
        return Math.toDegrees(ShipParking.angle(a, b).length());
    }

    static Quaterniond integrate(Quaterniond q, Vector3d spin) {
        double angle = spin.length();
        return angle == 0
                ? q
                : new Quaterniond()
                        .rotationAxis(angle, spin.x / angle, spin.y / angle, spin.z / angle)
                        .mul(q)
                        .normalize();
    }

    @Test
    void leadAndFollowerKeepRecordedPlaceOverFixedSunSpinningPlanetAndMovingParentMoon() {
        var model = ShipParkingTest.model();
        for (int body : new int[] {0, 1, 3}) {
            for (boolean lead : new boolean[] {true, false}) simulate(model, body, lead);
        }
    }

    private static void simulate(GroundEphemeris.Model model, int body, boolean lead) {
        long start = 1234;
        var position = model.frame(body, start).world(new Point(1800, 210, -350));
        var attitude = model.frame(body, start).rotation().mul(new Quaterniond().rotationZ(.2));
        var held = ShipParking.capture(model, body, ZERO, position, attitude, start);
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationXYZ(.1, -.2, .3),
                        Space.ORIGIN,
                        0,
                        position.sub(model.at(0, start).center()),
                        new Point(.3, -.1, .2),
                        start);
        var motion =
                new Space.Motion(
                        Space.ORIGIN,
                        frame.levelOrientation(attitude),
                        new Point(.1, -.05, .02),
                        new Vector3d(.00001, -.00002, .00003));
        Point turn = ZERO;
        ShipRecord.Place previous = null;
        double earlyError = 0, lateError = 0;
        for (long tick = start; tick <= start + 7200; tick++) {
            var target = ShipParking.target(model, held, ZERO, tick);
            var next = ShipParking.target(model, held, ZERO, tick + 1);
            if (lead) {
                frame = frame.folded(motion.velocity(), tick);
                var impulse =
                        frame.universeVector(
                                new Point(motion.spin().x, motion.spin().y, motion.spin().z));
                var spin = turn.add(impulse);
                var universe =
                        new Space.Motion(
                                frame.universe(motion.position(), model, tick),
                                frame.universeOrientation(motion.orientation()),
                                frame.universeVelocity(ZERO, model, tick),
                                new Vector3d(spin.x(), spin.y(), spin.z()));
                var settled = ShipParking.settle(universe, target, next);
                frame = ShipParking.settleFrame(frame, model, settled.velocity(), tick);
                turn = new Point(settled.spin().x, settled.spin().y, settled.spin().z);
                frame = ShipParking.turnFrame(frame, turn, motion.position());
                motion =
                        new Space.Motion(
                                motion.position(), motion.orientation(), ZERO, new Vector3d());
            } else {
                motion =
                        ShipParking.settle(
                                motion,
                                frame.level(target, model, tick),
                                frame.level(next, model, tick + 1));
            }
            motion =
                    new Space.Motion(
                            motion.position().add(motion.velocity()),
                            integrate(motion.orientation(), motion.spin()),
                            motion.velocity(),
                            motion.spin());
            var place =
                    ShipParking.fromLevel(
                            model,
                            frame,
                            body,
                            ZERO,
                            motion.position(),
                            motion.orientation(),
                            tick + 1);
            double error = place.position().sub(held.position()).length();
            if (tick == start) earlyError = error;
            if (tick == start + 100) lateError = error;
            if (tick >= start + 100) {
                assertTrue(
                        error < .1, "position body=" + body + " lead=" + lead + " error=" + error);
                assertTrue(degrees(place.attitude(), held.attitude()) < .1);
            }
            if (tick >= start + 2400 && (tick - start) % 1200 == 0) {
                if (previous != null) {
                    assertTrue(place.position().sub(previous.position()).length() < .5);
                    assertTrue(degrees(place.attitude(), previous.attitude()) < .01);
                }
                previous = place;
            }
        }
        assertTrue(lateError < earlyError);
    }

    @Test
    void boundedDampedCorrectionConvergesWithoutCrossingTheTarget() {
        for (double displacement : new double[] {.007, 1, 8, 31}) {
            double x = displacement, v = 0;
            for (int tick = 0; tick < 1200; tick++) {
                var correction =
                        ShipParking.correction(
                                new Point(-x, 0, 0), new Point(v, 0, 0), ShipParking.MAX_SPEED);
                v = correction.x();
                assertTrue(Math.abs(v) <= ShipParking.MAX_SPEED);
                double next = x + v;
                assertTrue(next >= -1e-12 && next <= x + 1e-12, "No oscillation");
                x = next;
            }
            assertTrue(Math.abs(x) < 1e-9);
        }
        assertEquals(
                ShipParking.MAX_SPEED,
                ShipParking.correction(
                                new Point(100, 200, 300),
                                new Point(50, -30, 2),
                                ShipParking.MAX_SPEED)
                        .length(),
                1e-12);
        assertEquals(
                ShipParking.MAX_SPIN,
                ShipParking.correction(new Point(1, 2, 3), ZERO, ShipParking.MAX_SPIN).length(),
                1e-12);
    }

    @Test
    void targetUsesRecordedAnchorAndShortestAttitudeEvenWhenComChanges() {
        var model = ShipParkingTest.model();
        var held =
                ShipParking.capture(
                        model,
                        1,
                        new Point(10, 20, 30),
                        new Point(3000, 400, 200),
                        new Quaterniond().rotationZ(.3),
                        100);
        var target = ShipParking.target(model, held, held.anchorPlot(), 9000);
        assertEquals(ShipParking.evaluate(model, held, 9000), target);
        var delta = new Point(2, -3, 4);
        var shifted = ShipParking.target(model, held, held.anchorPlot().add(delta), 9000);
        var offset = target.orientation().transform(new Vector3d(2, -3, 4));
        ShipParkingTest.close(
                target.position().add(new Point(offset.x, offset.y, offset.z)),
                shifted.position(),
                1e-12);
        var velocity = target.spin().cross(offset, new Vector3d());
        ShipParkingTest.close(
                target.velocity().add(new Point(velocity.x, velocity.y, velocity.z)),
                shifted.velocity(),
                1e-12);
        assertEquals(target.orientation(), shifted.orientation());
        var q = new Quaterniond().rotationY(Math.toRadians(359));
        assertEquals(Math.toRadians(1), ShipParking.angle(new Quaterniond(), q).length(), 1e-12);
        assertEquals(
                0, ShipParking.angle(q, new Quaterniond(-q.x, -q.y, -q.z, -q.w)).length(), 1e-12);
    }

    @Test
    void largeDisplacementYieldHasExplicitThresholds() {
        var target = new Space.Motion(ZERO, new Quaterniond(), ZERO, new Vector3d());
        assertFalse(ShipParking.yields(new Point(32, 0, 0), new Quaterniond(), target));
        assertTrue(ShipParking.yields(new Point(32.001, 0, 0), new Quaterniond(), target));
        assertFalse(
                ShipParking.yields(
                        ZERO,
                        new Quaterniond().rotationAxis(Math.toRadians(44.99), 1, 0, 0),
                        target));
        assertTrue(
                ShipParking.yields(
                        ZERO,
                        new Quaterniond().rotationAxis(Math.toRadians(45.01), 1, 0, 0),
                        target));
    }

    @Test
    void bindingSurvivesNewNearestAndRefreshAndSerializationButEndsAtHelmOrUnload() {
        var saved = ShipSave.fresh(false);
        var observer = new ShipObserver(saved);
        var uuid = UUID.randomUUID();
        var model = ShipParkingTest.model();
        var release =
                ShipParking.capture(
                        model,
                        1,
                        ZERO,
                        model.frame(1, 100).world(new Point(1800, 0, 0)),
                        new Quaterniond(),
                        100);
        var state =
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION, Optional.empty(), new ShipRecord.InSpace(release));
        observer.added(uuid, () -> state);
        assertNull(
                observer.bind(
                        uuid,
                        true,
                        () -> {
                            throw new AssertionError("Pilot not sampled");
                        }));
        assertSame(release, observer.bind(uuid, false, () -> release));
        var nearer = model.frame(3, 200).world(new Point(1500, 0, 0));
        assertEquals(3, ShipParking.nearestBody(model, nearer, 200));
        assertSame(
                release,
                observer.bind(
                        uuid,
                        false,
                        () -> {
                            throw new AssertionError("No recapture");
                        }));
        observer.serialized(uuid, state);
        observer.refresh();
        assertSame(release, observer.hold(uuid));
        assertEquals(release, ((ShipRecord.InSpace) saved.find(uuid).state()).place());
        assertNull(observer.bind(uuid, true, null));
        assertNull(observer.hold(uuid));
        var current = ShipParking.capture(model, 3, ZERO, nearer, new Quaterniond(), 200);
        assertSame(current, observer.bind(uuid, false, () -> current));
        observer.serialized(
                uuid,
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION, Optional.empty(), new ShipRecord.InSpace(current)));
        observer.removed(uuid, false);
        assertNull(observer.hold(uuid));
        assertNotNull(saved.find(uuid));
        observer.added(
                uuid,
                () -> {
                    assertSame(current, observer.hold(uuid));
                    return new ShipObserver.Sample(
                            ShipSaveTest.DIMENSION,
                            Optional.empty(),
                            new ShipRecord.InSpace(current));
                });
        assertSame(current, observer.hold(uuid));
        observer.bind(uuid, true, null); // Same invalidation used by a large-displacement yield.
        assertSame(release, observer.bind(uuid, false, () -> release));
    }

    @Test
    void pilotedTrajectoriesSkipSettlingEvenWithoutGyro() {
        var observer = new ShipObserver(ShipSave.fresh(false));
        var uuid = UUID.randomUUID();
        var model = ShipParkingTest.model();
        var held =
                ShipParking.capture(model, 1, ZERO, new Point(1800, 0, 0), new Quaterniond(), 100);
        var old =
                new Space.Motion(
                        held.position(),
                        held.attitude(),
                        new Point(.3, .2, .1),
                        new Vector3d(.001, .002, .003));
        var next = old;
        observer.bind(uuid, false, () -> held);
        for (long tick = 100; tick < 2500; tick++) {
            observer.bind(uuid, true, null);
            if (observer.hold(uuid) != null)
                next =
                        ShipParking.settle(
                                next,
                                ShipParking.evaluate(model, held, tick),
                                ShipParking.evaluate(model, held, tick + 1));
            old =
                    new Space.Motion(
                            old.position().add(old.velocity()),
                            integrate(old.orientation(), old.spin()),
                            old.velocity(),
                            old.spin());
            next =
                    new Space.Motion(
                            next.position().add(next.velocity()),
                            integrate(next.orientation(), next.spin()),
                            next.velocity(),
                            next.spin());
            assertEquals(old, next);
        }
    }

    @Test
    void settlingChangesOnlyMotionAndCorrectsAttitudeWithoutSpin() {
        var target = new Space.Motion(ZERO, new Quaterniond(), ZERO, new Vector3d());
        var motion =
                new Space.Motion(
                        new Point(1, 2, 3), new Quaterniond().rotationX(.3), ZERO, new Vector3d());
        var next = ShipParking.settle(motion, target, target);
        assertEquals(motion.position(), next.position());
        assertEquals(motion.orientation(), next.orientation());
        assertTrue(next.velocity().dot(motion.position()) < 0);
        assertTrue(next.spin().x < 0);
        assertEquals(target, ShipParking.settle(target, target, target));
    }

    @Test
    void probeRequiresAllThreeExactServerTickReadingsAndNoHoldWhilePiloted() throws Exception {
        var probe = Sources.text("DevShipHoldProbe");
        assertTrue(probe.contains("elapsed==100||elapsed==400||elapsed==1200"));
        assertTrue(probe.contains("level.getGameTime()-window.start()"));
        assertTrue(probe.contains("error[0]<.1&&error[1]<.1"));
        assertTrue(probe.contains("window.readings()==3&&window.passed()"));
        assertTrue(probe.contains("if(held!=null)throw new IllegalStateException"));
        assertTrue(
                Sources.text("DevShipPersistenceProbe").contains("DevShipHoldProbe.tick(level)"));
    }

    @Test
    void wiringProtectsPilotFinalFrameHeldRecordAndPhysicsPose() throws Exception {
        var source = Sources.text("SpaceServer");
        assertTrue(
                source.indexOf("ShipRecords.binding(level,body)")
                        < source.indexOf("advanceLead(level,container,bodies,lead,value,clock)"));
        assertTrue(
                source.indexOf("settleFollowers(level,container,bodies,lead,value)")
                        > source.indexOf("advanceLead(level,container,bodies,lead,value,clock)"));
        var follower =
                source.substring(
                        source.indexOf("private static void settleFollowers"),
                        source.indexOf("private static void shiftRegion"));
        assertTrue(follower.contains("if(body==lead||ShipRecords.piloted(level,body))continue"));
        assertTrue(
                follower.contains(
                        "if(held==null){coastFollower(level,container,body,value.frame);continue;}"));
        assertTrue(
                follower.contains(
                        "value.frame.level(ShipParking.target(clock.model,held,plot,clock.tick())"));
        assertTrue(follower.contains("ShipParking.target(clock.model,held,plot,clock.tick()+1)"));
        assertTrue(follower.contains("ShipParking.settle(motion,target,next)"));
        assertFalse(follower.contains("SableFrames.move"));
        var drag =
                source.substring(
                        source.indexOf("private static void dragFrame"),
                        source.indexOf("private static void coastLead"));
        assertTrue(drag.contains("ShipParking.target(model,held,plot,tick)"));
        assertTrue(
                drag.contains(
                        "if(held==null){coastLead(value,level,container,bodies,lead,tick);return;}"));
        assertTrue(drag.contains("ShipParking.settle(motion,target,next)"));
        assertFalse(drag.contains("angular.equals(GroundEphemeris.ZERO)"));
        var coast =
                source.substring(
                        source.indexOf("private static void coastLead"),
                        source.indexOf("private static Point spinPoint"));
        assertTrue(
                coast.contains(
                        "if(ShipRecords.piloted(level,lead)){value.clearHoldTurn();value.frame=value.frame.dragged(dragFactor(level),tick);return;}"));
        assertTrue(
                coast.indexOf("ShipRecords.piloted(level,lead)")
                        < coast.indexOf("ShipParking.coastFrame"));
        var records = Sources.text("ShipRecords");
        assertTrue(records.contains("held!=null?held:ShipParking.capture"));
        assertTrue(records.contains("observer.bind(hull.getUniqueId(),piloted(level,hull)"));
        assertTrue(records.contains("captureWhenSlow(level,hull,ShipParking.capture"));
        assertTrue(records.contains("ShipHost.get().carriedSpin(level,hull.getUniqueId())"));
        assertTrue(
                records.contains(
                        "ShipParking.slow(motion,ShipParking.evaluate(clock.model,place,clock.tick()))?place:null"));
        assertTrue(records.contains("if(!ShipParking.yields("));
        assertTrue(records.contains("observer.bind(hull.getUniqueId(),true,null)"));
        var helm = Sources.text("HelmServer");
        assertTrue(
                helm.contains(
                        "sessions.put(body,session);ShipRecords.binding(player.serverLevel(),body)"));
        assertTrue(
                helm.contains(
                        "sessions.remove(s.body);pilots.release(s.body);if(!s.body.isRemoved()&&s.body.getLevel() instanceof net.minecraft.server.level.ServerLevel level)ShipRecords.binding(level,s.body)"));
        var gyro = Sources.text("Thrusters");
        assertFalse(gyro.contains("spaceDormancy()"));
        assertTrue(
                gyro.contains(
                        "ShipRecords.hold(level,body)!=null&&!ShipRecords.piloted(level,body)"));
        var probe = Sources.text("DevShipPersistenceProbe");
        assertTrue(probe.contains("case \"motion\""));
        assertTrue(probe.contains("held.anchorPlot()"));
        assertTrue(probe.contains("local_release="));
        assertTrue(probe.contains("degrees="));
    }
}
