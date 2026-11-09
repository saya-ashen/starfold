package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.PlanetAtmosphere;

import org.junit.jupiter.api.Test;

class AtmosphereScatteringTest {
    private static final double R = 1304;
    private static final Point EYE = new Point(0, R + 1, 0),
            UP = new Point(0, 1, 0),
            EAST = new Point(1, 0, 0);

    private static double brightness(Point c) {
        return c.x() + c.y() + c.z();
    }

    @Test
    void noonZenithIsBluerThanHorizon() {
        var zenith = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, UP, UP);
        var horizon = AtmosphereScattering.fog(PlanetAtmosphere.EARTH, R, EYE, EAST, UP);
        assertTrue(zenith.z() > zenith.x(), zenith.toString());
        assertTrue(zenith.z() / zenith.x() > horizon.z() / horizon.x(), zenith + " vs " + horizon);
    }

    @Test
    void sunsetRedIsOnSunSide() {
        var near = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, EAST, EAST);
        var far = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, EAST.scale(-1), EAST);
        // Compare red radiance excess, not chromaticity of the nearly dark anti-solar sky.
        assertTrue(near.x() - near.z() > far.x() - far.z(), near + " vs " + far);
        assertTrue(near.x() > near.z(), near.toString());
        assertTrue(brightness(near) > brightness(far), near + " vs " + far);
    }

    @Test
    void vacuumIsBlackAndHasDayStarsAndNoFogOrGrade() {
        assertEquals(
                new Point(0, 0, 0),
                AtmosphereScattering.sky(PlanetAtmosphere.NONE, R, EYE, UP, UP));
        assertEquals(1, AtmosphereScattering.stars(new Point(0, 0, 0)));
        assertEquals(0, AtmosphereScattering.fogStrength(PlanetAtmosphere.NONE, 0));
        assertEquals(new Point(1, 1, 1), AtmosphereScattering.grade(PlanetAtmosphere.NONE, 0));
    }

    @Test
    void skyThinsWithAltitudeAndDensity() {
        var ground = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, UP, UP);
        var high =
                AtmosphereScattering.sky(
                        PlanetAtmosphere.EARTH, R, new Point(0, R + 200, 0), UP, UP);
        var thin =
                AtmosphereScattering.sky(
                        new PlanetAtmosphere(.1, PlanetAtmosphere.EARTH_BLUE, false),
                        R,
                        EYE,
                        UP,
                        UP);
        assertTrue(brightness(high) < brightness(ground));
        assertTrue(brightness(thin) < brightness(ground));
        assertTrue(AtmosphereScattering.stars(high) > AtmosphereScattering.stars(ground));
        assertEquals(
                new Point(0, 0, 0),
                AtmosphereScattering.sky(
                        PlanetAtmosphere.EARTH, R, new Point(0, R + 1024, 0), UP, UP));
    }

    @Test
    void fogIsExactlyTheSkyAtViewAzimuthHorizon() {
        var view = new Point(1, .7, 0);
        assertEquals(
                AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, EAST, UP),
                AtmosphereScattering.fog(PlanetAtmosphere.EARTH, R, EYE, view, UP));
    }

    @Test
    void displayEncodingIsAppliedToMappedLinearLightNotStarLuminance() {
        var linear = new Point(0, .0031308, .18);
        var display = AtmosphereScattering.display(linear);
        assertEquals(0, display.x());
        assertEquals(.040449936, display.y(), 1e-9);
        assertEquals(.4613561295, display.z(), 1e-9);
        assertEquals(new Point(0, .0031308, .18), linear);
        assertEquals(new Point(0, 0, 0), AtmosphereScattering.display(new Point(0, 0, 0)));
        assertEquals(1, AtmosphereScattering.display(new Point(1, 1, 1)).x(), 1e-12);
        var zenith = new Point(.4, .5, .6);
        assertEquals(
                Math.exp(
                        -AtmosphereSettings.STAR_EXTINCTION
                                * (.2126 * .4 + .7152 * .5 + .0722 * .6)),
                AtmosphereScattering.stars(zenith),
                1e-12);
    }

    @Test
    void calibratedHomeHasBrightBlueNoonAndWarmGoldSolarHorizon() {
        // Actual R1-after-glfix-04: p0 size=2048, eye Y=131.62, chart floor=128,
        // saved density=1, tint=0x78A7FF; noon SOLAR elevation=.9852432998418718.
        double radius = 2048 * 2 / Math.PI;
        var eye = new Point(0, radius + 3.62, 0);
        double elevation = .9852432998418718;
        var sun = new Point(Math.sqrt(1 - elevation * elevation), elevation, 0);
        var linear = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, radius, eye, UP, sun);
        var noon = AtmosphereScattering.display(linear);
        assertTrue(linear.y() > .45 && linear.y() < .6, linear.toString());
        assertTrue(noon.z() > noon.y() && noon.y() > noon.x(), noon.toString());
        assertEquals(170, noon.x() * 255, 2);
        assertEquals(191, noon.y() * 255, 2);
        assertEquals(206, noon.z() * 255, 2);
        var nearLinear = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, radius, eye, EAST, EAST);
        var near = AtmosphereScattering.display(nearLinear);
        var far =
                AtmosphereScattering.sky(PlanetAtmosphere.EARTH, radius, eye, EAST.scale(-1), EAST);
        assertTrue(near.x() / near.y() > 1.2, near.toString());
        assertTrue(near.y() / near.z() > 3, near.toString());
        assertEquals(211, near.x() * 255, 2);
        assertEquals(166, near.y() * 255, 2);
        assertEquals(45, near.z() * 255, 2);
        assertTrue(brightness(nearLinear) > 3 * brightness(far));
        assertEquals(
                near,
                AtmosphereScattering.display(
                        AtmosphereScattering.fog(PlanetAtmosphere.EARTH, radius, eye, EAST, EAST)));
    }

    @Test
    void lightweightSamplesStayCloseToAnIndependentConvergedIntegral() {
        double radius = 2048 * 2 / Math.PI;
        double elevation = .9852432998418718;
        var sun = new Point(Math.sqrt(1 - elevation * elevation), elevation, 0);
        for (var scene :
                new Point[][] {
                    {new Point(0, radius + 3.62, 0), UP, sun},
                    {new Point(0, radius + 3.62, 0), EAST, EAST},
                    {new Point(0, radius + 3.62, 0), EAST.scale(-1), EAST},
                    {new Point(0, radius + 160, 0), UP, sun}
                }) {
            var low =
                    AtmosphereScattering.sky(
                            PlanetAtmosphere.EARTH, radius, scene[0], scene[1], scene[2]);
            var reference = referenceSky(radius, scene[0], scene[1], scene[2], 96, 64);
            var half = referenceSky(radius, scene[0], scene[1], scene[2], 48, 32);
            assertPointClose(reference, half, .001);
            assertPointClose(reference, low, .03);
        }
    }

    private static void assertPointClose(Point expected, Point actual, double error) {
        assertEquals(expected.x(), actual.x(), error);
        assertEquals(expected.y(), actual.y(), error);
        assertEquals(expected.z(), actual.z(), error);
    }

    /** Independent high-resolution midpoint reference for the above outward/tangent Earth rays. */
    private static Point referenceSky(
            double radius, Point eye, Point ray, Point sun, int n, int m) {
        double outer = radius + AtmosphereSettings.SCALE_HEIGHT * AtmosphereSettings.SHELL_HEIGHTS;
        double ds = exitDistance(eye, ray, outer) / n;
        double[] sum = new double[3];
        double mu = ray.dot(sun), g = AtmosphereSettings.MIE_G;
        double phaseR = 3 * (1 + mu * mu) / (16 * Math.PI);
        double phaseM = (1 - g * g) / (4 * Math.PI * Math.pow(1 + g * g - 2 * g * mu, 1.5));
        double viewDepth = 0;
        int tint = PlanetAtmosphere.EARTH.color();
        for (int i = 0; i < n; i++) {
            var p = eye.add(ray.scale((i + .5) * ds));
            double local = referenceDensity(p, radius) * ds / AtmosphereSettings.SCALE_HEIGHT;
            double b = p.dot(sun), disc = b * b - p.dot(p) + radius * radius;
            if (disc < 0 || -b + Math.sqrt(disc) <= 1e-5) {
                double step = exitDistance(p, sun, outer) / m, solarDepth = 0;
                for (int j = 0; j < m; j++)
                    solarDepth +=
                            referenceDensity(p.add(sun.scale((j + .5) * step)), radius)
                                    * step
                                    / AtmosphereSettings.SCALE_HEIGHT;
                double depth = viewDepth + local / 2 + solarDepth;
                for (int c = 0; c < 3; c++) {
                    double beta =
                            Math.pow(
                                            ((tint >> (16 - c * 8)) & 255) / 255.0,
                                            AtmosphereSettings.TINT_POWER)
                                    * AtmosphereSettings.RAYLEIGH;
                    sum[c] +=
                            Math.exp(-(beta + AtmosphereSettings.MIE) * depth)
                                    * (beta * phaseR + AtmosphereSettings.MIE * phaseM)
                                    * local;
                }
            }
            viewDepth += local;
        }
        return new Point(
                1 - Math.exp(-AtmosphereSettings.EXPOSURE * sum[0]),
                1 - Math.exp(-AtmosphereSettings.EXPOSURE * sum[1]),
                1 - Math.exp(-AtmosphereSettings.EXPOSURE * sum[2]));
    }

    private static double exitDistance(Point p, Point ray, double radius) {
        double b = p.dot(ray);
        return -b + Math.sqrt(b * b - p.dot(p) + radius * radius);
    }

    private static double referenceDensity(Point p, double radius) {
        return Math.exp(-Math.max(0, p.length() - radius) / AtmosphereSettings.SCALE_HEIGHT);
    }

    @Test
    void rotationalCovarianceAndNightAndLimbStayFinite() {
        var first = AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, EAST, UP);
        var rotated =
                AtmosphereScattering.sky(
                        PlanetAtmosphere.EARTH, R, new Point(R + 1, 0, 0), UP, EAST);
        assertEquals(first.x(), rotated.x(), 1e-12);
        assertEquals(first.z(), rotated.z(), 1e-12);
        assertEquals(
                new Point(0, 0, 0),
                AtmosphereScattering.sky(PlanetAtmosphere.EARTH, R, EYE, UP, UP.scale(-1)));
        var limb =
                AtmosphereScattering.sky(
                        PlanetAtmosphere.EARTH,
                        R,
                        new Point(0, 0, R * 4),
                        new Point(R, 0, -R * 4),
                        EAST);
        assertTrue(brightness(limb) > 0);
        for (double c : new double[] {limb.x(), limb.y(), limb.z()})
            assertTrue(Double.isFinite(c) && c >= 0 && c <= 1);
    }
}
