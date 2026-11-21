package dev.starfold.devtools;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.RenderWorkDiagnostics;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class CaptureTest {
    static final class Fake implements Capture.Queries {
        int next, stamps, reads, checks, deletes;
        long clock;
        boolean ready;
        final Map<Integer, Long> values = new HashMap<>();
        final Set<Integer> readyIds = new HashSet<>();

        public int create() {
            return ++next;
        }

        public void timestamp(int q) {
            values.put(q, ++clock * 100);
            stamps++;
        }

        public boolean available(int q) {
            checks++;
            return ready || readyIds.contains(q);
        }

        public long result(int q) {
            assertTrue(ready || !readyIds.isEmpty(), "read before availability");
            reads++;
            return values.get(q);
        }

        public void delete(int q) {
            deletes++;
        }
    }

    @Test
    void gcPausesSplitAcrossFrameAndPacingBoundaries() {
        try (Capture c = new Capture(new Fake(), 3, 4, 4)) {
            c.open(100); c.boundary(200);
            c.open(200); c.boundary(300);
            c.recordGcPause(-20, 30);
            c.recordGcPause(90, 30);
            c.recordGcPause(205, 50);
            assertEquals(20, c.gcPause(0));
            assertEquals(20, c.gcPause(1));
        }
    }

    @Test
    void cpuOnlyTickUsesNoTimestampPairs() {
        var q = new Fake();
        try (Capture c = new Capture(q, 1, 4, 4)) {
            c.open(0); c.beginCpu(Capture.CLIENT_TICK, 1); c.end(Capture.CLIENT_TICK, 11); c.boundary(20);
            assertEquals(10, c.cpu(0, Capture.CLIENT_TICK));
            assertEquals(1, q.stamps);
        }
    }

    @Test
    void nestedAndRepeatedSectionsAccumulateWithDelayedFence() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 10, 8, 8)) {
            c.open(0);
            c.begin(0, 10);
            c.begin(1, 20);
            c.end(1, 30);
            c.end(0, 50);
            c.begin(1, 60);
            c.end(1, 80);
            c.boundary(100);
            assertEquals(0, q.checks);
            c.open(100);
            c.boundary(200);
            assertEquals(0, q.checks);
            q.ready = true;
            c.open(200);
            c.boundary(300);
            assertEquals(1, q.checks);
            assertEquals(40, c.cpu(0, 0));
            assertEquals(30, c.cpu(0, 1));
            assertEquals(300, c.gpu(0, 0));
            assertEquals(200, c.gpu(0, 1));
            assertEquals(100, c.interval(0));
        }
        assertEquals(q.next, q.deletes);
    }

    @Test
    void unavailableRingNeverWaitsOrReusesPendingQueries() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 5, 2, 2)) {
            for (int f = 0; f < 4; f++) {
                c.open(f * 100);
                c.begin(0, 1);
                c.end(0, 2);
                c.boundary((f + 1) * 100);
            }
            assertEquals(6, q.stamps); // two pairs + two frame fences, no overwritten queries
            assertEquals(0, q.reads);
            c.finish();
            assertEquals(0, q.reads);
            for (int f = 0; f < 4; f++) assertEquals(-1, c.gpu(f, 0));
            assertEquals(4, c.size());
        }
    }

    @Test
    void laterFramesRecoverWhenPendingQueriesBecomeAvailable() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 5, 2, 2)) {
            for (int f = 0; f < 3; f++) {
                c.open(f * 100);
                c.begin(0, 1);
                c.end(0, 2);
                c.boundary((f + 1) * 100);
            }
            q.ready = true;
            c.open(300);
            c.boundary(400);
            c.open(400);
            c.begin(0, 1);
            c.end(0, 2);
            c.boundary(500);
            c.finish();
            assertEquals(100, c.gpu(4, 0));
            assertTrue(c.full());
        }
    }

    @Test
    void overflowMarksWholeSectionMissingAndKeepsCpu() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 1, 4, 1)) {
            c.open(0);
            c.begin(0, 1);
            c.end(0, 3);
            c.begin(0, 4);
            c.end(0, 8);
            c.boundary(10);
            q.ready = true;
            c.finish();
            assertEquals(6, c.cpu(0, 0));
            assertEquals(-1, c.gpu(0, 0));
            assertEquals(2, q.reads);
        }
    }

    @Test
    void fenceMustFollowOuterEndNotLastAllocatedPair() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 4, 4, 4)) {
            c.open(0);
            c.begin(0, 1);
            c.begin(1, 2);
            c.end(1, 3);
            q.readyIds.add(5); // last allocated pair end, but outer is not done
            c.end(0, 4);
            c.boundary(100);
            c.open(100);
            c.boundary(200);
            c.open(200);
            c.boundary(300);
            assertEquals(0, q.reads);
        }
    }

    @Test
    void csvKeepsMissingDistinctFromZeroAndHasStableColumns() throws Exception {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 1, 4, 2)) {
            c.open(0);
            c.begin(0, 0);
            c.end(0, 1_000_000);
            c.boundary(20_000_000);
            c.finish();
            StringWriter out = new StringWriter();
            ProfileReport.csv(c, out);
            String[] lines = out.toString().split("\n");
            assertEquals(2, lines.length);
            int appended = 0;
            for (var metric : Capture.WORK_METRICS) appended += metric.timing() ? 2 : 1;
            assertEquals(2 + 2 * Capture.SECTIONS + appended + 1, lines[0].split(",", -1).length);
            assertTrue(lines[0].startsWith("frame,frame_ms,mesh_cpu_ms,mesh_gpu_ms,"));
            assertTrue(
                    lines[0].contains(
                            ",chunks_cpu_ms,chunks_gpu_ms,entities_cpu_ms,entities_gpu_ms,"));
            String[] cells = lines[1].split(",", -1);
            assertEquals(2 + 2 * Capture.SECTIONS + appended + 1, cells.length);
            for (int i = 2 + 2 * Capture.SECTIONS; i < cells.length; i++) assertEquals(0, Double.parseDouble(cells[i]));
            assertEquals("20.000000", cells[1]);
            assertEquals("1.000000", cells[2]);
            assertEquals("", cells[3]);
            assertEquals("0.000000", cells[5]);
        }
    }

    @Test
    void fineMetricsCopyAtBoundaryExportExactTotalsWithoutGpuQueries() throws Exception {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 2, 2, 2)) {
            RenderWorkDiagnostics.register(c::recordWork);
            assertFalse(RenderWorkDiagnostics.active());
            for (int f = 0; f < 2; f++) {
                c.open(f * 10_000_000L);
                RenderWorkDiagnostics.beginFrame();
                RenderWorkDiagnostics.add(
                        RenderWorkDiagnostics.Metric.NATIVE_REQUIRED_NS, 1_250_001);
                RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.FAR_FULL_REBUILDS, f + 1);
                RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.FAR_REASON_VIEW, 1);
                RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.FAR_REASON_GEOMETRY, 1);
                RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.FAR_INDICES_SUBMITTED, 12);
                RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.FAR_VERTICES_SUBMITTED, 8);
                RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.NATIVE_WATER_SCAN_NS, 100);
                RenderWorkDiagnostics.endFrame();
                c.boundary((f + 1) * 10_000_000L);
            }
            assertEquals(2, q.stamps); // frame fences only, no fine metric timestamps
            assertEquals(1, c.work(0, RenderWorkDiagnostics.Metric.FAR_FULL_REBUILDS));
            assertEquals(2, c.work(1, RenderWorkDiagnostics.Metric.FAR_FULL_REBUILDS));
            StringWriter out = new StringWriter();
            ProfileReport.csv(c, out);
            String[] lines = out.toString().split("\n");
            String[] names = lines[0].split(","), first = lines[1].split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < names.length; i++) row.put(names[i], first[i]);
            assertEquals("1250001", row.get("native_required_ns"));
            assertEquals("1.250001", row.get("native_required_ms"));
            assertEquals("0", row.get("native_water_ns"));
            assertEquals("12", row.get("far_indices_submitted"));
            assertEquals("8", row.get("far_vertices_submitted"));
            assertEquals("100", row.get("native_water_scan_ns"));
            assertEquals("0.000100", row.get("native_water_scan_ms"));
            assertFalse(row.containsKey("far_pack_ns"));
            assertFalse(row.containsKey("far_fade_updates"));
            assertTrue(row.containsKey("far_command_assembly_ns"));
            assertTrue(row.containsKey("far_command_assembly_ms"));
            var summary = new java.util.ArrayList<String>();
            ProfileReport.workSummary(c, summary::add);
            assertTrue(
                    summary.stream()
                            .anyMatch(
                                    line ->
                                            line.contains(
                                                    "far_full_rebuilds: total 3.000 / mean 1.500 /"
                                                            + " p95 2.000 / max 2.000 count; n=2")));
            assertTrue(
                    summary.stream()
                            .anyMatch(
                                    line ->
                                            line.contains(
                                                    "native_required_ns: total 2.500 / mean"
                                                            + " 1.250")));
            assertTrue(summary.get(0).contains("do not add parent/child totals"));
            assertTrue(summary.get(0).contains("incomplete partition"));
            assertTrue(summary.get(0).contains("command assembly outside prepare"));
            assertTrue(summary.get(0).contains("raw index counts"));
            assertTrue(summary.get(0).contains("not unique vertices or hardware invocations"));
        } finally {
            RenderWorkDiagnostics.register(null);
        }
    }

    @Test
    void emptyFineSummaryAndCsvAreValid() throws Exception {
        try (Capture c = new Capture(new Fake(), 1, 1, 1)) {
            var summary = new java.util.ArrayList<String>();
            ProfileReport.workSummary(c, summary::add);
            assertTrue(summary.get(1).contains("total 0.000 / mean 0.000 / p95 0.000 / max 0.000"));
            StringWriter out = new StringWriter();
            ProfileReport.csv(c, out);
            assertEquals(1, out.toString().split("\n").length);
        }
    }

    @Test
    void nearestRankQuantilesAndMatchedFrameDenominator() {
        long[] values = new long[100], frames = new long[100];
        for (int i = 0; i < 100; i++) {
            values[i] = (i + 1) * 1_000_000L;
            frames[i] = 100_000_000;
        }
        var s = ProfileReport.stats(values, frames);
        assertEquals(50, s.median());
        assertEquals(95, s.p95());
        assertEquals(99, s.p99());
        assertEquals(100, s.max());
        assertEquals(50.5, s.percent());
        s = ProfileReport.stats(new long[] {-1, 10}, new long[] {1000, 100});
        assertEquals(10, s.percent());
        assertEquals(1, s.samples());
        assertTrue(Double.isNaN(ProfileReport.stats(new long[] {-1}, new long[] {1}).median()));
    }
}
