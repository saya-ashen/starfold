package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.PlanetAtmosphere;

import org.junit.jupiter.api.Test;

class AtmosphereRimTest {
    @Test
    void visualShellIsThinEvenOnSmallPlanetsWithoutChangingOpticalShell() {
        for (double radius : new double[] {256 * 2 / Math.PI, 652, 1304, 2608}) {
            double height = AtmosphereRim.height(radius);
            assertTrue(height > 0 && height <= 36);
            assertTrue(height / radius <= .035 + 1e-12);
            assertTrue(
                    height
                            < AtmosphereSettings.SCALE_HEIGHT
                                    * AtmosphereSettings.SHELL_HEIGHTS
                                    / 7);
        }
        assertEquals(48, AtmosphereSettings.SCALE_HEIGHT);
        assertEquals(6, AtmosphereSettings.SHELL_HEIGHTS);
        assertEquals(1.05, AtmosphereSettings.RAYLEIGH);
        assertEquals(20, AtmosphereSettings.EXPOSURE);
    }

    @Test
    void everyBandIsTangentToItsOwnRadiusAtNearAndFarDistances() {
        for (double radius : new double[] {163, 652, 1304})
            for (double distance : new double[] {radius + 49, radius + 1024, radius * 4.8}) {
                double lastAngle = -1;
                for (int j = 0; j <= AtmosphereSettings.RIM_BANDS; j++) {
                    double height = AtmosphereRim.height(radius) * j / AtmosphereSettings.RIM_BANDS;
                    var section = AtmosphereRim.section(radius, distance, height);
                    var p = new Point(section.radial(), 0, section.axial());
                    var ray = p.sub(new Point(0, 0, distance));
                    assertEquals(radius + height, p.length(), 1e-9);
                    assertEquals(0, p.dot(ray), 1e-8, "tangent ray must not penetrate the shell");
                    double angle = Math.atan2(section.radial(), distance - section.axial());
                    assertEquals(Math.asin((radius + height) / distance), angle, 1e-12);
                    assertTrue(angle > lastAngle);
                    lastAngle = angle;
                }
            }
    }

    @Test
    void coverageHasZeroValueAndSlopeAtBothEdgesAndABoundedPeak() {
        assertEquals(0, AtmosphereRim.opacity(-1));
        assertEquals(0, AtmosphereRim.opacity(0));
        assertEquals(0, AtmosphereRim.opacity(1));
        assertEquals(0, AtmosphereRim.opacity(2));
        assertEquals(.28, AtmosphereRim.opacity(.5), 1e-12);
        assertTrue(AtmosphereRim.opacity(1e-6) / 1e-6 < 1e-5);
        assertTrue(AtmosphereRim.opacity(1 - 1e-6) / 1e-6 < 1e-5);
        for (int j = 0; j <= 100; j++) {
            double x = j / 100.0, alpha = AtmosphereRim.opacity(x);
            assertTrue(alpha >= 0 && alpha <= .28);
            assertEquals(alpha, AtmosphereRim.opacity(1 - x), 1e-12);
        }
    }

    @Test
    void ordinaryAlphaCoverageCannotAddDisplayChannelsPastWhite() {
        double alpha = AtmosphereRim.opacity(.5);
        for (double source : new double[] {0, .2, .8, 1})
            for (double background : new double[] {0, .2, .8, 1}) {
                double result = source * alpha + background * (1 - alpha);
                assertTrue(result >= Math.min(source, background));
                assertTrue(result <= Math.max(source, background));
                if (background < 1) assertTrue(result < 1);
            }
        // Old display-space ONE/ONE addition clipped this already bright scattered background.
        assertTrue(.8 + AtmosphereScattering.display(new Point(.65, .65, .65)).x() > 1);
        assertTrue(.8 * (1 - alpha) + .9 * alpha < 1);
    }

    @Test
    void limbStillUsesAtmosphereTintDensityAndSolarIllumination() {
        double radius = 2048 * 2 / Math.PI, distance = radius + 1024;
        var eye = new Point(0, 0, distance);
        var section = AtmosphereRim.section(radius, distance, AtmosphereRim.height(radius) / 2);
        var p = new Point(section.radial(), 0, section.axial());
        var ray = p.sub(eye);
        var light = new Point(1, 0, 0);
        var day = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, radius, eye, ray, light);
        var night =
                AtmosphereScattering.sky(PlanetAtmosphere.EARTH, radius, eye, ray, light.scale(-1));
        assertTrue(day.length() > night.length());
        var thin =
                AtmosphereScattering.sky(
                        new PlanetAtmosphere(.01, 0x78a7ff, false), radius, eye, ray, light);
        assertTrue(thin.length() < day.length());
        assertTrue(thin.z() > thin.x(), thin.toString());
        var red =
                AtmosphereScattering.sky(
                        new PlanetAtmosphere(.01, 0xff7040, false), radius, eye, ray, light);
        assertTrue(red.x() > red.z());
        assertEquals(
                new Point(0, 0, 0),
                AtmosphereScattering.sky(PlanetAtmosphere.NONE, radius, eye, ray, light));
    }
}
