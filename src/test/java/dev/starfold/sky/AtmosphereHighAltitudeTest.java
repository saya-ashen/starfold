package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.PlanetAtmosphere;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class AtmosphereHighAltitudeTest {
    @Test
    void originalGroundAndInsideShellSkyAreExactlyPreserved() {
        double radius = 2048 * 2 / Math.PI;
        for (double altitude : new double[] {0, 3.62, 48, 160, 288}) {
            double weight = AtmosphereRim.highAltitudeWeight(altitude);
            assertEquals(0, weight);
            var eye = new Point(0, radius + altitude, 0);
            for (var sun : new Point[] {new Point(0, 1, 0), new Point(1, 0, 0)}) {
                var original =
                        AtmosphereScattering.display(
                                AtmosphereScattering.sky(
                                        PlanetAtmosphere.EARTH,
                                        radius,
                                        eye,
                                        new Point(1, 0, 0),
                                        sun));
                assertEquals(original, original.scale(1 - weight));
            }
            assertEquals(0, AtmosphereRim.currentPassWeight(altitude, true));
        }
    }

    @Test
    void handoverIsContinuousAndSingleOwnerAcrossShellAndExteriorBoundaries() {
        double previous = 0;
        for (int altitude = 0; altitude <= 1200; altitude++) {
            double weight = AtmosphereRim.highAltitudeWeight(altitude);
            assertTrue(weight >= previous && weight <= 1);
            assertEquals(1, (1 - weight) + AtmosphereRim.currentPassWeight(altitude, true), 1e-12);
            assertEquals(
                    0,
                    AtmosphereRim.currentPassWeight(altitude, false),
                    "no later own-body overlay");
            previous = weight;
        }
        assertEquals(.5, AtmosphereRim.highAltitudeWeight(432));
        for (double boundary : new double[] {288, 576, 1024, 1088}) {
            double left = AtmosphereRim.highAltitudeWeight(boundary - 1e-4);
            double right = AtmosphereRim.highAltitudeWeight(boundary + 1e-4);
            assertEquals(left, right, 1e-9);
        }
        assertEquals(1, AtmosphereRim.highAltitudeWeight(576));
        assertEquals(1, AtmosphereRim.highAltitudeWeight(1024));
    }

    @Test
    void highAltitudeBroadOpticalLimbCannotBypassTheThinVisualRim() {
        double radius = 2048 * 2 / Math.PI, distance = radius + 1024;
        double impact =
                radius + 96; // Within the shared 288-block optical shell, outside the 36-block rim.
        double angle = Math.asin(impact / distance);
        var eye = new Point(0, distance, 0);
        var ray = new Point(Math.sin(angle), -Math.cos(angle), 0);
        var original =
                AtmosphereScattering.display(
                        AtmosphereScattering.sky(
                                PlanetAtmosphere.EARTH, radius, eye, ray, new Point(0, 1, 0)));
        assertTrue(original.length() > .1, "f5e's full sky still supplies a broad bright limb");
        assertTrue(impact - radius > AtmosphereRim.height(radius));
        var remaining = original.scale(1 - AtmosphereRim.highAltitudeWeight(distance - radius));
        assertEquals(new Point(0, 0, 0), remaining);
        // Atmosphere is not disabled: a tangent ray in the thin layer still scatters and has
        // coverage.
        var section = AtmosphereRim.section(radius, distance, AtmosphereRim.height(radius) / 2);
        var thinRay = new Point(section.radial(), section.axial() - distance, 0);
        var thin =
                AtmosphereScattering.sky(
                        PlanetAtmosphere.EARTH, radius, eye, thinRay, new Point(0, 1, 0));
        assertTrue(thin.length() > 0);
        assertEquals(
                .28,
                AtmosphereRim.opacity(.5) * AtmosphereRim.currentPassWeight(1024, true),
                1e-12);
    }

    @Test
    void skyOwnedRimUsesTheSameCameraRelativeUniversePositionUnderBodyRotation() {
        var frame =
                new GroundEphemeris.Frame(
                        new Point(1000, -2000, 3000),
                        new Quaterniond().rotationXYZ(.2, .7, -.4),
                        new Point(0, 0, 0),
                        new Point(0, 0, 0));
        var localEye = new Point(0, 0, 2300);
        var worldEye = frame.world(localEye);
        var p = new Point(1200, 50, 800);
        var u = frame.inverseVector(new Point(1, 0, 0));
        var up = frame.inverseVector(new Point(0, 1, 0));
        var v = frame.inverseVector(new Point(0, 0, 1));
        var center = worldEye.sub(frame.center()).scale(-1);
        var relative = center.add(new Point(p.dot(u), p.dot(up), p.dot(v)));
        var expected = frame.world(p).sub(worldEye);
        assertEquals(expected.x(), relative.x(), 1e-9);
        assertEquals(expected.y(), relative.y(), 1e-9);
        assertEquals(expected.z(), relative.z(), 1e-9);
    }
}
