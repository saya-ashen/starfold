package dev.starfold.space;

import static dev.starfold.sky.SpaceTest.point;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class SpaceCruiseTest {
    private static final dev.starfold.chart.GroundEphemeris.Body BODY =
            dev.starfold.chart.GroundSolar.defaults().body(0);
    // Historical dynamics fixture: caller-supplied caps, not production defaults.
    private static final SpaceSpeed.Caps CAPS = new SpaceSpeed.Caps(300, 800);
    private static final Point ZERO = GroundEphemeris.ZERO, NOSE = new Point(0, 0, 1);

    private static Point step(Point drift, double acceleration, boolean boost) {
        var next = SpaceServer.gain(drift, NOSE.scale(acceleration / 400), true, .09);
        return SpaceServer.cruise(drift, next, NOSE.scale(Math.signum(acceleration)), boost, CAPS);
    }

    @Test
    void decidedConstants() {
        assertEquals(3, SpaceServer.VACUUM);
        assertEquals(600, SpaceSpeed.V_REF);
        assertEquals(2400, SpaceSpeed.V_MAX);
        assertEquals(1, SpaceServer.SHED);
        assertEquals(40, SpaceServer.BRAKE);
        assertEquals(1, SpaceServer.BRAKE_FLOOR);
        assertEquals(150, SpaceServer.ARRIVE);
        assertEquals(6, SpaceServer.ARRIVE_TIME);
        assertEquals(3, SpaceServer.NEAR_TIME);
    }

    @Test
    void coastKeepsVelocityForAThousandTicks() {
        var original = new Point(3, 4, 10);
        var drift = original;
        for (int i = 0; i < 1000; i++) drift = step(drift, 0, false);
        point(original, drift);
    }

    @Test
    void forwardReachesCruiseAndStays() {
        var drift = ZERO;
        for (int i = 0; i < 1000; i++) {
            drift = step(drift, 4, false);
            assertEquals(Math.min(300, (i + 1) * .6), drift.length() * 20, 1e-8);
        }
    }

    @Test
    void boostReachesEightHundredAndStays() {
        var drift = ZERO;
        for (int i = 0; i < 1000; i++) {
            drift = step(drift, 16, true);
            assertEquals(Math.min(800, (i + 1) * 2.4), drift.length() * 20, 1e-8);
        }
    }

    @Test
    void excessShedsWithoutASnapAndThrustCannotReplaceIt() {
        for (double thrust : new double[] {0, 4}) {
            var drift = NOSE.scale(40);
            for (int i = 1; i <= 200; i++) {
                drift = step(drift, thrust, false);
                assertEquals(300 + 500 * Math.pow(1 / 1.05, i), drift.length() * 20, 1e-8);
            }
        }
    }

    @Test
    void brakeStopsThenHeldThrustReverses() {
        var drift = NOSE.scale(15);
        int ticks = 0;
        while (drift.z() > 1.0 / 20 && ticks < 200) {
            drift = step(drift, -4, false);
            ticks++;
        }
        assertTrue(ticks >= 115 && ticks <= 116);
        assertTrue(drift.length() * 20 <= 1);
        for (int i = 0; i < 3; i++) drift = step(drift, -4, false);
        assertTrue(drift.z() < 0);
        assertEquals(-.6, step(ZERO, -4, false).z() * 20, 1e-10);
        assertEquals(-2.2, step(NOSE.scale(.01), -16, true).z() * 20, 1e-10);
    }

    @Test
    void brakeFloorIsInclusiveAndThrustActsPlainly() {
        for (double speed : new double[] {0, .02, .2, 1})
            for (double acceleration : new double[] {-4, -16})
                assertEquals(
                        speed + acceleration * 3 / 20,
                        step(NOSE.scale(speed / 20), acceleration, true).z() * 20,
                        1e-10);
        point(ZERO, step(NOSE.scale(1.0001 / 20), -16, true));
    }

    @Test
    void aboveFloorIsBitForBitDev43Cruise() {
        var random = new java.util.Random(43);
        for (int i = 0; i < 1000; i++) {
            var before =
                    new Point(
                            random.nextDouble() * 80 - 40,
                            random.nextDouble() * 80 - 40,
                            random.nextDouble() * 80 - 40);
            var push =
                    new Point(
                            random.nextDouble() * 2 - 1,
                            random.nextDouble() * 2 - 1,
                            random.nextDouble() * 2 - 1);
            var drift = before.add(push);
            assertTrue(before.length() > 1.0 / 20);
            for (boolean boosted : new boolean[] {false, true})
                assertEquals(
                        oldCruise(before, drift, push, boosted),
                        SpaceServer.cruise(before, drift, push, boosted, CAPS));
        }
    }

    private static Point oldCruise(Point before, Point drift, Point push, boolean boosted) {
        double old = before.length(), speed = drift.length();
        boolean braking = old > 0 && push.dot(before.scale(1 / old)) < -.5;
        if (braking) {
            if (drift.dot(before) <= 0 || speed <= 40.0 / 400) return ZERO;
            drift = drift.scale((speed - 40.0 / 400) / speed);
            speed = drift.length();
        }
        double limit = (boosted ? 800.0 : 300.0) / 20,
                allowed = limit + Math.max(0, old - limit) / (1 + 1.0 / 20);
        return speed > allowed ? drift.scale(allowed / speed) : drift;
    }

    @Test
    void brakeThresholdIsStrictAndNotForwardOrSideways() {
        var d = NOSE.scale(10);
        point(d, SpaceServer.cruise(d, d, new Point(1, 0, -.5), false, CAPS));
        point(d, SpaceServer.cruise(d, d, NOSE, false, CAPS));
        assertEquals(
                9.9, SpaceServer.cruise(d, d, new Point(1, 0, -.5001), false, CAPS).z(), 1e-10);
    }

    @Test
    void arrivalChangesOnlyExcessClosing() {
        var drift = new Point(3, 4, 40);
        point(new Point(3, 4, 7.5), SpaceServer.arrival(BODY, drift, NOSE, drift, 1536));
        point(new Point(3, 4, 12.5), SpaceServer.arrival(BODY, drift, NOSE, drift, 1536 + 600));
        point(new Point(3, 4, 7.5), SpaceServer.arrival(BODY, drift, NOSE, drift, 0));
        for (double z : new double[] {-40, 0, 7.5}) {
            var d = new Point(3, 4, z);
            point(d, SpaceServer.arrival(BODY, d, NOSE, d, 1536));
        }
        point(drift, SpaceServer.arrival(BODY, drift, ZERO, drift, 0));
    }

    @Test
    void governorUsesNearestRelativeVelocityNotReferenceDrift() {
        var d = new Point(3, 4, 10);
        point(new Point(3, 4, 5.5), SpaceServer.arrival(BODY, d, NOSE, d.add(NOSE.scale(2)), 1536));
        var random = new java.util.Random(41);
        for (int i = 0; i < 1000; i++) {
            var n =
                    new Point(
                            random.nextDouble() - .5,
                            random.nextDouble() - .5,
                            random.nextDouble() - .5);
            n = n.scale(1 / n.length());
            var v =
                    new Point(
                            random.nextDouble() * 80 - 40,
                            random.nextDouble() * 80 - 40,
                            random.nextDouble() * 80 - 40);
            var next = SpaceServer.arrival(BODY, v, n, v, 1536);
            assertTrue(next.dot(n) <= v.dot(n) + 1e-10);
            point(v.sub(n.scale(v.dot(n))), next.sub(n.scale(next.dot(n))));
            assertTrue(next.dot(n) <= 7.5 + 1e-10);
        }
    }

    @Test
    void wholeLimitSpendsBudgetOnRadialFirst() {
        var d = new Point(3, 0, 4);
        point(d, SpaceServer.near(BODY, d, NOSE, d, 1536));
        d = new Point(30, 0, 6);
        for (double height : new double[] {1536, 1536 + 450, 1536 + 1950}) {
            double limit = (150 + Math.max(0, height - 1536) / 3) / 20;
            point(
                    new Point(Math.min(30, Math.sqrt(limit * limit - 36)), 0, 6),
                    SpaceServer.near(BODY, d, NOSE, d, height));
        }
    }

    @Test
    void wholeLimitNeverRaisesAndBoundsTangentInEveryDirection() {
        var random = new java.util.Random(43);
        for (double height : new double[] {-100, 0, 1536, 1536 + 450, 1536 + 1950, 5000})
            for (int i = 0; i < 1000; i++) {
                var toward =
                        new Point(
                                random.nextDouble() - .5,
                                random.nextDouble() - .5,
                                random.nextDouble() - .5);
                var n = toward.scale(1 / toward.length());
                var d =
                        new Point(
                                random.nextDouble() * 80 - 40,
                                random.nextDouble() * 80 - 40,
                                random.nextDouble() * 80 - 40);
                var next = SpaceServer.near(BODY, d, toward, d, height);
                double along = d.dot(n), limit = (150 + Math.max(0, height - 1536) / 3) / 20;
                var tangent = d.sub(n.scale(along));
                var after = next.sub(n.scale(next.dot(n)));
                assertEquals(along, next.dot(n), 1e-10);
                assertTrue(next.length() <= d.length() + 1e-10);
                assertTrue(
                        after.length()
                                <= Math.sqrt(Math.max(0, limit * limit - along * along)) + 1e-10);
                assertTrue(next.length() <= Math.max(limit, Math.abs(along)) + 1e-10);
                if (tangent.length() > 0)
                    point(tangent.scale(after.length() / tangent.length()), after);
            }
        point(ZERO, SpaceServer.near(BODY, ZERO, NOSE, ZERO, 0));
    }

    @Test
    void tangentialThrustCannotBleedClosingOverTwoHundredTicks() {
        var random = new java.util.Random(43);
        for (double height : new double[] {1536, 1536 + 450, 1536 + 1950}) {
            double closing = (150 + Math.max(0, height - 1536) / 6) / 20;
            var d = NOSE.scale(closing);
            for (int i = 0; i < 200; i++) {
                var thrust =
                        new Point(random.nextDouble() * 80 - 40, random.nextDouble() * 80 - 40, 0);
                var pushed = d.add(thrust);
                d = SpaceServer.near(BODY, pushed, NOSE, pushed, height);
                assertEquals(closing, d.dot(NOSE), 1e-10);
                assertTrue(d.length() <= pushed.length() + 1e-10);
                double limit = (150 + Math.max(0, height - 1536) / 3) / 20;
                assertTrue(
                        d.sub(NOSE.scale(closing)).length()
                                <= Math.sqrt(Math.max(0, limit * limit - closing * closing))
                                        + 1e-10);
            }
        }
    }

    @Test
    void recedingRadialIsUntouchedEvenAboveWholeLimit() {
        for (double z : new double[] {-6, -40}) {
            var d = new Point(30, 0, z);
            var next = SpaceServer.near(BODY, d, NOSE, d, 1536);
            assertEquals(z, next.z());
            assertEquals(Math.sqrt(Math.max(0, 7.5 * 7.5 - z * z)), next.x(), 1e-10);
            assertEquals(0, next.y());
        }
    }

    @Test
    void exactZeroTangentStaysExactlyZero() {
        for (double z : new double[] {-40, -7.5, 0, 7.5, 40}) {
            var d = NOSE.scale(z);
            assertEquals(d, SpaceServer.near(BODY, d, NOSE, d, 1536));
        }
        var d = new Point(30, 0, 40);
        assertEquals(d, SpaceServer.near(BODY, d, ZERO, d, 0));
    }

    @Test
    void wholeLimitFarAwayLeavesEightHundredUntouched() {
        for (var d : new Point[] {NOSE.scale(40), NOSE.scale(-40), new Point(24, 32, 0)})
            assertEquals(d, SpaceServer.near(BODY, d, NOSE, d, 5000));
    }

    @Test
    void wholeLimitUsesNearestPlanetAndPreservesRadialBound() {
        var d = new Point(30, 0, 40);
        var offset = new Point(2, 3, 4);
        var relative = d.add(offset);
        var radial = SpaceServer.arrival(BODY, d, NOSE, relative, 1536);
        var radialRelative = radial.add(offset);
        var next = SpaceServer.near(BODY, radial, NOSE, radialRelative, 1536);
        point(NOSE.scale(7.5), next.add(offset));
        assertEquals(radialRelative.dot(NOSE), next.add(offset).dot(NOSE), 1e-10);
        assertEquals(7.5, next.add(offset).length(), 1e-10);
    }

    @Test
    void unpilotedFoldAndDragAreExactlyTheOldFormula() {
        var d = new Point(3, 4, 10);
        var impulse = new Point(.01, -.02, .03);
        double factor = 1 / (1 + .09 / 20);
        for (int i = 0; i < 1000; i++) {
            var expected = d.add(impulse).scale(factor);
            d = SpaceServer.gain(d, impulse, false, .09);
            assertEquals(expected, d);
        }
    }

    @Test
    void integrationKeepsHullRelabellingAndLimitsAfterAssist() throws Exception {
        var s = Sources.text("SpaceServer");
        assertTrue(s.contains("anyMatch(player->helm(player,lead))"));
        assertTrue(s.contains("if(!piloted)dragFrame(value,level,container,bodies,lead,tick)"));
        assertTrue(
                s.contains(
                        "value.frame=ShipParking.coastFrame(value.frame,model,body,point,factor,tick)"));
        assertTrue(
                Sources.text("ShipParking")
                        .contains(
                                "else if(drift.equals(GroundEphemeris.ZERO))return frame.dragged(factor,tick)"));
        assertTrue(s.contains("double factor=dragFactor(level)"));
        assertTrue(s.contains("return 1/(1+"));
        assertTrue(s.contains("new org.joml.Vector3d(-v.x()*20,-v.y()*20,-v.z()*20)"));
        assertTrue(s.indexOf("drift=cruise(prior") > s.indexOf("assist(f.drift()"));
        int arrival = s.indexOf("var arrived=arrival(body,drift"),
                near =
                        s.indexOf(
                                "return near(body,arrived,toward,relative.add(arrived.sub(drift)),height)"),
                governed = s.indexOf("var limited=govern(model.body(nearest),drift"),
                prepare = s.indexOf("SpaceApproach.update(level,container,lead)");
        assertTrue(arrival >= 0 && near > arrival);
        assertTrue(governed >= 0 && prepare > governed);
    }
}
