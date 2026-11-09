package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class GasAppearanceTest {
    static final GasGiant GAS = new GasGiant(1, 42, "Amber", 0x98653d, 0xead1a2, 256, 512);

    @Test
    void fixedIndependentPhaseAndBandReferencesAreStable() {
        // Independently calculated using unsigned64 Python arithmetic before any shader
        // implementation.
        assertEquals(4.659389550619531, GasAppearance.phase(42), 1e-15);
        var normals =
                new Point[] {
                    new Point(1, 0, 0),
                    new Point(0, 1, 0),
                    new Point(0, 0, 1),
                    new Point(-1, 0, 0),
                    new Point(0, -1, 0)
                };
        var expected =
                new double[] {
                    .23964184571243735, .7186174833088737, 0, .13801775420978935, .5935630467137237
                };
        for (int i = 0; i < normals.length; i++)
            assertEquals(expected[i], GasAppearance.blend(42, normals[i]), 1e-14);
        assertNotEquals(GasAppearance.phase(42), GasAppearance.phase(43));
        for (long seed : new long[] {Long.MIN_VALUE, -1, 0, 42, Long.MAX_VALUE}) {
            double phase = GasAppearance.phase(seed);
            assertTrue(phase >= 0 && phase < 2 * Math.PI);
            assertEquals(phase, GasAppearance.phase(seed));
        }
    }

    @Test
    void bandsRemainBoundedContinuousAtSeamAndIndependentOfWorldTime() {
        var low = GasAppearance.color(GAS.lowColor());
        var high = GasAppearance.color(GAS.highColor());
        for (int y = 0; y <= 32; y++)
            for (int x = 0; x <= 64; x++) {
                double latitude = y * Math.PI / 32, longitude = x * 2 * Math.PI / 64;
                var normal =
                        new Point(
                                Math.sin(latitude) * Math.cos(longitude),
                                Math.cos(latitude),
                                Math.sin(latitude) * Math.sin(longitude));
                double blend = GasAppearance.blend(42, normal);
                assertTrue(blend >= 0 && blend <= 1);
                var color = GasAppearance.bands(GAS, normal);
                assertTrue(color.x() >= low.x() && color.x() <= high.x());
                assertTrue(color.y() >= low.y() && color.y() <= high.y());
                assertTrue(color.z() >= low.z() && color.z() <= high.z());
            }
        double epsilon = 1e-10;
        assertEquals(
                GasAppearance.blend(42, new Point(-Math.cos(epsilon), 0, Math.sin(epsilon))),
                GasAppearance.blend(42, new Point(-Math.cos(epsilon), 0, -Math.sin(epsilon))),
                1e-8);
        assertEquals(
                GasAppearance.blend(42, new Point(0, 1, 0)),
                GasAppearance.blend(42, new Point(epsilon, Math.sqrt(1 - epsilon * epsilon), 0)),
                1e-8);
    }

    @Test
    void diffuseAndFogColorsAreNotEmissiveSunColors() {
        var normal = new Point(0, 1, 0);
        var bands = GasAppearance.bands(GAS, normal);
        assertEquals(bands, GasAppearance.lit(GAS, normal, normal, 1));
        assertEquals(bands.scale(.12), GasAppearance.lit(GAS, normal, normal.scale(-1), 1));
        assertEquals(bands.scale(.12), GasAppearance.lit(GAS, normal, normal, 0));
        var day = GasAppearance.fogColor(GAS, 1);
        var night = GasAppearance.fogColor(GAS, 0);
        assertEquals(day.scale(.12), night);
        assertEquals(new Point(1, 0, 1), GasAppearance.color(0xff00ff));
    }

    @Test
    void malformedInputsCannotCreateNonfiniteShaderParameters() {
        for (var normal :
                new Point[] {GroundEphemeris.ZERO, new Point(2, 0, 0), new Point(Double.NaN, 0, 0)})
            assertThrows(IllegalArgumentException.class, () -> GasAppearance.blend(42, normal));
        for (double light : new double[] {-1, 2, Double.NaN})
            assertThrows(IllegalArgumentException.class, () -> GasAppearance.fogColor(GAS, light));
        for (int color : new int[] {-1, 0x1000000})
            assertThrows(IllegalArgumentException.class, () -> GasAppearance.color(color));
    }
}
