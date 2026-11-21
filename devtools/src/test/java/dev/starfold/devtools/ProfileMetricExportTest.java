package dev.starfold.devtools;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.api.RenderWorkDiagnostics.Metric;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

class ProfileMetricExportTest {
    @AfterEach
    void unregister() {
        RenderWorkDiagnostics.register(null);
    }

    @Test
    void everyEnumMetricSurvivesFrameCopyCsvAndSummary() throws Exception {
        Metric[] metrics = Metric.values();
        assertArrayEquals(metrics, Capture.WORK_METRICS);
        var queries = new CaptureTest.Fake();
        try (var capture = new Capture(queries, 3, 1, 1)) {
            var frames = new ProfileFrames(capture);
            for (int frame = 0; frame < 3; frame++) {
                for (var metric : metrics) RenderWorkDiagnostics.add(metric, 999_999);
                frames.open(frame * 100L);
                for (var metric : metrics) {
                    if (frame < 2) {
                        RenderWorkDiagnostics.add(metric, value(metric) * (frame + 1));
                        RenderWorkDiagnostics.add(metric, 0);
                        RenderWorkDiagnostics.add(metric, -1);
                    }
                }
                frames.frameEnd(frame * 100L + 50);
                assertFalse(RenderWorkDiagnostics.active());
                // Callback values are borrowed and cleared by endFrame; capture must own the copy.
                for (var metric : metrics) {
                    assertEquals(
                            frame < 2 ? value(metric) * (frame + 1) : 0,
                            capture.work(frame, metric),
                            metric.name());
                    RenderWorkDiagnostics.add(metric, 999_999);
                }
                frames.boundary((frame + 1) * 100L);
            }
            // Only the FRAME pair and its fence use timestamps; metric count adds no queries.
            assertEquals(3, queries.next);
            assertEquals(3, queries.stamps);
            var out = new StringWriter();
            ProfileReport.csv(capture, out);
            String[] lines = out.toString().split("\n");
            assertEquals(4, lines.length);
            var expected = new ArrayList<String>();
            expected.add("frame");
            expected.add("frame_ms");
            for (String section : Capture.NAMES) {
                expected.add(section + "_cpu_ms");
                expected.add(section + "_gpu_ms");
            }
            for (var metric : metrics) {
                String name = metric.name().toLowerCase(Locale.ROOT);
                expected.add(name);
                if (metric.timing()) expected.add(name.substring(0, name.length() - 3) + "_ms");
            }
            expected.add("gc_pause_ms");
            assertEquals(expected.size(), new HashSet<>(expected).size(), "unique CSV names");
            assertEquals(expected, Arrays.asList(lines[0].split(",", -1)));
            for (int frame = 0; frame < 3; frame++) {
                String[] cells = lines[frame + 1].split(",", -1);
                assertEquals(expected.size(), cells.length);
                int column = 2 + 2 * Capture.SECTIONS;
                for (var metric : metrics) {
                    long raw = frame < 2 ? value(metric) * (frame + 1) : 0;
                    assertEquals(raw, Long.parseLong(cells[column++]), metric.name());
                    if (metric.timing()) {
                        assertEquals(
                                raw / 1e6,
                                Double.parseDouble(cells[column++]),
                                0.0000005,
                                metric.name());
                    }
                }
                assertEquals(0, Double.parseDouble(cells[column++]));
                assertEquals(cells.length, column);
            }
            var summary = new ArrayList<String>();
            ProfileReport.workSummary(capture, summary::add);
            assertEquals(metrics.length + 1, summary.size());
            for (var metric : metrics) {
                double scale = metric.timing() ? 1e6 : 1;
                assertEquals(
                        String.format(
                                Locale.ROOT,
                                "%s: total %.3f / mean %.3f / p95 %.3f / max %.3f %s; n=3",
                                metric.name().toLowerCase(Locale.ROOT),
                                value(metric) * 3 / scale,
                                value(metric) / scale,
                                value(metric) * 2 / scale,
                                value(metric) * 2 / scale,
                                metric.timing() ? "ms" : "count"),
                        summary.get(metric.ordinal() + 1));
            }
        }
    }

    @Test
    void rawCsvIntegersRoundTripBeyondDoublePrecision() throws Exception {
        try (var capture = new Capture(new CaptureTest.Fake(), 1, 1, 1)) {
            long[] values = new long[Metric.values().length];
            for (var metric : Metric.values())
                values[metric.ordinal()] = (1L << 53) + 1 + metric.ordinal();
            capture.open(0);
            capture.recordWork(values);
            Arrays.fill(values, 0);
            capture.boundary(100);
            var out = new StringWriter();
            ProfileReport.csv(capture, out);
            String[] cells = out.toString().split("\n")[1].split(",", -1);
            int column = 2 + 2 * Capture.SECTIONS;
            for (var metric : Metric.values()) {
                assertEquals(
                        (1L << 53) + 1 + metric.ordinal(),
                        Long.parseLong(cells[column++]),
                        metric.name());
                if (metric.timing()) column++;
            }
        }
    }

    @Test
    void maximumRecordingStorageFitsExplicitHeapBudgetWithDynamicSchema() throws Exception {
        int frames = 120_000;
        var queries = new CaptureTest.Fake();
        // Check before allocating, so schema growth fails clearly rather than exhausting the heap.
        long expectedBytes =
                (long) frames * (2 * Capture.SECTIONS + 2 + Metric.values().length) * Long.BYTES;
        assertTrue(
                expectedBytes <= 256L * 1024 * 1024,
                () ->
                        "120000-frame storage exceeds 256 MiB: "
                                + expectedBytes
                                + " bytes; reconsider the frame cap before expanding the schema");
        try (var capture = new Capture(queries, frames, 8, 256)) {
            long actualBytes = 0;
            for (var field : Capture.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long[].class)
                    continue;
                field.setAccessible(true);
                actualBytes += (long) ((long[]) field.get(capture)).length * Long.BYTES;
            }
            // Includes the small section start array, excludes JVM headers and query/state arrays.
            assertEquals(expectedBytes + (long) Capture.SECTIONS * Long.BYTES, actualBytes);
            assertEquals(8 * (2 * 256 + 1), queries.next);
            assertEquals(0, queries.stamps);
            assertEquals(0, queries.reads);
        }
        assertEquals(queries.next, queries.deletes);
    }

    private static long value(Metric metric) {
        return (metric.ordinal() + 1L) * 1_250_001;
    }
}
