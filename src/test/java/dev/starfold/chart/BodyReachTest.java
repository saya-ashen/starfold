package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.HullWeight;
import dev.starfold.sky.Space;

import org.junit.jupiter.api.Test;

class BodyReachTest {
    @Test
    void sixDefaultHeightsKeepTheirExactValuesAndDisplayHeightStaysGlobal() {
        var body = GroundSolar.defaults().body(0);
        assertEquals(2048, body.reach());
        assertEquals(2048, body.hullOut());
        assertEquals(1536, body.hullIn());
        assertEquals(4096, body.flightExit());
        assertEquals(3072, body.flightEnter());
        assertEquals(3072, body.weightNone());
        assertEquals(1024, body.weightFull());
        assertEquals(1024, GroundOrbit.END);
    }

    @Test
    void smallBodyHandoverIsStrictAndWeightFadesInItsOwnBand() {
        var body =
                new GroundEphemeris.Body(
                        new GroundEphemeris.Fixed(GroundEphemeris.ZERO), 0, false, 0, 512);
        assertEquals(512, body.hullOut());
        assertEquals(384, body.hullIn());
        assertEquals(1024, body.flightExit());
        assertEquals(768, body.flightEnter());
        assertEquals(768, body.weightNone());
        assertEquals(256, body.weightFull());
        assertFalse(Space.leaves(body, 512));
        assertTrue(Space.leaves(body, Math.nextUp(512.0)));
        assertFalse(Space.enters(body, 384));
        assertTrue(Space.enters(body, Math.nextDown(384.0)));
        assertEquals(1, HullWeight.at(body, 256));
        assertEquals(.5, HullWeight.at(body, 512));
        assertEquals(0, HullWeight.at(body, 768));
        assertEquals(1024, GroundOrbit.END);
    }

    @Test
    void reachIsValidatedBySurfaceMembershipAndRetainedWhenSpinChanges() {
        var original = GroundSolar.defaults();
        var body = original.body(0);
        for (double reach : new double[] {0, 511, 2049, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GroundEphemeris.Body(body.motion(), 0, false, 0, reach));
        var light = new GroundEphemeris.Fixed(new Point(0, 20000, 0));
        assertEquals(0, new GroundEphemeris.Body(light, 64, true, -1).reach());
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundEphemeris.Body(light, 64, true, -1, 512));
        var bodies = new java.util.ArrayList<>(original.bodies());
        bodies.set(
                original.index(0),
                new GroundEphemeris.Body(body.motion(), body.radius(), body.light(), 0, 512));
        var model = new GroundEphemeris.Model(bodies);
        assertEquals(512, GroundEphemeris.spinning(model, true, 123).body(0).reach());
        assertEquals(512, GroundEphemeris.spinning(model, false, 123).body(0).reach());
    }
}
