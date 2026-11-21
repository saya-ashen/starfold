package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.api.FarViewDiagnostics.Kind;
import dev.starfold.api.FarViewDiagnostics.Source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FarViewBoundaryTest {
    @AfterEach
    void disable() {
        FarViewDiagnostics.stop();
    }

    @Test
    void resettingRendererRejectsLateCallbacksFromPreviousWatchEpoch() {
        FarViewDiagnostics.observeContext(7, 10, true, "fixture");
        var old = FarViewDiagnostics.watch(7, 0, 37, 48, 0);
        assertEquals(32, old.x());
        FarViewDiagnostics.observeContext(7, 11, true, "world-reload");
        var fresh = FarViewDiagnostics.snapshot();
        assertNotEquals(old.epoch(), fresh.epoch());
        FarViewDiagnostics.observeEvent(
                old.epoch(), 0, 32, 48, Source.RESIDENT, Kind.UPLOAD, "late-worker", 1, null);
        assertFalse(
                FarViewDiagnostics.eventsSince(0).stream()
                        .anyMatch(event -> event.reason().equals("late-worker")));
        assertNull(FarViewDiagnostics.currentWatch(old.epoch()));
    }

    @Test
    void eventReadersAreIndependentAndOverflowIsExplicit() {
        FarViewDiagnostics.observeContext(8, 12, true, "fixture");
        var watch = FarViewDiagnostics.watch(8, 1, 64, 80, 0);
        for (int n = 0; n < FarViewDiagnostics.MAX_EVENTS + 9; n++)
            FarViewDiagnostics.observeEvent(
                    watch.epoch(),
                    1,
                    64,
                    80,
                    Source.RESIDENT,
                    Kind.REQUEST,
                    "fixture-" + n,
                    1,
                    null);
        var first = FarViewDiagnostics.eventsSince(0);
        assertEquals(FarViewDiagnostics.MAX_EVENTS, first.size());
        assertEquals(first, FarViewDiagnostics.eventsSince(0));
        assertEquals(9, FarViewDiagnostics.snapshot().droppedEvents());
        assertEquals(
                first.subList(1, first.size()),
                FarViewDiagnostics.eventsSince(first.getFirst().sequence()));
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }

    @Test
    void encodingEvidenceIsCopiedBoundedAndRejectsStaleEpoch() {
        FarViewDiagnostics.observeContext(6, 15, true, "fixture");
        var watch = FarViewDiagnostics.watch(6, 0, 0, 0, 0);
        var mutable = new java.util.ArrayList<Object>(java.util.List.of("original"));
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("samples", mutable);
        FarViewDiagnostics.record(watch.epoch(), 6, 0, 0, 0, "PACKED_CPU", fields);
        mutable.set(0, "mutated");
        assertEquals(
                java.util.List.of("original"),
                FarViewDiagnostics.snapshot().records().getFirst().fields().get("samples"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> FarViewDiagnostics.snapshot().records().getFirst().fields().clear());
        for (int n = 0; n < FarViewDiagnostics.MAX_RECORDS; n++)
            FarViewDiagnostics.record(watch.epoch(), 6, 0, 0, 0, "PACKED_CPU", java.util.Map.of());
        assertEquals(
                FarViewDiagnostics.MAX_RECORDS, FarViewDiagnostics.snapshot().records().size());
        assertEquals(1, FarViewDiagnostics.snapshot().droppedRecords());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FarViewDiagnostics.record(
                                watch.epoch(),
                                6,
                                0,
                                0,
                                0,
                                "PACKED_CPU",
                                java.util.Map.of("unsafe", new Object())));
        FarViewDiagnostics.watch(6, 0, 0, 0, 0);
        FarViewDiagnostics.record(watch.epoch(), 6, 0, 0, 0, "PACKED_CPU", fields);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }

    @Test
    void oneShotDrawRecordsSurviveCpuOverflowWithinTheSameBoundedChannel() {
        FarViewDiagnostics.observeContext(9, 13, true, "fixture");
        var w = FarViewDiagnostics.watch(9, 5, 992, 976, 0);
        for (int n = 0; n < FarViewDiagnostics.MAX_DRAW_RECORDS + 1; n++)
            FarViewDiagnostics.record(
                    w.epoch(),
                    9,
                    5,
                    992,
                    976,
                    FarViewDiagnostics.DRAW_GPU_STATE,
                    java.util.Map.of("slot", n));
        for (int n = 0; n < FarViewDiagnostics.MAX_RECORDS * 3; n++)
            FarViewDiagnostics.record(
                    w.epoch(), 9, 5, 992, 976, "PACKED_CPU", java.util.Map.of("sample", n));
        var snapshot = FarViewDiagnostics.snapshot();
        assertEquals(FarViewDiagnostics.MAX_RECORDS, snapshot.records().size());
        var draws =
                snapshot.records().stream()
                        .filter(r -> r.stage().equals(FarViewDiagnostics.DRAW_GPU_STATE))
                        .toList();
        assertEquals(FarViewDiagnostics.MAX_DRAW_RECORDS, draws.size());
        assertEquals(0, draws.getFirst().fields().get("slot"));
        assertEquals(FarViewDiagnostics.MAX_DRAW_RECORDS - 1, draws.getLast().fields().get("slot"));
        assertEquals(
                FarViewDiagnostics.MAX_RECORDS * 2 + FarViewDiagnostics.MAX_DRAW_RECORDS + 1,
                snapshot.droppedRecords());
        assertEquals(
                FarViewDiagnostics.MAX_RECORDS * 3 - 1,
                snapshot.records().getLast().fields().get("sample"));
        var fresh = FarViewDiagnostics.watch(9, 5, 992, 976, 0);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
        FarViewDiagnostics.record(
                w.epoch(), 9, 5, 992, 976, FarViewDiagnostics.DRAW_GPU_STATE, java.util.Map.of());
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
        FarViewDiagnostics.record(
                fresh.epoch(),
                9,
                5,
                992,
                976,
                FarViewDiagnostics.DRAW_GPU_STATE,
                java.util.Map.of());
        assertEquals(1, FarViewDiagnostics.snapshot().records().size());
    }

    @Test
    void wrongPlanetAndOutsideTileCannotAddEvidence() {
        FarViewDiagnostics.observeContext(9, 13, true, "fixture");
        var watch = FarViewDiagnostics.watch(9, 0, 0, 0, 0);
        FarViewDiagnostics.observeEvent(
                watch.epoch(), 0, 16, 0, Source.RESIDENT, Kind.DRAW, "outside", 1, null);
        assertTrue(FarViewDiagnostics.snapshot().events().isEmpty());
        FarViewDiagnostics.observeContext(10, 14, false, "other-planet");
        assertEquals(0, FarViewDiagnostics.epoch(9));
        assertFalse(FarViewDiagnostics.matches(0, 0, 0));
        FarViewDiagnostics.stop();
        assertFalse(FarViewDiagnostics.active());
        assertNull(FarViewDiagnostics.snapshot().region());
        assertTrue(FarViewDiagnostics.snapshot().events().isEmpty());
    }
}
