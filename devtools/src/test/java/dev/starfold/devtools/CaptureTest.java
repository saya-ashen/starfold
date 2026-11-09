package dev.starfold.devtools;

import org.junit.jupiter.api.Test;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CaptureTest {
    static final class Fake implements Capture.Queries {
        int next, stamps, reads, checks, deletes;
        long clock;
        boolean ready;
        final Map<Integer, Long> values = new HashMap<>();
        final Set<Integer> readyIds = new HashSet<>();
        public int create() { return ++next; }
        public void timestamp(int q) { values.put(q, ++clock * 100); stamps++; }
        public boolean available(int q) { checks++; return ready || readyIds.contains(q); }
        public long result(int q) {
            assertTrue(ready || !readyIds.isEmpty(), "read before availability");
            reads++;
            return values.get(q);
        }
        public void delete(int q) { deletes++; }
    }
    @Test void nestedAndRepeatedSectionsAccumulateWithDelayedFence() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 10, 8, 8)) {
            c.open(0);
            c.begin(0, 10); c.begin(1, 20); c.end(1, 30); c.end(0, 50);
            c.begin(1, 60); c.end(1, 80);
            c.boundary(100);
            assertEquals(0, q.checks);
            c.open(100); c.boundary(200);
            assertEquals(0, q.checks);
            q.ready = true;
            c.open(200); c.boundary(300);
            assertEquals(1, q.checks);
            assertEquals(40, c.cpu(0, 0)); assertEquals(30, c.cpu(0, 1));
            assertEquals(300, c.gpu(0, 0)); assertEquals(200, c.gpu(0, 1));
            assertEquals(100, c.interval(0));
        }
        assertEquals(q.next, q.deletes);
    }
    @Test void unavailableRingNeverWaitsOrReusesPendingQueries() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 5, 2, 2)) {
            for (int f = 0; f < 4; f++) {
                c.open(f * 100); c.begin(0, 1); c.end(0, 2); c.boundary((f + 1) * 100);
            }
            assertEquals(6, q.stamps); // two pairs + two frame fences, no overwritten queries
            assertEquals(0, q.reads);
            c.finish();
            assertEquals(0, q.reads);
            for (int f = 0; f < 4; f++) assertEquals(-1, c.gpu(f, 0));
            assertEquals(4, c.size());
        }
    }
    @Test void laterFramesRecoverWhenPendingQueriesBecomeAvailable() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 5, 2, 2)) {
            for (int f = 0; f < 3; f++) {
                c.open(f * 100); c.begin(0, 1); c.end(0, 2); c.boundary((f + 1) * 100);
            }
            q.ready = true;
            c.open(300); c.boundary(400);
            c.open(400); c.begin(0, 1); c.end(0, 2); c.boundary(500); c.finish();
            assertEquals(100, c.gpu(4, 0));
            assertTrue(c.full());
        }
    }
    @Test void overflowMarksWholeSectionMissingAndKeepsCpu() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 1, 4, 1)) {
            c.open(0); c.begin(0, 1); c.end(0, 3); c.begin(0, 4); c.end(0, 8);
            c.boundary(10); q.ready = true; c.finish();
            assertEquals(6, c.cpu(0, 0)); assertEquals(-1, c.gpu(0, 0));
            assertEquals(2, q.reads);
        }
    }
    @Test void fenceMustFollowOuterEndNotLastAllocatedPair() {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 4, 4, 4)) {
            c.open(0); c.begin(0, 1); c.begin(1, 2); c.end(1, 3);
            q.readyIds.add(5); // last allocated pair end, but outer is not done
            c.end(0, 4); c.boundary(100);
            c.open(100); c.boundary(200); c.open(200); c.boundary(300);
            assertEquals(0, q.reads);
        }
    }
    @Test void csvKeepsMissingDistinctFromZeroAndHasStableColumns() throws Exception {
        Fake q = new Fake();
        try (Capture c = new Capture(q, 1, 4, 2)) {
            c.open(0); c.begin(0, 0); c.end(0, 1_000_000); c.boundary(20_000_000); c.finish();
            StringWriter out = new StringWriter(); ProfileReport.csv(c, out);
            String[] lines = out.toString().split("\n");
            assertEquals(2, lines.length);
            assertEquals(24, lines[0].split(",", -1).length);
            String[] cells = lines[1].split(",", -1);
            assertEquals(24, cells.length); assertEquals("20.000000", cells[1]);
            assertEquals("1.000000", cells[2]); assertEquals("", cells[3]);
            assertEquals("0.000000", cells[5]);
        }
    }
    @Test void nearestRankQuantilesAndMatchedFrameDenominator() {
        long[] values = new long[100], frames = new long[100];
        for (int i = 0; i < 100; i++) { values[i] = (i + 1) * 1_000_000L; frames[i] = 100_000_000; }
        var s = ProfileReport.stats(values, frames);
        assertEquals(50, s.median()); assertEquals(95, s.p95()); assertEquals(99, s.p99());
        assertEquals(100, s.max()); assertEquals(50.5, s.percent());
        s = ProfileReport.stats(new long[]{-1, 10}, new long[]{1000, 100});
        assertEquals(10, s.percent()); assertEquals(1, s.samples());
        assertTrue(Double.isNaN(ProfileReport.stats(new long[]{-1}, new long[]{1}).median()));
    }
}
