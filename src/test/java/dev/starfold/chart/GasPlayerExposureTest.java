package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

class GasPlayerExposureTest {
    private static final Point ZERO = new Point(0, 0, 0);

    private static GasPressure.Layer layer(int id, Point center, double maxDepth) {
        return new GasPressure.Layer(id, center, 4096,
                new GasGiant(1, 0, "test", 0, 1, 256, maxDepth));
    }

    @Test
    void exactDepthAndPressureThresholdsUseActualCenterWithNoHullPadding() {
        var layers = List.of(layer(7, ZERO, 512));
        assertEquals(GasPlayerExposure.Risk.NONE,
                GasPlayerExposure.sample(layers, new Point(4352, 0, 0)).risk());
        assertEquals(GasPlayerExposure.Risk.PRESSURE,
                GasPlayerExposure.sample(layers, new Point(4351, 0, 0)).risk());
        assertEquals(GasPlayerExposure.Risk.STRUCTURAL,
                GasPlayerExposure.sample(layers, new Point(3968, 0, 0)).risk());
        assertEquals(GasPlayerExposure.Risk.NEAR_LIMIT,
                GasPlayerExposure.sample(layers, new Point(4096 - 358.4, 0, 0)).risk());
        var above = GasPlayerExposure.sample(layers, new Point(Math.nextUp(3584.0), 0, 0));
        assertEquals(GasPlayerExposure.Condition.SAFE, above.condition());
        assertEquals(GasPlayerExposure.Risk.NEAR_LIMIT, above.risk());
        var at = GasPlayerExposure.sample(layers, new Point(3584, 0, 0));
        assertEquals(GasPlayerExposure.Condition.DANGER, at.condition());
        assertEquals(GasPlayerExposure.Risk.SURVIVAL_LIMIT, at.risk());
        assertEquals(GasPlayerExposure.Condition.DANGER,
                GasPlayerExposure.sample(layers, ZERO).condition());
        var center = new Point(4500, 0, 0);
        assertEquals(0, GasPlayerExposure.sample(layers, center).reading().orElseThrow().pressure());
        assertTrue(layers.getFirst().read(center, 1000).pressure() > .5);
    }

    @Test
    void overlappingGasUsesEachPoliciesDepthAndProducesOnePersonalSample() {
        var shallow = layer(9, new Point(1000, 0, 0), 16);
        var deep = layer(2, ZERO, 1024);
        var center = new Point(4096 + 1000 - 16, 0, 0);
        var sample = GasPlayerExposure.sample(List.of(deep, shallow), center);
        assertEquals(GasPlayerExposure.Condition.DANGER, sample.condition());
        assertEquals(9, sample.reading().orElseThrow().bodyIndex());
        var a = layer(9, ZERO, 512);
        var b = layer(2, ZERO, 512);
        assertEquals(GasPlayerExposure.sample(List.of(a, b), ZERO),
                GasPlayerExposure.sample(List.of(b, a), ZERO));
        assertEquals(2, GasPlayerExposure.sample(List.of(a, b), ZERO).reading().orElseThrow().bodyIndex());
    }

    @Test
    void currentFrameTranslationIsSharedByLayersAndPlayerRegardlessOfShipPresence() {
        var center = new Point(12000, -500, 90);
        var player = center.add(new Point(3584, 0, 0));
        var translated = GasPlayerExposure.sample(List.of(layer(4, center, 512)), player);
        var origin = GasPlayerExposure.sample(List.of(layer(4, ZERO, 512)), new Point(3584, 0, 0));
        assertEquals(origin, translated);
        // There is deliberately no aboard/ship parameter: crew and free players share this API.
        assertNotEquals(origin.condition(),
                GasPlayerExposure.sample(List.of(layer(4, ZERO, 512)), player).condition());
    }

    @Test
    void missingInvalidGeometryIsUnknownWhileKnownNonGasIsSafe() {
        assertEquals(GasPlayerExposure.Condition.SAFE,
                GasPlayerExposure.sample(List.of(), ZERO).condition());
        assertEquals(GasPlayerExposure.Risk.NONE, GasPlayerExposure.confirmedSafe().risk());
        for (var sample : List.of(
                GasPlayerExposure.sample(null, ZERO),
                GasPlayerExposure.sample(List.of(), null),
                GasPlayerExposure.sample(List.of(), new Point(Double.NaN, 0, 0)),
                GasPlayerExposure.sample(Arrays.asList((GasPressure.Layer) null), ZERO),
                GasPlayerExposure.sample(List.of(layer(1, ZERO, 512), layer(1, ZERO, 512)), ZERO),
                GasPlayerExposure.sample(List.of(layer(1, ZERO, 512)), new Point(Double.MAX_VALUE, 0, 0)))) {
            assertEquals(GasPlayerExposure.Condition.UNKNOWN, sample.condition());
            assertEquals(GasPlayerExposure.Risk.UNKNOWN, sample.risk());
            assertTrue(sample.reading().isEmpty());
        }
    }
}
