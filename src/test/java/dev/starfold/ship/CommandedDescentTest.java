package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CommandedDescentTest {
    @Test
    void hoverBalancesWeightAndCriticallyDampsAltitudeWithoutOvershoot() {
        double y = 10, v = 0, previous = y;
        for (int i = 0; i < 1000; i++) {
            var o = LiftControl.step(100, 8.25, 1500, 600, y, 12, v, 100, 0, false, true);
            v += (o.lift() * 15 - 8.25) * .01;
            y += v * .01;
            assertTrue(y >= previous - 1e-10);
            assertTrue(y <= 12 + 1e-6);
            previous = y;
        }
        assertEquals(12, y, 1e-5);
        assertEquals(
                .55,
                LiftControl.step(100, 8.25, 1500, 600, 12, 12, 0, 100, 0, false, false).lift(),
                1e-12);
    }

    @Test
    void gravityDescendsWithoutDownNozzlesAndLiftBrakes() {
        var falling =
                LiftControl.step(
                        100, 8.25, 1500, 600, 1300, 1300, 0, 1300, HelmCommand.DOWN, false, true);
        assertEquals(0, falling.lift());
        assertEquals(0, falling.down());
        var braking =
                LiftControl.step(
                        100, 8.25, 1500, 600, 50, 50, -45, 50, HelmCommand.DOWN, false, true);
        assertEquals(1, braking.lift());
        assertEquals(0, braking.down());
        assertTrue(LiftControl.stoppingDistance(45, 2) > LiftControl.stoppingDistance(45, 7));
        assertEquals(Double.POSITIVE_INFINITY, LiftControl.stoppingDistance(45, 0));
    }

    @Test
    void weakHullCannotHoverAndParkedHullIsSilent() {
        var weak = LiftControl.step(100, 8.25, 500, 600, 100, 100, 0, 100, 0, false, true);
        assertTrue(weak.insufficient());
        assertEquals(1, weak.lift());
        assertTrue(weak.lift() * 5 - 8.25 < 0);
        for (boolean pilot : new boolean[] {false, true})
            assertEquals(
                    0, LiftControl.step(100, 8.25, 1500, 600, 0, 0, 0, 0, 0, false, pilot).lift());
        assertEquals(
                1,
                LiftControl.step(100, 8.25, 500, 600, 0, 0, 0, 0, HelmCommand.UP, false, true)
                        .lift());
    }

    @Test
    void downJetsRequireBoostPilotAndHeightOutsideBrakingBand() {
        for (boolean pilot : new boolean[] {false, true})
            for (boolean boost : new boolean[] {false, true})
                for (double h : new double[] {100, 1300}) {
                    var o =
                            LiftControl.step(
                                    100,
                                    8.25,
                                    1500,
                                    600,
                                    h,
                                    h,
                                    0,
                                    h,
                                    HelmCommand.DOWN,
                                    boost,
                                    pilot);
                    assertEquals(pilot && boost && h > 200, o.down() > 0);
                }
    }

    @Test
    void thirteenHundredBlockPlainDescentIsBoundedAndLandsGently() {
        double h = 1300, v = 0, t = 0;
        while (h > .15 && t < 60) {
            double gravity =
                    8.25 * HullWeight.at(dev.starfold.chart.GroundSolar.defaults().body(0), h);
            var o =
                    LiftControl.step(
                            2228, gravity, 33500, 13400, h, h, v, h, HelmCommand.DOWN, false, true);
            v += (o.lift() * 33500 / 2228 - gravity) * .01;
            h += v * .01;
            t += .01;
            assertTrue(v >= -LiftControl.DESCENT - .01);
        }
        System.out.println("GRAVITY pure descent seconds=" + t + " touchdown=" + v);
        assertTrue(t >= 25 && t <= 35, "seconds=" + t);
        assertTrue(v > -1.5, "touchdown=" + v);
    }

    @Test
    void releasedClimbCapturesReachableAltitudeWithoutBobbing() {
        double y = 100,
                v = 30,
                g = 8.25,
                lift = 15,
                target = LiftControl.capture(y, v, g, lift - g);
        assertTrue(target >= 100 + 30 * 30 / (2 * g));
        for (int i = 0; i < 20000; i++) {
            var o = LiftControl.step(100, g, 1500, 600, y, target, v, 100, 0, false, true);
            v += (o.lift() * lift - g) * .001;
            y += v * .001;
            assertTrue(y <= target + .02);
        }
        assertEquals(target, y, .02);
        assertEquals(0, v, .02);
    }

    @Test
    void noAvailableMarginNeverClaimsABrakingForce() {
        for (double maxLift : new double[] {0, 100, 824})
            for (double velocity : new double[] {-110, -45, 0, 40}) {
                var o =
                        LiftControl.step(
                                100,
                                8.25,
                                maxLift,
                                600,
                                50,
                                50,
                                velocity,
                                50,
                                HelmCommand.DOWN,
                                false,
                                true);
                assertTrue(o.insufficient());
                assertTrue(o.lift() >= 0 && o.lift() <= 1);
                assertEquals(0, o.down());
                assertEquals(Double.POSITIVE_INFINITY, o.stoppingDistance());
            }
    }

    @Test
    void onlyHorizontalDriftIsDamped() {
        var v = SableFrames.drift(new org.joml.Vector3d(7, -45, -8), 1, 4);
        assertEquals(-1.4, v.x, 1e-12);
        assertEquals(0, v.y, 1e-12);
        assertEquals(1.6, v.z, 1e-12);
    }
}
