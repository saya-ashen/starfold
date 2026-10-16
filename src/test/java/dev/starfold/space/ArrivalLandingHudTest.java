package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

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
            var result = SpaceServer.arrival(body, velocity, toward, velocity, height);
            assertEquals(SpaceServer.arrivalLimit(body, height), result.x() * 20, 1e-8);
            var tangent = new Point(0, 10000, 0);
            var near = SpaceServer.near(body, tangent, toward, tangent, height);
            assertEquals(SpaceServer.nearLimit(body, height), near.y() * 20, 1e-8);
        }
    }

    @Test
    void widgetsOnlyUseSnapshotsAndOldChartTextIsGone() throws Exception {
        var widgets = Sources.text("FlightWidgets");
        for (String name :
                new String[] {"HullLandingClient", "WaypointClient", "SpaceClient", "HelmClient"})
            assertFalse(widgets.contains(name), name);
        assertTrue(widgets.contains("s.tier()>=3?-limit:Double.NaN"));
        assertTrue(widgets.contains("if(s.tier()<3||a==null)return"));
        assertTrue(widgets.contains("h<.5"));
        assertTrue(widgets.contains("home.distance()<=16"));
        assertTrue(widgets.contains("disabled(g"));
        assertTrue(widgets.contains("DARK"));
        assertFalse(Sources.text("WaypointClient").contains("renderHelm"));
        assertFalse(Sources.text("HullLandingClient").contains("drawString"));
    }
}
