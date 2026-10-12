package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.junit.jupiter.api.Test;

class SpacePassbyTest {
    @Test
    void closestApproachHeadOnGrazeWideAndReceding() {
        double radius = 4096;
        for (double speed : new double[] {.001, 1, 40, 4000})
            for (double miss : new double[] {-radius, Space.IN + 200, 5000}) {
                var p = new Point(-20000, radius + miss, 0);
                var v = new Point(speed, 0, 0);
                assertEquals(miss, SpaceServer.missHeight(p, v, radius), 1e-8);
                assertEquals(
                        Double.POSITIVE_INFINITY, SpaceServer.missHeight(p, v.scale(-1), radius));
            }
        assertEquals(
                Double.POSITIVE_INFINITY,
                SpaceServer.missHeight(new Point(1, 2, 3), GroundEphemeris.ZERO, radius));
    }

    @Test
    void hysteresisUsesStrictEntryInclusiveExitAndRecessionClears() {
        double in = Space.IN + 500, out = Space.IN + 1000;
        assertFalse(SpaceServer.inbound(false, 1, in));
        assertTrue(SpaceServer.inbound(false, 1, Math.nextDown(in)));
        assertTrue(SpaceServer.inbound(true, 1, in));
        assertTrue(SpaceServer.inbound(true, 1, out));
        assertFalse(SpaceServer.inbound(true, 1, Math.nextUp(out)));
        for (double closing : new double[] {0, -1})
            assertFalse(SpaceServer.inbound(true, closing, -4000));
    }

    @Test
    void widePassNeverAlignsOrGovernsAtAnySpeedAboveFloor() {
        for (double speed : new double[] {.001, 1, 40, 4000})
            for (double x = -20000; x <= 20000; x += 100) {
                var p = new Point(x, 9096, 0);
                var v = new Point(speed, 0, 0);
                double h = p.length() - 4096,
                        closing = SpaceNavigation.closing(p.scale(-1), v, GroundEphemeris.ZERO);
                boolean inbound =
                        SpaceServer.inbound(false, closing, SpaceServer.missHeight(p, v, 4096));
                assertFalse(inbound);
                assertFalse(SpaceServer.align(h, closing, inbound));
                assertFalse(SpaceServer.governed(h, inbound));
                assertSame(v, SpaceServer.govern(v, p.scale(-1), v, h, inbound));
            }
    }

    @Test
    void headOnRetainsOldArithmeticBitForBit() {
        for (double h = Space.IN; h < 20000; h += 17)
            for (double speed : new double[] {1, 150, 300, 800}) {
                var p = new Point(0, 4096 + h, 0);
                var v = new Point(0, -speed / 20, 0);
                var toward = p.scale(-1);
                boolean inbound =
                        SpaceServer.inbound(false, speed, SpaceServer.missHeight(p, v, 4096));
                assertTrue(inbound);
                assertEquals(SpaceServer.align(h, speed), SpaceServer.align(h, speed, inbound));
                var radial = SpaceServer.arrival(v, toward, v, h);
                assertEquals(
                        SpaceServer.near(radial, toward, radial, h),
                        SpaceServer.govern(v, toward, v, h, inbound));
            }
    }

    @Test
    void floorAndLateSwingAreImmediate() {
        assertFalse(SpaceServer.governed(Space.IN + 300, false));
        assertTrue(SpaceServer.governed(Math.nextDown(Space.IN + 300), false));
        assertTrue(SpaceServer.align(Math.nextDown(Space.IN + 300), -1, false));
        var v = new Point(40, 0, 0);
        assertEquals(
                150 + 500.0 / 3,
                SpaceServer.govern(v, new Point(0, -1, 0), v, Space.IN + 500, true).length() * 20,
                1e-10);
        assertEquals(
                150 + 500.0 / 6,
                SpaceServer.govern(
                                        new Point(0, -40, 0),
                                        new Point(0, -1, 0),
                                        new Point(0, -40, 0),
                                        Space.IN + 500,
                                        true)
                                .length()
                        * 20,
                1e-10);
    }

    @Test
    void lockDoesNotEnterArrivalGeometryAndClientsUseServerFlag() throws Exception {
        var s = Sources.text("SpaceServer");
        var entry =
                s.substring(s.indexOf("var entry=value.frame"), s.indexOf("if(v.length()>1e-9)"));
        assertTrue(entry.contains("Space.nearest(model,position,tick)"));
        assertFalse(entry.contains("targetVelocity"));
        assertTrue(entry.contains("value.inboundPlanet==destination"));
        assertFalse(
                s.substring(s.indexOf("@Override public CompoundTag save")).contains("inbound"));
        for (String file : new String[] {"SpaceAttitudeClient.java", "SpaceNavigationClient.java"})
            assertTrue(
                    Sources.text(file.substring(0, file.length() - 5))
                            .contains("SpaceClient.arriving()"));
        assertTrue(Sources.text("SpaceClient").contains("arriving=packet.turning()"));
    }
}
