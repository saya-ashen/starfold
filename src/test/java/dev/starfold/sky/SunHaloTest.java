package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class SunHaloTest {
    private final Point center = new Point(1000, -2000, 3000);
    private final Point eye = center.add(new Point(0, 1500, 0));

    @Test
    void sameAirGivesEachSunItsOwnWidthAndWarmth() {
        var high = SunHalo.forSun(1, center, eye, eye.add(new Point(0, 17800, 0)));
        var low = SunHalo.forSun(1, center, eye, eye.add(new Point(137900, 0, 0)));
        assertEquals(1.66, high.width(), 1e-12);
        assertEquals(3.16, low.width(), 1e-12);
        assertEquals(new Point(1, 1, 1), high.color());
        assertEquals(.65, low.color().y(), 1e-12);
        assertEquals(.30, low.color().z(), 1e-12);
        assertEquals(high.strength(), low.strength());
    }

    @Test
    void selectedSunPreservesOriginalElevationAndHaloMath() {
        for (double air : new double[] {0, .25, 1})
            for (var delta :
                    new Point[] {
                        new Point(0, 17800, 0), new Point(17800, 0, 0),
                        new Point(10000, -4000, 1000), new Point(10000, 4000, 1000),
                        new Point(0, 0, 0)
                    }) {
                // GroundClient.elevation, including its coincident-sun fallback.
                var up = PlanetSpace.unit(eye.sub(center));
                double elevation =
                        up.dot(
                                delta.length() < 1e-9
                                        ? GroundEphemeris.Y
                                        : delta.scale(1 / delta.length()));
                double low = 1 - Math.clamp(elevation, 0, 1);
                var actual = SunHalo.forSun(air, center, eye, eye.add(delta));
                assertEquals(.16 + air * (1.5 + low * 1.5), actual.width(), 1e-12);
                assertEquals(.24 * (1 + air * .45), actual.strength(), 1e-12);
                assertEquals(1, actual.color().x());
                assertEquals(1 - .35 * air * low, actual.color().y(), 1e-12);
                assertEquals(1 - .70 * air * low, actual.color().z(), 1e-12);
            }
    }

    @Test
    void vacuumIsExplicitAndIndependentOfSunElevation() {
        var vacuum = SunHalo.vacuum();
        assertEquals(.16, vacuum.width());
        assertEquals(.24, vacuum.strength());
        assertEquals(new Point(1, 1, 1), vacuum.color());
        for (var delta :
                new Point[] {
                    new Point(0, 17800, 0), new Point(17800, 0, 0), new Point(0, -17800, 0)
                }) assertEquals(vacuum, SunHalo.forSun(0, center, eye, eye.add(delta)));
    }

    @Test
    void universeRadialUpMatchesBodyLocalProjectionUnderRotationAndTranslation() {
        var home =
                new GroundEphemeris.Frame(
                        center,
                        new Quaterniond().rotationXYZ(.2, .7, -.4),
                        GroundEphemeris.ZERO,
                        GroundEphemeris.ZERO);
        var bodyEye = new Point(400, 1200, 800);
        var bodySun = new Point(17800, 4000, -2000);
        var local = SunHalo.forSun(.8, GroundEphemeris.ZERO, bodyEye, bodySun);
        var universe = SunHalo.forSun(.8, home.center(), home.world(bodyEye), home.world(bodySun));
        assertEquals(local.width(), universe.width(), 1e-12);
        assertEquals(local.strength(), universe.strength(), 1e-12);
        assertEquals(local.color().y(), universe.color().y(), 1e-12);
        assertEquals(local.color().z(), universe.color().z(), 1e-12);
        // The renderer projects this same universe delta via home.inverseVector.
        var projected = home.inverseVector(home.world(bodySun).sub(home.world(bodyEye)));
        var expected = bodySun.sub(bodyEye);
        assertEquals(expected.x(), projected.x(), 1e-9);
        assertEquals(expected.y(), projected.y(), 1e-9);
        assertEquals(expected.z(), projected.z(), 1e-9);
    }
}
