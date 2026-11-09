package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class SpacePassbyTest {
    private static final GroundEphemeris.Body BODY =
            dev.starfold.chart.GroundSolar.defaults().body(0);

    @Test
    void closestApproachHeadOnGrazeWideAndReceding() {
        double radius = 4096;
        for (double speed : new double[] {.001, 1, 40, 4000})
            for (double miss : new double[] {-radius, 1536 + 200, 5000}) {
                var p = new Point(-20000, radius + miss, 0);
                var v = new Point(speed, 0, 0);
                assertEquals(miss, SpaceFlightMath.missHeight(p, v, radius), 1e-8);
                assertEquals(
                        Double.POSITIVE_INFINITY, SpaceFlightMath.missHeight(p, v.scale(-1), radius));
            }
        assertEquals(
                Double.POSITIVE_INFINITY,
                SpaceFlightMath.missHeight(new Point(1, 2, 3), GroundEphemeris.ZERO, radius));
    }

    @Test
    void hysteresisUsesStrictEntryInclusiveExitAndRecessionClears() {
        double in = 1536 + 500, out = 1536 + 1000;
        assertFalse(SpaceFlightMath.inbound(BODY, false, 1, in));
        assertTrue(SpaceFlightMath.inbound(BODY, false, 1, Math.nextDown(in)));
        assertTrue(SpaceFlightMath.inbound(BODY, true, 1, in));
        assertTrue(SpaceFlightMath.inbound(BODY, true, 1, out));
        assertFalse(SpaceFlightMath.inbound(BODY, true, 1, Math.nextUp(out)));
        for (double closing : new double[] {0, -1})
            assertFalse(SpaceFlightMath.inbound(BODY, true, closing, -4000));
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
                        SpaceFlightMath.inbound(
                                BODY, false, closing, SpaceFlightMath.missHeight(p, v, 4096));
                assertFalse(inbound);
                assertFalse(SpaceFlightMath.align(BODY, h, closing, inbound));
                assertFalse(SpaceFlightMath.governed(BODY, h, inbound));
                assertSame(v, SpaceFlightMath.govern(BODY, v, p.scale(-1), v, h, inbound));
            }
    }

    @Test
    void headOnRetainsOldArithmeticBitForBit() {
        for (double h = 1536; h < 20000; h += 17)
            for (double speed : new double[] {1, 150, 300, 800}) {
                var p = new Point(0, 4096 + h, 0);
                var v = new Point(0, -speed / 20, 0);
                var toward = p.scale(-1);
                boolean inbound =
                        SpaceFlightMath.inbound(BODY, false, speed, SpaceFlightMath.missHeight(p, v, 4096));
                assertTrue(inbound);
                assertEquals(
                        SpaceFlightMath.align(BODY, h, speed),
                        SpaceFlightMath.align(BODY, h, speed, inbound));
                var radial = SpaceFlightMath.arrival(BODY, v, toward, v, h);
                assertEquals(
                        SpaceFlightMath.near(BODY, radial, toward, radial, h),
                        SpaceFlightMath.govern(BODY, v, toward, v, h, inbound));
            }
    }

    @Test
    void floorAndLateSwingAreImmediate() {
        assertFalse(SpaceFlightMath.governed(BODY, 1536 + 300, false));
        assertTrue(SpaceFlightMath.governed(BODY, Math.nextDown(1536 + 300), false));
        assertTrue(SpaceFlightMath.align(BODY, Math.nextDown(1536 + 300), -1, false));
        var v = new Point(40, 0, 0);
        assertEquals(
                150 + 500.0 / 3,
                SpaceFlightMath.govern(BODY, v, new Point(0, -1, 0), v, 1536 + 500, true).length() * 20,
                1e-10);
        assertEquals(
                150 + 500.0 / 6,
                SpaceFlightMath.govern(
                                        BODY,
                                        new Point(0, -40, 0),
                                        new Point(0, -1, 0),
                                        new Point(0, -40, 0),
                                        1536 + 500,
                                        true)
                                .length()
                        * 20,
                1e-10);
    }

    @Test
    void lockDoesNotEnterArrivalGeometryAndClientsUseServerFlag() throws Exception {
        var advance = SpaceBytecode.calls("space/SpaceServer", "advanceLead");
        SpaceBytecode.before(advance, "Space.nearest", "ArrivalState.step");
        SpaceBytecode.before(advance, "ArrivalState.step", "SpaceServer.targetVelocity");
        var save = new SpaceServer(dev.starfold.sky.Space.Frame.IDENTITY).save(new net.minecraft.nbt.CompoundTag(), null);
        assertFalse(save.contains("inbound"));
        assertFalse(save.contains("targets"));
        for (String client : new String[] {"SpaceAttitudeClient", "SpaceNavigationClient"})
            assertTrue(Sources.text(client).contains("SpaceClient.arriving()"));
        assertTrue(Sources.text("SpaceClient").contains("arriving=packet.turning()"));
    }
}
