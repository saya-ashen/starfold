package dev.starfold.devtools;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.FarViewDiagnostics.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class FarAnalyzerTest {
    private static final Versions V = new Versions(1, 1, 1, 1, 1, 1);

    private static Snapshot snapshot(
            long nanos, long epoch, boolean near, boolean mesh, List<Event> events) {
        var tile =
                new TileState(
                        0,
                        16,
                        32,
                        near,
                        1,
                        mesh ? 1 : 0,
                        mesh,
                        mesh,
                        false,
                        false,
                        false,
                        false,
                        0,
                        0,
                        0,
                        false,
                        false,
                        false,
                        V,
                        1,
                        1);
        return new Snapshot(
                new Watch(0, 0, 16, 32, 0, epoch),
                0,
                1,
                epoch,
                new RegionState(nanos, epoch, 0, 0, true, true, true, 0, 0, List.of(tile)),
                events,
                List.of(),
                0);
    }

    private static Event event(long seq, long nanos, long epoch, Kind kind) {
        return new Event(seq, nanos, epoch, 0, 0, 16, 32, Source.RESIDENT, kind, "fixture", 1, V);
    }

    @Test
    void unavailableDoesNotBecomeFalseOrSuccess() {
        var c = new FarCapture(0);
        c.accept(
                new Snapshot(
                        new Watch(0, 0, 16, 32, 0, 1), 0, 1, 1, null, List.of(), List.of(), 0));
        var a = FarAnalyzer.analyze(c, false);
        assertEquals("INCOMPLETE", a.status());
        assertEquals("UNAVAILABLE", a.stateAvailability());
        assertEquals("NATIVE_RETREAT_UNOBSERVED", a.lifecycle());
        assertTrue(a.findings().isEmpty());
    }

    @Test
    void startingOutsideNativeWindowCannotEstablishHandoff() {
        var c = new FarCapture(0);
        for (int i = 0; i < 9; i++)
            c.accept(snapshot(i * 500_000_000L, 1, false, false, List.of()));
        assertTrue(FarAnalyzer.analyze(c, true).findings().isEmpty());
    }

    @Test
    void gapRequiresContinuousObservedExitAndStrictlyExceedsGrace() {
        var c = new FarCapture(0);
        c.accept(snapshot(0, 1, true, false, List.of()));
        for (int i = 1; i <= 5; i++)
            c.accept(snapshot(i * 500_000_000L, 1, false, false, List.of()));
        assertTrue(FarAnalyzer.analyze(c, true).findings().isEmpty()); // exactly 2s
        c.accept(snapshot(3_000_000_000L, 1, false, false, List.of()));
        var a = FarAnalyzer.analyze(c, true);
        assertEquals(1, a.findings().size());
        assertEquals("PERSISTENT_CPU_HANDOFF_GAP", a.findings().getFirst().rule());
        assertEquals("INCOMPLETE", a.status()); // geometry unavailable
    }

    @Test
    void epochChangesAndLongGapsBreakTransitionEvidence() {
        for (boolean reset : List.of(true, false)) {
            var c = new FarCapture(0);
            c.accept(snapshot(0, 1, true, false, List.of()));
            for (int i = 0; i < 8; i++)
                c.accept(
                        snapshot(
                                2_000_000_000L + i * 500_000_000L,
                                reset ? 2 : 1,
                                false,
                                false,
                                List.of()));
            assertTrue(FarAnalyzer.analyze(c, true).findings().isEmpty());
        }
    }

    @Test
    void restorationEventsMustFollowExitWithinObservedIntervalAndEpoch() {
        var c = new FarCapture(0);
        c.accept(snapshot(0, 1, true, false, List.of(event(1, 0, 1, Kind.DRAW))));
        c.accept(
                snapshot(
                        500_000_000L,
                        1,
                        false,
                        false,
                        List.of(event(2, 400_000_000L, 1, Kind.REQUEST))));
        assertEquals(
                "RETREAT_WITHOUT_RESTORATION_EVIDENCE", FarAnalyzer.analyze(c, true).lifecycle());
        c.accept(
                snapshot(
                        1_000_000_000L,
                        1,
                        false,
                        true,
                        List.of(event(3, 750_000_000L, 1, Kind.DRAW))));
        assertEquals("RETREAT_THEN_CPU_DRAW", FarAnalyzer.analyze(c, true).lifecycle());
        assertTrue(FarAnalyzer.analyze(c, true).findings().isEmpty());
    }

    @Test
    void coarseFallbackDoesNotHideMissingResidentRequest() {
        var c = new FarCapture(0);
        for (int i = 0; i <= 6; i++) {
            var tile =
                    new TileState(
                            0, 16, 32, i == 0, 0, 1, false, false, false, false, false, false, 0,
                            16, 16, false, true, false, V, -1, -1);
            c.accept(
                    new Snapshot(
                            new Watch(0, 0, 16, 32, 0, 1),
                            0,
                            1,
                            1,
                            new RegionState(
                                    i * 500_000_000L,
                                    1,
                                    0,
                                    0,
                                    true,
                                    true,
                                    false,
                                    0,
                                    0,
                                    List.of(tile)),
                            List.of(),
                            List.of(),
                            0));
        }
        var a = FarAnalyzer.analyze(c, true);
        assertEquals(
                List.of("RESIDENT_REQUEST_GAP"),
                a.findings().stream().map(FarAnalyzer.Finding::rule).toList());
    }

    @Test
    void unrelatedSourcesAndRequestsAfterReentryCannotProveRestoration() {
        var c = new FarCapture(0);
        c.accept(snapshot(0, 1, true, false, List.of()));
        c.accept(snapshot(500_000_000L, 1, false, false, List.of()));
        var parent =
                new Event(
                        1,
                        750_000_000L,
                        1,
                        0,
                        0,
                        16,
                        32,
                        Source.PARENT,
                        Kind.REQUEST,
                        "fixture",
                        16,
                        V);
        c.accept(snapshot(1_000_000_000L, 1, false, false, List.of(parent)));
        c.accept(
                snapshot(
                        1_500_000_000L,
                        1,
                        true,
                        false,
                        List.of(event(2, 1_250_000_000L, 1, Kind.REQUEST))));
        assertEquals(
                "RETREAT_WITHOUT_RESTORATION_EVIDENCE", FarAnalyzer.analyze(c, true).lifecycle());
    }

    @Test
    void ledgerDeduplicatesAndBoundsHistoryWithGapAccounting() {
        var c = new FarCapture(0);
        for (int i = 1; i <= 2200; i++) {
            var s = snapshot(i, 1, false, false, List.of(event(i + 5, i, 1, Kind.REQUEST)));
            c.accept(s);
            c.accept(s);
        }
        assertEquals(FarCapture.MAX_EVENTS, c.events().size());
        assertEquals(FarCapture.MAX_STATES, c.states().size());
        assertEquals(2200 - FarCapture.MAX_STATES, c.discardedStates);
        assertEquals(2200 - FarCapture.MAX_EVENTS, c.discardedEvents);
        assertEquals(0, c.missingEvents); // first global sequence is not assumed to be one
        c.accept(snapshot(2201, 1, false, false, List.of(event(2208, 2201, 1, Kind.REQUEST))));
        assertEquals(2, c.missingEvents);
        assertTrue(c.expired(FarCapture.MAX_NANOS));
    }
}
