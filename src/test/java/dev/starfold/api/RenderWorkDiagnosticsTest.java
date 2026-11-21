package dev.starfold.api;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RenderWorkDiagnosticsTest {
    @AfterEach
    void unregister() {
        RenderWorkDiagnostics.register(null);
        RenderWorkDiagnostics.clockForTests(null);
    }

    @Test
    void registrationIsInactiveUntilFrameAndBoundariesNeverBleed() {
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        assertFalse(RenderWorkDiagnostics.active());
        assertEquals(0, RenderWorkDiagnostics.start());
        RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 99);
        RenderWorkDiagnostics.beginFrame();
        assertTrue(RenderWorkDiagnostics.active());
        RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 3);
        RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 4);
        RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, -1);
        RenderWorkDiagnostics.endFrame();
        assertFalse(RenderWorkDiagnostics.active());
        RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 99);
        RenderWorkDiagnostics.endFrame();
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.endFrame();
        assertEquals(2, frames.size());
        assertEquals(7, frames.get(0)[NATIVE_DIRTY_COLUMNS.ordinal()]);
        assertArrayEquals(new long[metricCount()], frames.get(1));
    }

    @Test
    void readyByteGaugesReplaceRefreshesAndCanReturnToZero() {
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_RESIDENT_BYTES, 99);
        RenderWorkDiagnostics.beginFrame();
        for (int refresh = 0; refresh < 4; refresh++)
            RenderWorkDiagnostics.gauge(FAR_LOD_READY_RESIDENT_BYTES, 4096);
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_COVER_BYTES, 2048);
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_COVER_BYTES, 0);
        RenderWorkDiagnostics.gauge(FAR_LOD_READY_RESIDENT_BYTES, -1);
        RenderWorkDiagnostics.endFrame();
        assertEquals(4096, frames.getFirst()[FAR_LOD_READY_RESIDENT_BYTES.ordinal()]);
        assertEquals(0, frames.getFirst()[FAR_LOD_READY_COVER_BYTES.ordinal()]);
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.endFrame();
        assertArrayEquals(new long[metricCount()], frames.getLast());
    }

    @Test
    void abandonedFrameAndStaleTimersDoNotReachNextProfile() {
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.add(FAR_REASON_VIEW, 1);
        long stale = RenderWorkDiagnostics.start();
        RenderWorkDiagnostics.register(null);
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, stale);
        RenderWorkDiagnostics.beginFrame();
        assertFalse(RenderWorkDiagnostics.active());
        assertEquals(0, RenderWorkDiagnostics.start());
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, stale);
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, 0);
        RenderWorkDiagnostics.endFrame();
        assertEquals(1, frames.size());
        assertArrayEquals(new long[metricCount()], frames.get(0));
    }

    @Test
    void beginResetsUnclosedFrameAndCpuTimerRecordsElapsed() {
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        long stale = RenderWorkDiagnostics.start();
        RenderWorkDiagnostics.add(FAR_CACHE_REUSES, 7);
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.finish(FAR_COMMAND_ASSEMBLY_NS, stale);
        long[] clock = {100};
        RenderWorkDiagnostics.clockForTests(() -> clock[0]);
        long started = RenderWorkDiagnostics.start();
        clock[0] = 107;
        assertNotEquals(0, started);
        RenderWorkDiagnostics.finish(NATIVE_READY_NS, started);
        RenderWorkDiagnostics.endFrame();
        assertEquals(0, frames.get(0)[FAR_CACHE_REUSES.ordinal()]);
        assertEquals(0, frames.get(0)[FAR_COMMAND_ASSEMBLY_NS.ordinal()]);
        assertEquals(7, frames.get(0)[NATIVE_READY_NS.ordinal()]);
    }

    @Test
    void tiedClockFramesAndRegistrationRejectStaleHandlesWithoutClockReads() {
        long[] clock = {0};
        int[] reads = {0};
        RenderWorkDiagnostics.clockForTests(
                () -> {
                    reads[0]++;
                    return clock[0];
                });
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        assertEquals(0, RenderWorkDiagnostics.start());
        assertEquals(0, reads[0]);
        RenderWorkDiagnostics.beginFrame();
        long generation = RenderWorkDiagnostics.captureGeneration();
        long stale = RenderWorkDiagnostics.start();
        assertNotEquals(0, stale);
        RenderWorkDiagnostics.endFrame();
        assertEquals(0, RenderWorkDiagnostics.captureGeneration());
        RenderWorkDiagnostics.beginFrame();
        assertEquals(generation, RenderWorkDiagnostics.captureGeneration());
        long current = RenderWorkDiagnostics.start();
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, stale);
        assertEquals(2, reads[0]);
        clock[0] = 9;
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, current);
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, current);
        assertEquals(3, reads[0]);
        RenderWorkDiagnostics.endFrame();
        assertEquals(9, frames.get(1)[NATIVE_WATER_NS.ordinal()]);
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        assertNotEquals(generation, RenderWorkDiagnostics.captureGeneration());
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, current);
        RenderWorkDiagnostics.endFrame();
        assertEquals(0, frames.get(2)[NATIVE_WATER_NS.ordinal()]);
    }

    @Test
    void negativeOriginNestedOutOfOrderTimersAndExceptionalFinallyUseActualElapsed() {
        long[] clock = {-10};
        RenderWorkDiagnostics.clockForTests(() -> clock[0]);
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        long outer = RenderWorkDiagnostics.start();
        clock[0] = -5;
        long inner = RenderWorkDiagnostics.start();
        clock[0] = 0;
        RenderWorkDiagnostics.finish(NATIVE_WATER_NS, outer);
        clock[0] = 3;
        assertThrows(
                IllegalStateException.class,
                () -> {
                    try {
                        throw new IllegalStateException();
                    } finally {
                        RenderWorkDiagnostics.finish(NATIVE_READY_NS, inner);
                    }
                });
        RenderWorkDiagnostics.endFrame();
        assertEquals(10, frames.get(0)[NATIVE_WATER_NS.ordinal()]);
        assertEquals(8, frames.get(0)[NATIVE_READY_NS.ordinal()]);
    }

    @Test
    void boundedConcurrentHandlesReuseSlotsWithoutReusingIdentity() {
        long[] clock = {0};
        RenderWorkDiagnostics.clockForTests(() -> clock[0]);
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        long[] handles = new long[256];
        for (int i = 0; i < handles.length; i++) {
            handles[i] = RenderWorkDiagnostics.start();
            assertNotEquals(0, handles[i]);
        }
        assertEquals(0, RenderWorkDiagnostics.start());
        clock[0] = 5;
        RenderWorkDiagnostics.finish(NATIVE_READY_NS, handles[100]);
        long replacement = RenderWorkDiagnostics.start();
        assertNotEquals(0, replacement);
        assertNotEquals(handles[100], replacement);
        clock[0] = 8;
        RenderWorkDiagnostics.finish(NATIVE_READY_NS, handles[100]);
        RenderWorkDiagnostics.finish(NATIVE_READY_NS, replacement);
        RenderWorkDiagnostics.endFrame();
        assertEquals(8, frames.get(0)[NATIVE_READY_NS.ordinal()]);
    }

    @Test
    void callbackFailureStillClosesAndClears() {
        RenderWorkDiagnostics.register(
                values -> {
                    throw new IllegalStateException();
                });
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.add(NATIVE_DIRTY_COLUMNS, 5);
        assertThrows(IllegalStateException.class, RenderWorkDiagnostics::endFrame);
        assertFalse(RenderWorkDiagnostics.active());
        RenderWorkDiagnostics.endFrame();
        List<long[]> frames = new ArrayList<>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
        RenderWorkDiagnostics.endFrame();
        assertArrayEquals(new long[metricCount()], frames.get(0));
    }

    private static int metricCount() {
        return RenderWorkDiagnostics.Metric.values().length;
    }
}
