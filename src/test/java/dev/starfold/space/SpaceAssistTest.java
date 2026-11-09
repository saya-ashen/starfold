package dev.starfold.space;

import static dev.starfold.sky.SpaceTest.point;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.Thrusters;

import org.junit.jupiter.api.Test;

class SpaceAssistTest {
    @Test
    void sidewaysAndBackwardOnlyDampPerpendicularAtTheDecidedRate() {
        assertEquals(1.5, SpaceFlightMath.ASSIST);
        var nose = new Point(0, 0, 1);
        double factor = 1 / (1 + 1.5 / 20);
        for (double along : new double[] {-120, 0}) {
            var drift = new Point(30, -40, along);
            for (int tick = 1; tick <= 100; tick++) {
                drift = SpaceFlightMath.assist(drift, nose, true);
                point(
                        new Point(30 * Math.pow(factor, tick), -40 * Math.pow(factor, tick), along),
                        drift);
            }
        }
    }

    @Test
    void forwardKeepsLengthAndDampsThePerpendicularBeforeRescaling() {
        var nose = new Point(0, 0, 1);
        var drift = new Point(30, -40, 120);
        double factor = 1 / (1 + 1.5 / 20), speed = drift.length();
        for (int tick = 1; tick <= 100; tick++) {
            drift = SpaceFlightMath.assist(drift, nose, true);
            assertEquals(speed, drift.length(), 1e-10);
            assertEquals(30 * Math.pow(factor, tick) / 120, drift.x() / drift.z(), 1e-10);
            assertEquals(-40 * Math.pow(factor, tick) / 120, drift.y() / drift.z(), 1e-10);
        }
    }

    @Test
    void arbitraryHeadingsKeepForwardSpeedAndBackwardParallel() {
        var random = new java.util.Random(36);
        for (int i = 0; i < 1000; i++) {
            var n =
                    new Point(
                            random.nextDouble() - .5,
                            random.nextDouble() - .5,
                            random.nextDouble() - .5);
            n = n.scale(1 / n.length());
            var d =
                    new Point(
                            random.nextDouble() * 200 - 100,
                            random.nextDouble() * 200 - 100,
                            random.nextDouble() * 200 - 100);
            var next = SpaceFlightMath.assist(d, n, true);
            if (d.dot(n) > 0) assertEquals(d.length(), next.length(), 1e-10);
            else {
                assertTrue(next.length() <= d.length() + 1e-10);
                assertEquals(d.dot(n), next.dot(n), 1e-10);
            }
        }
    }

    @Test
    void noForwardThrustIsUnchangedAndZeroStaysZero() {
        var nose = new Point(0, 0, 1);
        for (var d :
                new Point[] {
                    new Point(30, -40, 120),
                    new Point(0, 150, 0),
                    new Point(30, 40, -120),
                    GroundEphemeris.ZERO
                }) point(d, SpaceFlightMath.assist(d, nose, false));
        point(GroundEphemeris.ZERO, SpaceFlightMath.assist(GroundEphemeris.ZERO, nose, true));
        for (double along : new double[] {-120, 120})
            point(nose.scale(along), SpaceFlightMath.assist(nose.scale(along), nose, true));
    }

    @Test
    void poweredPushIsSummedPerTickAndExpiresAfterTwoTicks() {
        var nose = new Point(.6, 0, .8);
        var up = new Point(0, 1, 0);
        var push = new Thrusters.Push(10, up);
        assertFalse(push.forward(10, nose));
        push = push.add(10, nose);
        assertTrue(push.forward(10, nose));
        assertTrue(push.forward(12, nose));
        assertFalse(push.forward(13, nose));
        assertFalse(push.forward(9, nose));
        point(up.add(nose), push.direction());
        assertFalse(push.add(10, nose.scale(-1)).forward(10, nose));
        push = push.add(11, up);
        point(up, push.direction());
        assertFalse(push.forward(11, nose));
        assertFalse(new Thrusters.Push(10, nose.scale(.5)).forward(10, nose));
        assertTrue(new Thrusters.Push(10, nose.scale(.50001)).forward(10, nose));
    }

    @Test
    void pointerAimClampsAndChasesWithoutOvershoot() {
        assertEquals(45, SpaceAttitudeClient.AIM);
        assertEquals(120, SpaceAttitudeClient.AIM_TURN);
        assertEquals(.16, SpaceAttitudeClient.CHASE);
        assertEquals(.02, SpaceAttitudeClient.EPSILON);
        var centre = new SpaceAttitudeClient.Pointer(0, 0);
        var p = centre.move(30, 0);
        double total = 0;
        for (int i = 0; i < 100; i++) {
            var turn = p.step();
            assertTrue(Math.hypot(turn.x(), turn.y()) <= 6);
            assertTrue(turn.x() >= 0 && turn.x() <= p.x());
            total += turn.x();
            p = p.remaining(turn);
            if (i == 3) assertEquals(15, p.x(), .1);
        }
        assertEquals(30, total, 1e-10);
        assertEquals(centre, p);
        assertEquals(centre, centre.step());
        var full = centre.move(200, 200);
        assertEquals(45, Math.hypot(full.x(), full.y()), 1e-10);
        assertEquals(6, Math.hypot(full.step().x(), full.step().y()), 1e-10);
        assertEquals(45, centre.move(200, 0).x());
        p = centre.move(30, 0);
        p = p.remaining(p.step()).move(15, 0);
        assertEquals(40.2, p.x(), 1e-10);
        assertTrue(centre.move(0, -15).step().y() < 0);
    }

    @Test
    void weightDoesNotConsultTheGraceAndAssistDoesNotDependOnAWish() throws Exception {
        var weight = SpaceBytecode.calls("space/SpaceServer", "weighted");
        assertTrue(weight.stream().anyMatch(c -> c.endsWith(".getTrackingSubLevel")));
        assertTrue(weight.contains("Set.contains"));
        assertFalse(weight.contains("SableFrames.aboard"));
        var cabins = SpaceBytecode.calls("space/SpaceServer", "cabins");
        SpaceBytecode.before(cabins, "ServerSubLevel.isRemoved", "SableFrames.inside");
        assertTrue(cabins.contains("Space.in"));
        var advance = SpaceBytecode.calls("space/SpaceServer", "advanceLead");
        SpaceBytecode.before(advance, "Thrusters.steadied", "SpaceFlightMath.assist");
        SpaceBytecode.before(advance, "SpaceServer.helm", "SpaceFlightMath.assist");
        SpaceBytecode.before(advance, "HelmServer.forward", "SpaceFlightMath.assist");
        SpaceBytecode.before(advance, "Thrusters.forward", "SpaceFlightMath.assist");
        var assist = SpaceBytecode.method("space/SpaceFlightMath", "assist");
        assertFalse(java.util.stream.StreamSupport.stream(assist.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof org.objectweb.asm.tree.FieldInsnNode f
                        && f.owner.equals("dev/starfold/space/SpaceSessions")));
    }
}
