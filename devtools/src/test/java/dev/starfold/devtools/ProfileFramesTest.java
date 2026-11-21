package dev.starfold.devtools;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.RenderWorkDiagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProfileFramesTest {
    @AfterEach
    void reset() {
        RenderWorkDiagnostics.register(null);
    }

    @Test
    void endDeliversImmediatelyAndOffFrameWorkCannotOverwriteRow() {
        var q = new CaptureTest.Fake();
        try (var c = new Capture(q, 2, 1, 4)) {
            var frames = new ProfileFrames(c);
            assertFalse(RenderWorkDiagnostics.active());
            frames.open(100);
            RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 3);
            long stale = RenderWorkDiagnostics.start();
            frames.frameEnd(150);
            assertFalse(RenderWorkDiagnostics.active());
            assertEquals(3, c.work(0, NATIVE_DIRTY_COLUMNS));
            int stamps = q.stamps;
            frames.begin(Capture.LEVEL, 160);
            frames.end(Capture.LEVEL, 170);
            RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 900);
            RenderWorkDiagnostics.finish(NATIVE_READY_NS, stale);
            assertEquals(0, RenderWorkDiagnostics.start());
            frames.frameEnd(180);
            assertEquals(stamps, q.stamps);
            frames.boundary(200);
            assertEquals(100, c.interval(0)); // includes pacing after render end
            assertEquals(50, c.cpu(0, Capture.FRAME));
            assertEquals(3, c.work(0, NATIVE_DIRTY_COLUMNS));
            frames.open(200);
            RenderWorkDiagnostics.finish(NATIVE_READY_NS, stale);
            RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 4);
            frames.frameEnd(250);
            frames.boundary(300);
            assertEquals(3, c.work(0, NATIVE_DIRTY_COLUMNS));
            assertEquals(4, c.work(1, NATIVE_DIRTY_COLUMNS));
            assertEquals(0, c.work(1, NATIVE_READY_NS));
        }
    }

    @Test
    void stopClosesInsideFrameAndPreventsReopenUntilExport() {
        var q = new CaptureTest.Fake();
        try (var c = new Capture(q, 2, 1, 4)) {
            var frames = new ProfileFrames(c);
            frames.open(10);
            RenderWorkDiagnostics.add(FAR_CACHE_REUSES, 1);
            frames.stop(20);
            assertFalse(RenderWorkDiagnostics.active());
            assertEquals(1, c.work(0, FAR_CACHE_REUSES));
            int stamps = q.stamps;
            RenderWorkDiagnostics.add(FAR_CACHE_REUSES, 9);
            frames.begin(Capture.FRAME, 21);
            frames.end(Capture.FRAME, 22);
            frames.frameEnd(23);
            frames.stop(24);
            assertEquals(stamps, q.stamps);
            frames.boundary(30);
            frames.open(30);
            assertFalse(RenderWorkDiagnostics.active());
            assertEquals(1, c.size());
            assertEquals(20, c.interval(0));
            assertEquals(10, c.cpu(0, Capture.FRAME));
            assertEquals(1, c.work(0, FAR_CACHE_REUSES));
        }
    }

    @Test
    void wrappedExceptionStillClosesBeforeNextBoundary() {
        try (var c = new Capture(new CaptureTest.Fake(), 2, 1, 4)) {
            var frames = new ProfileFrames(c);
            frames.open(10);
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        try {
                            RenderWorkDiagnostics.add(FAR_DRAW_CALLS, 1);
                            throw new IllegalStateException();
                        } finally {
                            // Same finally boundary used by the production FrameMixin wrapper.
                            frames.frameEnd(15);
                        }
                    });
            assertFalse(RenderWorkDiagnostics.active());
            assertEquals(1, c.work(0, FAR_DRAW_CALLS));
            frames.boundary(20);
            assertEquals(1, c.work(0, FAR_DRAW_CALLS));
            assertEquals(10, c.interval(0));
            assertEquals(5, c.cpu(0, Capture.FRAME));
        }
    }

    @Test
    void abandonedExceptionalFrameClosesOnceAtBoundary() {
        try (var c = new Capture(new CaptureTest.Fake(), 2, 1, 4)) {
            var frames = new ProfileFrames(c);
            frames.open(10);
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        long timer = RenderWorkDiagnostics.start();
                        try {
                            RenderWorkDiagnostics.add(FAR_PREPARE_CALLS, 1);
                            throw new IllegalStateException();
                        } finally {
                            RenderWorkDiagnostics.finish(FAR_PREPARE_NS, timer);
                        }
                    });
            frames.boundary(20);
            assertFalse(RenderWorkDiagnostics.active());
            assertEquals(1, c.work(0, FAR_PREPARE_CALLS));
            frames.boundary(21);
            assertEquals(1, c.size());
            assertEquals(1, c.work(0, FAR_PREPARE_CALLS));
        }
    }

    @Test
    void fullBudgetDeliversLastRowExactlyOnceAndNeverOpensAnother() {
        var q = new CaptureTest.Fake();
        try (var c = new Capture(q, 120_000, 1, 1)) {
            var frames = new ProfileFrames(c);
            for (int i = 0; i < 120_000; i++) {
                frames.open(i * 100L);
                RenderWorkDiagnostics.add(FAR_PREPARE_CALLS, i + 1);
                if (i == 0 || i == 119_999) {
                    for (var metric : RenderWorkDiagnostics.Metric.values()) {
                        if (metric != FAR_PREPARE_CALLS)
                            RenderWorkDiagnostics.add(metric, (i + 1L) * (metric.ordinal() + 1));
                    }
                }
                frames.frameEnd(i * 100L + 50);
                assertFalse(RenderWorkDiagnostics.active());
                assertEquals(i + 1, c.work(i, FAR_PREPARE_CALLS));
                frames.boundary((i + 1) * 100L);
            }
            assertTrue(c.full());
            assertEquals(120_000, c.size());
            assertEquals(120_000, c.work(119_999, FAR_PREPARE_CALLS));
            for (var metric : RenderWorkDiagnostics.Metric.values()) {
                if (metric == FAR_PREPARE_CALLS) continue;
                assertEquals(metric.ordinal() + 1L, c.work(0, metric), metric.name());
                assertEquals(0, c.work(60_000, metric), metric.name());
                assertEquals(
                        120_000L * (metric.ordinal() + 1), c.work(119_999, metric), metric.name());
            }
            int stamps = q.stamps;
            frames.open(12_000_000);
            RenderWorkDiagnostics.add(FAR_PREPARE_CALLS, 999);
            frames.frameEnd(12_000_050);
            frames.boundary(12_000_100);
            assertFalse(RenderWorkDiagnostics.active());
            assertEquals(stamps, q.stamps);
            assertEquals(120_000, c.work(119_999, FAR_PREPARE_CALLS));
        }
    }
}
