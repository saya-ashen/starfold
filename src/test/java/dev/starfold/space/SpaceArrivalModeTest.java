package dev.starfold.space;

import static dev.starfold.sky.SpaceTest.point;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class SpaceArrivalModeTest {
    @Test
    void tangentAddsNothingAndRadialKeepsEitherSign() {
        var toward = new Point(0, -100, 0);
        var drift = new Point(2, -30, 4);
        for (double sign : new double[] {-1, 1}) {
            point(
                    GroundEphemeris.ZERO,
                    SpaceFlightMath.arrivalPush(new Point(3 * sign, 0, 4 * sign), toward, true));
            point(
                    new Point(0, 2 * sign, 0),
                    SpaceFlightMath.arrivalPush(new Point(3, 2 * sign, 4), toward, true));
            point(
                    drift,
                    SpaceFlightMath.gain(
                            drift,
                            SpaceFlightMath.arrivalPush(new Point(3, 0, 4), toward, true)));
        }
    }

    @Test
    void projectionWorksInEveryPlanetDirection() {
        var random = new java.util.Random(53);
        for (int i = 0; i < 1000; i++) {
            var toward =
                    new Point(
                            random.nextDouble() - .5,
                            random.nextDouble() - .5,
                            random.nextDouble() - .5);
            var n = toward.scale(1 / toward.length());
            var push =
                    new Point(
                            random.nextDouble() - .5,
                            random.nextDouble() - .5,
                            random.nextDouble() - .5);
            var kept = SpaceFlightMath.arrivalPush(push, toward, true);
            assertEquals(push.dot(n), kept.dot(n), 1e-12);
            point(GroundEphemeris.ZERO, kept.sub(n.scale(kept.dot(n))));
            assertTrue(kept.length() <= push.length() + 1e-12);
        }
    }

    @Test
    void outsideArrivalIsBitForBitIncludingLockedCruise() {
        var random = new java.util.Random(53);
        for (int i = 0; i < 1000; i++) {
            var drift =
                    new Point(
                            random.nextDouble() * 40,
                            random.nextDouble() * 40,
                            random.nextDouble() * 40);
            var impulse =
                    new Point(
                            random.nextDouble() - .5,
                            random.nextDouble() - .5,
                            random.nextDouble() - .5);
            var toward = new Point(2, -3, 4);
            assertSame(impulse, SpaceFlightMath.arrivalPush(impulse, toward, false));
            for (boolean boost : new boolean[] {false, true})
                for (var target : new Point[] {GroundEphemeris.ZERO, new Point(1, 2, 3)}) {
                    var old = SpaceFlightMath.gain(drift, impulse);
                    var next =
                            SpaceFlightMath.gain(
                                    drift,
                                    SpaceFlightMath.arrivalPush(impulse, toward, false));
                    assertEquals(old, next);
                    assertEquals(
                            SpaceFlightMath.brake(drift, old, impulse, target),
                            SpaceFlightMath.brake(drift, next,
                                    SpaceFlightMath.arrivalPush(impulse, toward, false), target));
                }
        }
    }

    @Test
    void tangentialPassAtFiveThousandIsNotArrival() {
        assertFalse(SpaceFlightMath.align(dev.starfold.chart.GroundSolar.defaults().body(0), 5000, 0));
        var v = new Point(40, 0, 0);
        assertSame(v, SpaceFlightMath.arrivalPush(v, new Point(0, -1, 0), false));
        assertEquals(
                v,
                SpaceFlightMath.near(
                        dev.starfold.chart.GroundSolar.defaults().body(0),
                        v,
                        new Point(0, -1, 0),
                        v,
                        5000));
    }

    @Test
    void integrationFiltersFoldNotHullRelabellingAndSkipsBothAssists() throws Exception {
        var s = Sources.text("SpaceServer");
        assertTrue(s.contains("boolean arriving=value.arrivalState.latched()"));
        assertTrue(
                s.contains(
                        "gain(f.drift(),blendPush(f.universeVector(v),towardEntry,blend))"));
        assertTrue(s.contains("push=blendPush(push,towardEntry,blend)"));
        assertTrue(s.contains("new org.joml.Vector3d(-v.x()*20,-v.y()*20,-v.z()*20)"));
        assertTrue(s.contains("if(!arriving)"));
        assertTrue(s.indexOf("if(!arriving)") < s.indexOf("assist(f.drift(),nose"));
        assertTrue(
                s.indexOf("drift=cruise(prior")
                        < s.indexOf("var limited=govern(model.body(nearest),drift"));
        var hud = Sources.text("SpaceNavigationClient");
        assertTrue(hud.contains("SpaceClient.arriving()?FlightMode.ARRIVAL"));
        var widgets = Sources.text("FlightWidgets");
        assertTrue(widgets.contains("if(arrival){if(s.blend()<1)"));
        assertTrue(widgets.contains("g.setColor(1,1,1,(float)s.blend())"));
    }
}
