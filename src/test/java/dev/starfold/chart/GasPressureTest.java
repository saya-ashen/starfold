package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GasPressureTest {
    private static GasPressure.Layer layer(int index, Point center) {
        return new GasPressure.Layer(index, center, 4096, GasGiantTest.policy());
    }

    @Test
    void conservativeHullSampleReachesThresholdBeforeItsAnchorAndClampsAtCenter() {
        var layer = layer(5, GroundEphemeris.ZERO);
        assertEquals(0, layer.read(new Point(4352, 0, 0), 0).pressure());
        assertEquals(0, layer.read(new Point(4352 + 80, 0, 0), 80).pressure());
        // Independent arithmetic: threshold depth=(512-256)/2=128, not the nominal radius.
        assertEquals(.5, layer.read(new Point(4096 - 128 + 80, 0, 0), 80).pressure());
        assertTrue(layer.read(new Point(4096 - 128 + 80, 0, 0), 0).pressure() < .5);
        assertEquals(100, layer.read(new Point(3968, 0, 0), 0).damageInterval());
        assertEquals(20, layer.read(new Point(3584, 0, 0), 0).damageInterval());
        var enveloped = layer.read(new Point(1, 2, 3), 9999);
        assertEquals(4096, enveloped.depth());
        assertEquals(1, enveloped.pressure());
        for (int distance = 5000; distance >= 0; distance -= 5) {
            var a = layer.read(new Point(distance, 0, 0), 12);
            var b = layer.read(new Point(Math.max(0, distance - 5), 0, 0), 12);
            assertTrue(b.pressure() >= a.pressure());
        }
    }

    @Test
    void largestPressureWinsAndTiesUseCatalogIdentityNotIterationOrder() {
        var lower = layer(4, GroundEphemeris.ZERO);
        var higher = layer(19, new Point(0, 1, 0));
        var field = new GasPressure.Field(List.of(higher, lower));
        assertEquals(4, field.worst(GroundEphemeris.ZERO, 2).orElseThrow().bodyIndex());
        var nearHigher = new Point(0, 4100, 0);
        assertEquals(19, field.worst(nearHigher, 0).orElseThrow().bodyIndex());
        assertEquals(lower.read(nearHigher, 0), field.selected(4, nearHigher, 0).orElseThrow());
        assertTrue(field.selected(7, nearHigher, 0).isEmpty());
        var reversed = new GasPressure.Field(List.of(lower, higher));
        assertEquals(field.worst(nearHigher, 0), reversed.worst(nearHigher, 0));
    }

    @Test
    void fieldIsImmutableBoundedAndRejectsBadPressureGeometry() {
        var source = new ArrayList<>(List.of(layer(4, GroundEphemeris.ZERO)));
        var field = new GasPressure.Field(source);
        source.clear();
        assertFalse(field.empty());
        assertTrue(new GasPressure.Field(List.of()).empty());
        assertTrue(new GasPressure.Field(List.of()).worst(GroundEphemeris.ZERO, 0).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GasPressure.Field(
                                List.of(
                                        layer(4, GroundEphemeris.ZERO),
                                        layer(4, GroundEphemeris.ZERO))));
        var oversized = new ArrayList<GasPressure.Layer>();
        for (int i = 0; i <= GasGiant.MAX_COUNT; i++) oversized.add(layer(i, GroundEphemeris.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new GasPressure.Field(oversized));
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class, () -> field.worst(GroundEphemeris.ZERO, bad));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> field.selected(4, GroundEphemeris.ZERO, bad));
        }
        assertThrows(IllegalArgumentException.class, () -> layer(-1, GroundEphemeris.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> new GasPressure.Reading(1, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new GasPressure.Reading(1, 0, 1.01));
    }

    @Test
    void physicalBlockSampleDoesNotInheritAnUnexposedShipsBroadPhasePressure() {
        var layer = layer(5, GroundEphemeris.ZERO);
        var anchor = new Point(4500, 0, 0);
        assertTrue(layer.read(anchor, 1000).pressure() > .5);
        // A long mounted child can raise hull hazard; this actual parent block is still safe.
        assertEquals(0, layer.read(anchor, Math.sqrt(3) / 2).pressure());
    }

    @Test
    void snapshotUsesAppendedCatalogFramesWithoutInventingGasInAnOldModel() {
        assertTrue(GasPressure.snapshot(GroundSolar.defaults(), 1000).empty());
        var model = GasGiantTest.mixed();
        int index = model.bodies().size() - 1;
        assertTrue(index >= model.count());
        for (double tick : new double[] {0, 100, 1000.5, 10000}) {
            var field = GasPressure.snapshot(model, tick);
            var center = model.frame(index, tick).center();
            assertEquals(1, field.selected(index, center, 0).orElseThrow().pressure());
            var outside = center.add(new Point(4352, 0, 0));
            assertEquals(0, field.worst(outside, 0).orElseThrow().pressure(), 1e-12);
            assertTrue(field.selected(model.index(0), outside, 0).isEmpty());
        }
    }
}
