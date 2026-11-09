package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class GasPlayerExposureValuesTest {
    @Test
    void absentLegacyDataDefaultsButValidValuesRoundTripWithoutWorldClock() {
        assertEquals(GasPlayerExposureValues.initial(), GasPlayerExposureValues.fromFields(null));
        assertEquals(GasPlayerExposureValues.initial(), GasPlayerExposureValues.fromFields(Map.of()));
        for (var values : new GasPlayerExposureValues[] {
                GasPlayerExposureValues.initial(),
                new GasPlayerExposureValues(99, 0, 0),
                new GasPlayerExposureValues(0, 99, 20),
                new GasPlayerExposureValues(100, 100, 0),
                new GasPlayerExposureValues(0, 0, 0)}) {
            assertEquals(values, GasPlayerExposureValues.fromFields(values.toFields()));
            assertEquals(3, values.toFields().size());
            assertThrows(UnsupportedOperationException.class, () -> values.toFields().put("clock", 42));
        }
    }

    @Test
    void presentBadTypesMissingPartialAndOutOfRangeFailClosedWithoutThrowingOrFreshGrace() {
        var failClosed = new GasPlayerExposureValues(0, 0, 0);
        var good = new GasPlayerExposureValues(0, 50, 10).toFields();
        for (var key : good.keySet()) {
            var partial = new HashMap<String, Object>(good);
            partial.remove(key);
            assertEquals(failClosed, GasPlayerExposureValues.fromFields(partial));
            for (Object bad : new Object[] {null, "20", 1L, 1.0, Double.NaN, false, -1, Integer.MAX_VALUE}) {
                var fields = new HashMap<String, Object>(good);
                fields.put(key, bad);
                assertEquals(failClosed, GasPlayerExposureValues.fromFields(fields), key + ":" + bad);
            }
        }
        assertEquals(failClosed, GasPlayerExposureValues.fromFields(Map.of("wrong_type", true)));
        for (var fields : java.util.List.of(
                Map.of("grace_ticks", 101, "safe_ticks", 0, "attempt_ticks", 0),
                Map.of("grace_ticks", 0, "safe_ticks", 101, "attempt_ticks", 0),
                Map.of("grace_ticks", 0, "safe_ticks", 0, "attempt_ticks", 21),
                Map.of("grace_ticks", 1, "safe_ticks", 0, "attempt_ticks", 1),
                Map.of("grace_ticks", 0, "safe_ticks", 100, "attempt_ticks", 0)))
            assertEquals(failClosed, GasPlayerExposureValues.fromFields(fields));
    }

    @Test
    void recordConstructionEnforcesAllTimerBoundsAndCrossFieldInvariants() {
        for (var tuple : new int[][] {
                {-1, 0, 0}, {101, 0, 0}, {0, -1, 0}, {0, 101, 0}, {0, 0, -1}, {0, 0, 21},
                {50, 0, 1}, {0, 100, 0}, {100, 100, 20}})
            assertThrows(IllegalArgumentException.class,
                    () -> new GasPlayerExposureValues(tuple[0], tuple[1], tuple[2]));
    }
}
