package dev.starfold.space;

import static dev.starfold.sky.SpaceTest.point;
import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class SpaceCruiseTest {
    private static final GroundEphemeris.Body BODY =
            dev.starfold.chart.GroundSolar.defaults().body(0);
    private static final Point ZERO = GroundEphemeris.ZERO, NOSE = new Point(0, 0, 1);

    @Test
    void accelerationHasNoVacuumMultiplierOrCruiseLimit() {
        var drift = ZERO;
        for (int i = 0; i < 10000; i++)
            drift = SpaceSpeed.limit(SpaceFlightMath.gain(drift, NOSE.scale(4.0 / 400)));
        assertEquals(2000, drift.length() * 20, 1e-8);
        var original = new Point(3, 4, 200);
        drift = original;
        for (int i = 0; i < 1000; i++) drift = SpaceFlightMath.gain(drift, ZERO);
        point(original, drift);
    }

    @Test
    void opposingThrustRequestsStopRelativeToLockWithoutGivingFreeBraking() {
        var reference = new Point(4, 2, -3);
        var drift = reference.add(NOSE.scale(10));
        point(reference, SpaceFlightMath.brake(drift, drift, NOSE.scale(-1), reference));
        point(drift, SpaceFlightMath.brake(drift, drift, NOSE, reference));
        point(drift, SpaceSpeed.maneuver(drift, reference, 1, dv -> 0));
        point(reference.add(NOSE.scale(9.9)),
                SpaceSpeed.maneuver(drift, reference, 1, dv -> 2));
    }

    @Test
    void arrivalChangesOnlyExcessClosingAndNearPreservesRadial() {
        var drift = new Point(30, 4, 40);
        var radial = SpaceFlightMath.arrival(BODY, drift, NOSE, drift, 1536);
        point(new Point(30, 4, 7.5), radial);
        var next = SpaceFlightMath.near(BODY, radial, NOSE, radial, 1536);
        point(NOSE.scale(7.5), next);
        for (double z : new double[] {-40, 0, 7.5}) {
            var d = new Point(3, 4, z);
            point(d, SpaceFlightMath.arrival(BODY, d, NOSE, d, 1536));
        }
        point(drift, SpaceFlightMath.arrival(BODY, drift, ZERO, drift, 0));
    }

    @Test
    void wholeLimitNeverRaisesAndBoundsTangentInEveryDirection() {
        var random = new java.util.Random(43);
        for (double height : new double[] {-100, 0, 1536, 1986, 3486, 5000})
            for (int i = 0; i < 1000; i++) {
                var toward = new Point(random.nextDouble() - .5,
                        random.nextDouble() - .5, random.nextDouble() - .5);
                var n = toward.scale(1 / toward.length());
                var d = new Point(random.nextDouble() * 80 - 40,
                        random.nextDouble() * 80 - 40, random.nextDouble() * 80 - 40);
                var next = SpaceFlightMath.near(BODY, d, toward, d, height);
                double along = d.dot(n), limit = (150 + Math.max(0, height - 1536) / 3) / 20;
                var tangent = d.sub(n.scale(along));
                var after = next.sub(n.scale(next.dot(n)));
                assertEquals(along, next.dot(n), 1e-10);
                assertTrue(next.length() <= d.length() + 1e-10);
                assertTrue(after.length() <= Math.sqrt(Math.max(0, limit * limit - along * along)) + 1e-10);
                assertTrue(next.length() <= Math.max(limit, Math.abs(along)) + 1e-10);
                if (tangent.length() > 0) point(tangent.scale(after.length() / tangent.length()), after);
            }
        point(ZERO, SpaceFlightMath.near(BODY, ZERO, NOSE, ZERO, 0));
    }

    @Test
    void tangentialThrustCannotBleedClosingOverTwoHundredTicks() {
        var random = new java.util.Random(43);
        for (double height : new double[] {1536, 1986, 3486}) {
            double closing = (150 + Math.max(0, height - 1536) / 6) / 20;
            var d = NOSE.scale(closing);
            for (int i = 0; i < 200; i++) {
                var thrust = new Point(random.nextDouble() * 80 - 40,
                        random.nextDouble() * 80 - 40, 0);
                var pushed = d.add(thrust);
                d = SpaceFlightMath.near(BODY, pushed, NOSE, pushed, height);
                assertEquals(closing, d.dot(NOSE), 1e-10);
                assertTrue(d.length() <= pushed.length() + 1e-10);
                double limit = (150 + Math.max(0, height - 1536) / 3) / 20;
                assertTrue(d.sub(NOSE.scale(closing)).length()
                        <= Math.sqrt(Math.max(0, limit * limit - closing * closing)) + 1e-10);
            }
        }
    }

    @Test
    void recedingAndExactZeroTangentRemainUntouched() {
        for (double z : new double[] {-40, -7.5, 0, 7.5, 40}) {
            var d = NOSE.scale(z);
            assertEquals(d, SpaceFlightMath.near(BODY, d, NOSE, d, 1536));
        }
        for (double z : new double[] {-6, -40}) {
            var d = new Point(30, 0, z);
            var next = SpaceFlightMath.near(BODY, d, NOSE, d, 1536);
            assertEquals(z, next.z());
            assertEquals(Math.sqrt(Math.max(0, 7.5 * 7.5 - z * z)), next.x(), 1e-10);
        }
        var d = new Point(30, 0, 40);
        assertEquals(d, SpaceFlightMath.near(BODY, d, ZERO, d, 0));
    }

    @Test
    void captureUsesNearestRelativeVelocityAndNeedsNoFuel() throws Exception {
        var random = new java.util.Random(41);
        for (int i = 0; i < 1000; i++) {
            var offset = new Point(2, 3, 4);
            var v = new Point(random.nextDouble() * 80 - 40,
                    random.nextDouble() * 80 - 40, random.nextDouble() * 80);
            var relative = v.add(offset);
            var radial = SpaceFlightMath.arrival(BODY, v, NOSE, relative, 1536);
            var next = SpaceFlightMath.near(BODY, radial, NOSE,
                    relative.add(radial.sub(v)), 1536);
            assertTrue(next.add(offset).length() <= 7.5 + 1e-10);
            assertEquals(Math.min(relative.z(), 7.5), next.add(offset).z(), 1e-10);
        }
        var source = Sources.text("SpaceServer");
        for (String method : new String[] {"arrival", "near"})
            assertFalse(SpaceBytecode.calls("space/SpaceFlightMath", method).stream()
                    .anyMatch(call -> call.startsWith("ThrusterFuel.") || call.endsWith(".draw")));
        assertTrue(source.contains("guided=assist(f.drift(),nose,Thrusters.forward("));
        assertTrue(source.contains("SpaceSpeedServer.maneuver(lead,f,f.drift(),brake(prior,guided,push,targetVelocity))"));
        var guidance = Sources.text("SpaceSpeedServer");
        assertTrue(guidance.contains("dv->ThrusterFuel.change(body,dv)"));
        assertTrue(guidance.contains("1-HelmServer.power(body,jet.getBlockPos(),state)/15.0"));
        assertFalse(source.contains("VACUUM"));
        assertFalse(source.contains("SHED"));
        assertTrue(source.contains("new org.joml.Vector3d(-v.x()*20,-v.y()*20,-v.z()*20)"));
    }
}
