package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.ArrivalState;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.junit.jupiter.api.Test;

class SpaceSpeedTest {
    @Test
    void onlyTechnicalLimitRemainsInEveryDirection() {
        var random = new java.util.Random(41);
        assertEquals(20000, SpaceSpeed.DEEP_MAX);
        for (int i = 0; i < 1000; i++) {
            var velocity = new Point(random.nextDouble() * 4000 - 2000,
                    random.nextDouble() * 4000 - 2000, random.nextDouble() * 4000 - 2000);
            var result = SpaceSpeed.limit(velocity);
            assertEquals(Math.min(velocity.length(), 1000), result.length(), 1e-9);
            if (velocity.length() <= 1000) assertSame(velocity, result);
            assertEquals(0, result.x() * velocity.y() - result.y() * velocity.x(), 1e-8);
        }
    }

    @Test
    void nativeDampingIsUndoneForLeadAndFollowerWithoutBoostingThrust() {
        for (double step : new double[] {.05, .0125})
            for (double drag : new double[] {0, .09, .7}) {
                var velocity = new Point(3, 4, 10);
                var push = new Point(.01, 0, .02);
                var wanted = velocity;
                for (int i = 0; i < 1000; i++) {
                    wanted = wanted.add(push);
                    velocity = SpaceSpeed.undamped(velocity.add(push).scale(1 / (1 + drag * step)),
                            drag * step);
                }
                assertEquals(wanted.x(), velocity.x(), 1e-10);
                assertEquals(wanted.z(), velocity.z(), 1e-10);
            }
    }

    @Test
    void stopAndHeadingChangesAreFuelLimitedAndCoastingDrawsNothing() {
        var velocity = new Point(0, 0, 10);
        double[] spent = {0};
        var next = SpaceSpeed.maneuver(velocity, new Point(0, 0, 0), .1, dv -> {
            spent[0] += dv;
            return dv;
        });
        assertEquals(2, spent[0]);
        assertEquals(198, next.length() * 20, 1e-10);
        assertEquals(velocity, SpaceSpeed.maneuver(velocity, new Point(10, 0, 0), 1, dv -> 0));
        assertSame(velocity, SpaceSpeed.maneuver(velocity, velocity, 1,
                dv -> { fail("coasting must not draw fuel"); return 0; }));
        assertEquals(new Point(0, 0, 0), SpaceSpeed.maneuver(new Point(0, 0, .01),
                new Point(0, 0, 0), 1, dv -> dv));
    }

    @Test
    void arrivalNoticeBlendAndGovernorReachEntryWithoutHighSpeedOvershoot() {
        var body = dev.starfold.chart.GroundSolar.defaults().body(0);
        var nose = new Point(0, 0, 1);
        for (double initial : new double[] {800, 1200, 2400, 20000}) {
            double h = 1536 + initial * SpaceFlightMath.ALIGN_TIME - initial / 20;
            var state = ArrivalState.cruise(0, "ship", 0);
            var v = nose.scale(initial / 20);
            int ticks = 0;
            while (h > 1536 && ticks++ < 2000) {
                state = state.step(0, "ship", h, v.length() * 20,
                        SpaceFlightMath.align(body, h, v.length() * 20, true),
                        h < 1836, false, false, true);
                var governed = SpaceFlightMath.govern(body, v, nose, v, h, true);
                v = v.add(governed.sub(v).scale(state.blend()));
                h -= v.z();
            }
            assertTrue(ticks < 2000);
            assertEquals(1, state.blend());
            assertTrue(v.length() * 20 <= 150 / (1 - 1.0 / 120) + 1e-8);
            assertTrue(h > 0);
        }
    }

    @Test
    void regionHasMoreThanOneMaximumSpeedTickOfMargin() {
        double step = SpaceSpeed.DEEP_MAX / 20;
        assertEquals(1000, step);
        assertTrue(Space.ANCHOR_REACH + step < Space.REACH);
        assertTrue(Math.abs(Space.X) + Space.ANCHOR_REACH + step < 32768);
        assertTrue(Space.Y - Space.ANCHOR_REACH - step > Space.SABLE_MIN_Y);
        assertTrue(Space.Y + Space.ANCHOR_REACH + step < Space.SABLE_MAX_Y);
    }

    @Test
    void hudShowsDeltaVNotCruiseCaps() throws Exception {
        assertFalse(Sources.text("ShipHost").contains("SpeedCaps"));
        assertFalse(Sources.text("HelmPackets").contains("cruiseCap"));
        var widget = Sources.text("FlightWidgets");
        assertTrue(widget.contains("tr(\"delta_v\",number(s.deltaV()))"));
        assertTrue(widget.contains("deltaV>=0&&deltaV<speed"));
        assertTrue(Sources.text("SpaceNavigationClient").contains("FlightWidgets.stopping(g,velocity.length()*20"));
    }
}
