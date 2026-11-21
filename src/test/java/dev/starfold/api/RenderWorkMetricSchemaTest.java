package dev.starfold.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

class RenderWorkMetricSchemaTest {
    @AfterEach
    void reset() {
        RenderWorkDiagnostics.register(null);
        RenderWorkDiagnostics.clockForTests(null);
    }

    @Test
    void everyMetricHasUniqueExportNamesAndCorrectTimingClassification() {
        var names = new HashSet<String>();
        for (var metric : RenderWorkDiagnostics.Metric.values()) {
            String name = metric.name().toLowerCase(Locale.ROOT);
            assertTrue(name.matches("[a-z][a-z0-9_]*"), name);
            assertTrue(names.add(name), "duplicate raw name: " + name);
            assertEquals(name.endsWith("_ns"), metric.timing(), name);
            if (metric.timing()) {
                String millis = name.substring(0, name.length() - 3) + "_ms";
                assertTrue(names.add(millis), "duplicate derived name: " + millis);
            }
        }
    }

    @Test
    void allMetricsAccumulateOnlyWithinActiveFramesAndResetBeforeReuse() {
        var metrics = RenderWorkDiagnostics.Metric.values();
        var frames = new ArrayList<long[]>();
        int[] clockReads = {0};
        long[] now = {0};
        RenderWorkDiagnostics.clockForTests(
                () -> {
                    clockReads[0]++;
                    return now[0];
                });
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        for (var metric : metrics) {
            RenderWorkDiagnostics.add(metric, 999);
            RenderWorkDiagnostics.finish(metric, RenderWorkDiagnostics.start());
        }
        assertEquals(0, clockReads[0]);
        RenderWorkDiagnostics.beginFrame();
        assertTrue(RenderWorkDiagnostics.active());
        for (var metric : metrics) {
            long amount = metric.ordinal() + 1L;
            RenderWorkDiagnostics.add(metric, amount);
            RenderWorkDiagnostics.add(metric, amount);
            RenderWorkDiagnostics.add(metric, 0);
            RenderWorkDiagnostics.add(metric, -1);
            if (metric.timing()) {
                long timer = RenderWorkDiagnostics.start();
                now[0] += amount;
                RenderWorkDiagnostics.finish(metric, timer);
                RenderWorkDiagnostics.finish(metric, timer);
            }
        }
        RenderWorkDiagnostics.endFrame();
        assertFalse(RenderWorkDiagnostics.active());
        assertEquals(1, frames.size());
        assertEquals(metrics.length, frames.get(0).length);
        int readsAtEnd = clockReads[0];
        for (var metric : metrics) {
            assertEquals(
                    (metric.ordinal() + 1L) * (metric.timing() ? 3 : 2),
                    frames.get(0)[metric.ordinal()],
                    metric.name());
            RenderWorkDiagnostics.add(metric, 999);
            RenderWorkDiagnostics.finish(metric, RenderWorkDiagnostics.start());
        }
        assertEquals(readsAtEnd, clockReads[0]);
        RenderWorkDiagnostics.endFrame();
        assertEquals(1, frames.size());
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.endFrame();
        assertEquals(2, frames.size());
        assertArrayEquals(new long[metrics.length], frames.get(1));
        RenderWorkDiagnostics.register(null);
        RenderWorkDiagnostics.beginFrame();
        assertFalse(RenderWorkDiagnostics.active());
        RenderWorkDiagnostics.endFrame();
        assertEquals(2, frames.size());
    }
}
