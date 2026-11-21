package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class ArrivalLandingHudTest {
    @Test
    void roundedClearanceNeverPrintsNegativeZero() {
        for (double h : new double[] {-.49, -.001, -0.0, 0, .49})
            assertEquals("0", HudLayout.number(h));
        assertEquals("-1", HudLayout.number(-1));
        assertEquals("—", HudLayout.number(Double.NaN));
    }

    @Test
    void displayedArrivalCapsAreTheActualServerCaps() {
        var body = dev.starfold.chart.GroundSolar.defaults().body(0);
        for (double height : new double[] {0, 1536, 2200, 5000, 10000}) {
            var toward = new Point(1, 0, 0);
            var velocity = new Point(10000, 0, 0);
            var result = SpaceFlightMath.arrival(body, velocity, toward, velocity, height);
            assertEquals(SpaceFlightMath.arrivalLimit(body, height), result.x() * 20, 1e-8);
            var tangent = new Point(0, 10000, 0);
            var near = SpaceFlightMath.near(body, tangent, toward, tangent, height);
            assertEquals(SpaceFlightMath.nearLimit(body, height), near.y() * 20, 1e-8);
        }
    }
}
