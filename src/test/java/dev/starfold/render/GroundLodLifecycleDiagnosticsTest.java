package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.RenderWorkDiagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GroundLodLifecycleDiagnosticsTest {
    private final List<long[]> frames = new ArrayList<>();

    @AfterEach
    void unregister() {
        RenderWorkDiagnostics.register(null);
    }

    private void startCapture() {
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        RenderWorkDiagnostics.beginFrame();
    }

    private void assertMetric(int frame, RenderWorkDiagnostics.Metric metric, long expected) {
        assertEquals(expected, frames.get(frame)[metric.ordinal()], metric.name());
    }

    @Test
    void enqueuedRequestReasonsOverlapButFirstIsNotAStepChange() {
        startCapture();
        GroundLodLifecycleDiagnostics.request(0, 0, 4, true, true, true);
        GroundLodLifecycleDiagnostics.request(98_303, 4, 2, true, true, true);
        GroundLodLifecycleDiagnostics.request(7, 2, 2, false, false, true);
        RenderWorkDiagnostics.endFrame();
        assertMetric(0, FAR_REQUEST_FIRST, 1);
        assertMetric(0, FAR_REQUEST_STEP, 1);
        assertMetric(0, FAR_REQUEST_SURFACE, 2);
        assertMetric(0, FAR_REQUEST_MIP, 2);
        assertMetric(0, FAR_REQUEST_DIRTY, 3);
        assertMetric(0, FAR_PUBLICATION_FIRST, 0);
        assertMetric(0, FAR_PUBLICATION_REPEAT_KEY, 0);
    }

    @Test
    void publicationComparesLongMipsAndSurfaceInputsIndependentlyOfDirty() {
        startCapture();
        long mip = 1L << 40;
        GroundLodLifecycleDiagnostics.publication(0, 0, 4, 0, mip, false, false);
        GroundLodLifecycleDiagnostics.publication(1, 4, 4, mip, mip, true, true);
        GroundLodLifecycleDiagnostics.publication(2, 4, 2, mip, mip, true, false);
        GroundLodLifecycleDiagnostics.publication(3, 4, 4, mip, mip + (1L << 32), true, false);
        GroundLodLifecycleDiagnostics.publication(98_303, 4, 4, mip, mip, false, false);
        RenderWorkDiagnostics.endFrame();
        assertMetric(0, FAR_PUBLICATION_FIRST, 1);
        assertMetric(0, FAR_PUBLICATION_REPLACEMENT, 4);
        assertMetric(0, FAR_PUBLICATION_SAME_LOCAL_INPUTS, 1);
        assertMetric(0, FAR_PUBLICATION_REPEAT_KEY, 0);
        assertMetric(0, FAR_REQUEST_DIRTY, 0);
    }

    @Test
    void repeatIdentitySurvivesFramesAndRetirementButNotNewCaptures() {
        startCapture();
        GroundLodLifecycleDiagnostics.publication(98_303, 0, 4, 0, 1, false, false);
        GroundLodLifecycleDiagnostics.publication(98_303, 4, 2, 1, 1, true, false);
        RenderWorkDiagnostics.endFrame();
        // An inactive call must not consume the first publication for another key.
        GroundLodLifecycleDiagnostics.publication(7, 0, 4, 0, 1, false, false);
        RenderWorkDiagnostics.beginFrame();
        // Resident absent again after retirement, but identity still repeats in this capture.
        GroundLodLifecycleDiagnostics.publication(98_303, 0, 4, 0, 1, false, false);
        GroundLodLifecycleDiagnostics.publication(7, 0, 4, 0, 1, false, false);
        RenderWorkDiagnostics.endFrame();
        startCapture();
        GroundLodLifecycleDiagnostics.publication(98_303, 4, 4, 1, 1, true, false);
        RenderWorkDiagnostics.endFrame();
        assertMetric(0, FAR_PUBLICATION_REPEAT_KEY, 1);
        assertMetric(1, FAR_PUBLICATION_REPEAT_KEY, 1);
        assertMetric(1, FAR_PUBLICATION_FIRST, 2);
        assertMetric(2, FAR_PUBLICATION_REPEAT_KEY, 0);
        assertMetric(2, FAR_PUBLICATION_REPLACEMENT, 1);
    }

    @Test
    void inactiveAndInvalidKeysAreIgnoredWithoutThrowingOrAliasing() {
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        GroundLodLifecycleDiagnostics.request(0, 0, 4, true, true, true);
        GroundLodLifecycleDiagnostics.publication(0, 0, 4, 0, 1, false, false);
        RenderWorkDiagnostics.beginFrame();
        for (long key : new long[] {-1, 98_304, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 32}) {
            GroundLodLifecycleDiagnostics.request(key, 0, 4, true, true, true);
            GroundLodLifecycleDiagnostics.publication(key, 0, 4, 0, 1, false, false);
        }
        RenderWorkDiagnostics.endFrame();
        assertArrayEquals(new long[RenderWorkDiagnostics.Metric.values().length], frames.get(0));
        RenderWorkDiagnostics.beginFrame();
        GroundLodLifecycleDiagnostics.publication(0, 0, 4, 0, 1, false, false);
        RenderWorkDiagnostics.endFrame();
        assertMetric(1, FAR_PUBLICATION_FIRST, 1);
        assertMetric(1, FAR_PUBLICATION_REPEAT_KEY, 0);
    }
}
