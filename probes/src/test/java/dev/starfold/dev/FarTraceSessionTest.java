package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.FarViewDiagnostics.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class FarTraceSessionTest {
    private static final Watch WATCH = new Watch(0, 0, 1024, 1024, 1, 7);
    private static final Versions VERSIONS = new Versions(1, 2, 3, 4, 5, 6);

    private static TileState tile(int x, boolean nativeWindow, boolean resident) {
        return new TileState(
                0,
                x,
                1024,
                nativeWindow,
                1,
                1,
                resident,
                resident,
                false,
                false,
                false,
                false,
                0,
                16,
                16,
                true,
                true,
                false,
                VERSIONS,
                3,
                1);
    }

    private static Snapshot snapshot(
            long time, long epoch, long dropped, List<Event> events, TileState... tiles) {
        return new Snapshot(
                WATCH,
                0,
                1,
                epoch,
                new RegionState(time, epoch, 0, 0, true, true, true, 0, 0, List.of(tiles)),
                events,
                List.of(),
                dropped);
    }

    private static FarTraceSession.Pose pose(double x, double y, double yaw) {
        return new FarTraceSession.Pose(0, 0, x, y, 1032, yaw);
    }

    private static void phase(
            FarTraceSession s,
            String label,
            long time,
            boolean window,
            boolean resident,
            FarTraceSession.Pose pose) {
        s.mark(label);
        s.tick(snapshot(time, 7, 0, List.of(), tile(1024, window, resident)), pose);
    }

    private static FarTraceSession route(boolean move, boolean resident) {
        var s = new FarTraceSession(WATCH);
        phase(s, "baseline", 1, false, false, pose(1600, 300, 0));
        phase(s, "approach", 2, true, false, pose(move ? 1032 : 1600, 300, 0));
        phase(s, "retreat", 3, false, false, pose(1600, 300, 0));
        s.mark("height");
        s.tick(
                snapshot(
                        4,
                        7,
                        0,
                        resident ? List.of(publication(1, 4, VERSIONS)) : List.of(),
                        tile(1024, false, resident)),
                pose(1600, move ? 600 : 300, 0));
        phase(s, "turn", 5, false, resident, pose(1600, move ? 600 : 300, move ? 120 : 0));
        return s;
    }

    private static String status(FarTraceSession s, String name) {
        return s.check().assertions().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow()
                .status();
    }

    @Test
    void sameTileLifecycleAndObservedRouteCanPassWithResidentRestoration() {
        var s = route(true, true);
        assertEquals("PASS", s.check().status());
        assertEquals(5, s.report("check").samples().size());
        assertEquals("check", s.report("check").termination());
    }

    @Test
    void phaseLabelsWithoutMotionNeverPass() {
        var s = route(false, true);
        assertEquals("INCOMPLETE", s.check().status());
        for (var check : List.of("approachMotion", "retreatMotion", "heightMotion", "turnMotion"))
            assertEquals("INCOMPLETE", status(s, check));
    }

    @Test
    void enteringOneTileAndExitingAnotherCannotEstablishLifecycle() {
        var s = new FarTraceSession(WATCH);
        s.tick(
                snapshot(1, 7, 0, List.of(), tile(1024, false, false), tile(1040, true, false)),
                null);
        s.tick(
                snapshot(2, 7, 0, List.of(), tile(1024, true, false), tile(1040, false, true)),
                null);
        assertEquals("PASS", status(s, "nativeEnter"));
        assertEquals("INCOMPLETE", status(s, "nativeExit"));
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void startingInsideNativeWindowDoesNotProveEnter() {
        var s = new FarTraceSession(WATCH);
        s.tick(snapshot(1, 7, 0, List.of(), tile(1024, true, false)), null);
        s.tick(snapshot(2, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "nativeEnter"));
        assertEquals("INCOMPLETE", status(s, "nativeExit"));
    }

    @Test
    void motionAndPublicationWithoutNativeTransitionRemainIncomplete() {
        var reference = route(true, true);
        var s = new FarTraceSession(WATCH);
        for (var sample : reference.samples) {
            s.mark(sample.phase());
            long time = sample.state().nanoTime();
            s.collect(
                    snapshot(
                            time,
                            7,
                            0,
                            time == 4 ? List.of(publication(1, 4, VERSIONS)) : List.of(),
                            tile(1024, false, sample.state().tiles().getFirst().meshPresent())),
                    sample.pose());
        }
        assertEquals("PASS", status(s, "approachMotion"));
        assertEquals("PASS", status(s, "turnMotion"));
        assertEquals("INCOMPLETE", status(s, "nativeEnter"));
        assertEquals("INCOMPLETE", status(s, "nativeExit"));
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        assertEquals("INCOMPLETE", s.check().status());
    }

    @Test
    void nativeExitWithoutRestorationIsIncomplete() {
        var s = route(true, false);
        assertEquals("PASS", status(s, "nativeExit"));
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void residentRequestMustFollowTheExitAndMatchTileButNeverProvesCompletion() {
        for (var event : List.of(event(1, 2, 1024), event(1, 6, 1040))) {
            var s = route(true, false);
            s.collect(snapshot(6, 7, 0, List.of(event), tile(1024, false, false)), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
            assertFalse(s.check().residentRestoreAttempted());
        }
        var s = route(true, false);
        s.collect(snapshot(6, 7, 0, List.of(event(1, 6, 1024)), tile(1024, false, false)), null);
        assertTrue(s.check().residentRestoreAttempted());
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        assertEquals("INCOMPLETE", s.check().status());
    }

    @Test
    void residencyOrRequestAfterReentryDoesNotProveRestorationOutsideNativeWindow() {
        var s = route(true, false);
        s.collect(snapshot(6, 7, 0, List.of(event(1, 6, 1024)), tile(1024, true, true)), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void queueAndRunningJobAreAttemptsWithoutCompletion() {
        for (boolean queued : List.of(true, false)) {
            var s = route(true, false);
            var t = new Resident();
            t.mesh = false;
            t.queued = queued;
            t.jobs = queued ? 0 : 1;
            s.collect(snapshot(6, 7, 0, List.of(), t.tile()), null);
            assertTrue(s.check().residentRestoreAttempted());
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
        }
    }

    @Test
    void installationMustSettleBeforeRecoveryCompletes() {
        var s = route(true, false);
        var t = new Resident();
        t.fading = true;
        t.previous = true;
        // lodComplete can still describe the previous mesh while its replacement fades.
        s.collect(snapshot(6, 7, 0, List.of(publication(1, 6, VERSIONS)), t.tile()), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        t.fading = false;
        s.collect(snapshot(7, 7, 0, List.of(), t.tile()), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        t.previous = false;
        t.complete = false;
        s.collect(snapshot(8, 7, 0, List.of(), t.tile()), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        t.complete = true;
        s.collect(snapshot(9, 7, 0, List.of(), t.tile()), null);
        assertEquals("PASS", s.check().status());
    }

    @Test
    void uploadFadeOrOwnershipPublicationCannotSubstituteForInstallation() {
        for (var event :
                List.of(
                        evidence(1, 6, Source.RESIDENT, Kind.UPLOAD, "resident-upload-complete"),
                        evidence(1, 6, Source.RESIDENT, Kind.PUBLICATION, "resident-fade-complete"),
                        evidence(
                                1,
                                6,
                                Source.OWNERSHIP,
                                Kind.PUBLICATION,
                                "resident-installed-fading"))) {
            var s = route(true, false);
            s.collect(snapshot(6, 7, 0, List.of(event), tile(1024, false, true)), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
        }
    }

    @Test
    void completeMeshAppearingWithoutPublicationDoesNotProveRecovery() {
        var s = route(true, false);
        s.collect(snapshot(6, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void installationMustMatchExitEpochTileTimeAndMeshInputs() {
        for (var event :
                List.of(
                        publication(1, 2, VERSIONS),
                        publication(1, 7, VERSIONS),
                        publication(1, 6, new Versions(99, 2, 3, 4, 5, 6)),
                        publication(1, 6, new Versions(1, 2, 99, 4, 5, 6)),
                        new Event(
                                1,
                                6,
                                8,
                                0,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.PUBLICATION,
                                "resident-installed-fading",
                                1,
                                VERSIONS),
                        new Event(
                                1,
                                6,
                                7,
                                1,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.PUBLICATION,
                                "resident-installed-fading",
                                1,
                                VERSIONS),
                        new Event(
                                1,
                                6,
                                7,
                                0,
                                0,
                                1040,
                                1024,
                                Source.RESIDENT,
                                Kind.PUBLICATION,
                                "resident-installed-fading",
                                1,
                                VERSIONS),
                        new Event(
                                1,
                                6,
                                7,
                                0,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.PUBLICATION,
                                "resident-installed-fading",
                                2,
                                VERSIONS))) {
            var s = route(true, false);
            s.collect(snapshot(6, 7, 0, List.of(event), tile(1024, false, true)), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"), event.toString());
        }
    }

    @Test
    void invalidResidentOrUnknownSelectionCannotCompletePublication() {
        for (int mode = 0; mode < 7; mode++) {
            var s = route(true, false);
            var t = new Resident();
            switch (mode) {
                case 0 -> t.retiring = true;
                case 1 -> t.selected = 0;
                case 2 -> t.selected = -1;
                case 3 -> t.jobs = 1;
                case 4 -> t.versions = new Versions(1, 2, 99, 4, 5, 6);
                case 5 -> t.versions = new Versions(99, 2, 3, 4, 5, 6);
                case 6 -> t.requested = 0;
                default -> throw new AssertionError();
            }
            s.collect(snapshot(6, 7, 0, List.of(publication(1, 6, VERSIONS)), t.tile()), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"), "mode " + mode);
        }
    }

    @Test
    void completeResidentCanSurviveNativeWithoutRebuildOrAttempt() {
        var s = new FarTraceSession(WATCH);
        phase(s, "baseline", 1, false, true, pose(1600, 300, 0));
        phase(s, "approach", 2, true, true, pose(1032, 300, 0));
        phase(s, "retreat", 3, false, true, pose(1600, 300, 0));
        phase(s, "height", 4, false, true, pose(1600, 600, 0));
        phase(s, "turn", 5, false, true, pose(1600, 600, 120));
        assertEquals("PASS", s.check().status());
        assertFalse(s.check().residentRestoreAttempted());
        assertTrue(s.events.isEmpty());
    }

    @Test
    void nativeSelectionNeedNotBePositiveToRetainACompleteResident() {
        for (int selected : List.of(0, -1)) {
            var s = new FarTraceSession(WATCH);
            s.collect(snapshot(1, 7, 0, List.of(), tile(1024, false, true)), null);
            var t = new Resident();
            t.nativeWindow = true;
            t.selected = selected;
            s.collect(snapshot(2, 7, 0, List.of(), t.tile()), null);
            s.collect(snapshot(3, 7, 0, List.of(), tile(1024, false, true)), null);
            assertEquals("PASS", status(s, "residentRestore"));
        }
    }

    @Test
    void cancelledDifferentStepJobDoesNotRequireRebuildingRetainedResident() {
        for (boolean whileNative : List.of(false, true)) {
            var s = new FarTraceSession(WATCH);
            var t = new Resident();
            t.requested = t.selected = 2;
            s.collect(snapshot(1, 7, 0, List.of(), t.tile()), null);
            t.nativeWindow = true;
            s.collect(snapshot(2, 7, 0, List.of(), t.tile()), null);
            t.nativeWindow = whileNative;
            s.collect(snapshot(3, 7, 0, List.of(), t.tile()), null);
            if (!whileNative) assertEquals("PASS", status(s, "residentRestore"));
            t.requested = t.selected = 1;
            t.jobs = 1;
            s.collect(snapshot(4, 7, 0, List.of(), t.tile()), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
            t.requested = t.selected = 2;
            t.jobs = 0;
            t.nativeWindow = false;
            s.collect(
                    snapshot(
                            5,
                            7,
                            0,
                            List.of(
                                    evidence(
                                            1,
                                            5,
                                            Source.RESIDENT,
                                            Kind.CANCEL,
                                            "selection-coarsened")),
                            t.tile()),
                    null);
            assertEquals("PASS", status(s, "residentRestore"));
        }
    }

    @Test
    void globalVersionChangesDoNotForceAnUnnecessaryResidentRebuild() {
        var s = retainedThroughNative();
        var t = new Resident();
        t.selected = 2; // A finer existing resident is usable; selectedStep is last evaluated.
        t.versions = new Versions(1, 200, 3, 400, 500, 600);
        s.collect(snapshot(3, 7, 0, List.of(), t.tile()), null);
        assertEquals("PASS", status(s, "residentRestore"));
    }

    @Test
    void sameColumnsDecisionAllowsRetainedMeshWithDifferentSurfaceRevision() {
        var s = retainedThroughNative();
        var t = new Resident();
        t.versions = new Versions(99, 200, 3, 400, 500, 600);
        var unchanged =
                new Event(
                        1,
                        3,
                        7,
                        0,
                        0,
                        1024,
                        1024,
                        Source.RESIDENT,
                        Kind.SKIP,
                        "inputs-unchanged",
                        1,
                        t.versions);
        s.collect(snapshot(3, 7, 0, List.of(unchanged), t.tile()), null);
        assertEquals("PASS", status(s, "residentRestore"));
        s.collect(snapshot(4, 7, 0, List.of(), t.tile()), null);
        assertEquals("PASS", status(s, "residentRestore"));
    }

    @Test
    void delayedSameColumnsDecisionCanValidateContinuouslyRetainedResident() {
        var s = retainedThroughNative();
        var t = new Resident();
        t.versions = new Versions(99, 200, 3, 400, 500, 600);
        s.collect(snapshot(3, 7, 0, List.of(), t.tile()), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        var unchanged =
                new Event(
                        1,
                        4,
                        7,
                        0,
                        0,
                        1024,
                        1024,
                        Source.RESIDENT,
                        Kind.SKIP,
                        "inputs-unchanged",
                        1,
                        t.versions);
        s.collect(snapshot(4, 7, 0, List.of(unchanged), t.tile()), null);
        assertEquals("PASS", status(s, "residentRestore"));
    }

    @Test
    void sameColumnsProofMustMatchCurrentInputsAndPrecedeTheSample() {
        var current = new Versions(99, 2, 3, 4, 5, 6);
        for (var event :
                List.of(
                        new Event(
                                1,
                                3,
                                7,
                                0,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.SKIP,
                                "inputs-unchanged",
                                1,
                                VERSIONS),
                        new Event(
                                1,
                                3,
                                7,
                                0,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.SKIP,
                                "inputs-unchanged",
                                1,
                                new Versions(99, 2, 4, 4, 5, 6)),
                        new Event(
                                1,
                                3,
                                7,
                                0,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.SKIP,
                                "inputs-unchanged",
                                2,
                                current),
                        new Event(
                                1,
                                4,
                                7,
                                0,
                                0,
                                1024,
                                1024,
                                Source.RESIDENT,
                                Kind.SKIP,
                                "inputs-unchanged",
                                1,
                                current))) {
            var s = retainedThroughNative();
            var t = new Resident();
            t.versions = current;
            s.collect(snapshot(3, 7, 0, List.of(event), t.tile()), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"), event.toString());
        }
    }

    @Test
    void replacementCannotBorrowAnOlderSameColumnsDecision() {
        var s = retainedThroughNative();
        var t = new Resident();
        t.versions = new Versions(99, 2, 3, 4, 5, 6);
        var unchanged =
                new Event(
                        1,
                        3,
                        7,
                        0,
                        0,
                        1024,
                        1024,
                        Source.RESIDENT,
                        Kind.SKIP,
                        "inputs-unchanged",
                        1,
                        t.versions);
        s.collect(snapshot(3, 7, 0, List.of(unchanged), t.tile()), null);
        assertEquals("PASS", status(s, "residentRestore"));
        s.collect(snapshot(4, 7, 0, List.of(publication(2, 4, VERSIONS)), t.tile()), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void retainedResidentCannotChangeOrRetireUnobservedBetweenSamples() {
        for (var event :
                List.of(
                        publication(1, 3, VERSIONS),
                        evidence(1, 3, Source.RESIDENT, Kind.RETIRE, "released"))) {
            var s = retainedThroughNative();
            // Exit is only sampled at 4: the event at 3 cannot prove post-exit installation.
            s.collect(snapshot(4, 7, 0, List.of(event), tile(1024, false, true)), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
        }
        var s = retainedThroughNative();
        var t = new Resident();
        t.meshSurface = 99;
        t.versions = new Versions(99, 2, 3, 4, 5, 6);
        s.collect(snapshot(3, 7, 0, List.of(), t.tile()), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void laterLossRetirementOrNativeReentryRevokesCompletedRecovery() {
        for (int mode = 0; mode < 4; mode++) {
            var s = route(true, true);
            assertEquals("PASS", status(s, "residentRestore"));
            var t = new Resident();
            switch (mode) {
                case 0 -> t.mesh = false;
                case 1 -> t.retiring = true;
                case 2 -> t.nativeWindow = true;
                case 3 -> t.complete = false;
                default -> throw new AssertionError();
            }
            s.collect(snapshot(6, 7, 0, List.of(), t.tile()), null);
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
        }
    }

    @Test
    void lifecycleEventsNewerThanPeriodicStateImmediatelyRevokeCompletion() {
        for (var event :
                List.of(
                        publication(2, 6, VERSIONS),
                        evidence(2, 6, Source.RESIDENT, Kind.RETIRE, "released"))) {
            var s = route(true, true);
            s.collect(snapshot(5, 7, 0, List.of(event), tile(1024, false, true)), null);
            assertEquals(5, s.samples.size());
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
        }
        var s = route(true, true);
        s.collect(
                snapshot(5, 7, 0, List.of(publication(2, 6, VERSIONS)), tile(1024, false, true)),
                null);
        s.collect(snapshot(7, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("PASS", status(s, "residentRestore"));
    }

    @Test
    void requestNewerThanStateIsReportedWithoutClaimingCompletion() {
        var s = route(true, false);
        s.collect(snapshot(5, 7, 0, List.of(event(1, 6, 1024)), tile(1024, false, false)), null);
        assertTrue(s.check().residentRestoreAttempted());
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void missingMeshCannotReuseAnOlderInstallationWhenItReappears() {
        var s = route(true, true);
        s.collect(snapshot(6, 7, 0, List.of(), tile(1024, false, false)), null);
        s.collect(snapshot(7, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
        s.collect(
                snapshot(8, 7, 0, List.of(publication(2, 8, VERSIONS)), tile(1024, false, true)),
                null);
        assertEquals("PASS", status(s, "residentRestore"));
    }

    @Test
    void newerInstallationCannotBorrowOlderMatchingPublication() {
        var s = route(true, true);
        s.collect(
                snapshot(
                        6,
                        7,
                        0,
                        List.of(publication(2, 6, new Versions(99, 2, 3, 4, 5, 6))),
                        tile(1024, false, true)),
                null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void newNativeCycleNeedsItsOwnRecoveryEvidence() {
        var s = route(true, true);
        s.collect(snapshot(6, 7, 0, List.of(), tile(1024, true, false)), null);
        s.collect(snapshot(7, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void missingTileBreaksNativeTransitionContinuity() {
        var s = retainedThroughNative();
        s.collect(snapshot(3, 7, 0, List.of()), null);
        s.collect(snapshot(4, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "nativeExit"));
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void sampleTimeGapOrReversalWithinSameEpochCannotPass() {
        for (long time : List.of(1L, 2L + FarTraceSession.MAX_SAMPLE_GAP_NANOS + 1)) {
            var s = retainedThroughNative();
            s.collect(snapshot(time, 7, 0, List.of(), tile(1024, false, true)), null);
            assertEquals("INCOMPLETE", status(s, "continuousCapture"));
            assertEquals("INCOMPLETE", status(s, "nativeExit"));
            assertEquals("INCOMPLETE", status(s, "residentRestore"));
        }
        var s = retainedThroughNative();
        s.collect(
                snapshot(
                        2 + FarTraceSession.MAX_SAMPLE_GAP_NANOS,
                        7,
                        0,
                        List.of(),
                        tile(1024, false, true)),
                null);
        assertEquals("PASS", status(s, "residentRestore"));
    }

    @Test
    void missingRegionAfterSamplingCannotBeBridged() {
        var s = retainedThroughNative();
        s.collect(new Snapshot(WATCH, 0, 1, 7, null, List.of(), List.of(), 0), null);
        s.collect(snapshot(3, 7, 0, List.of(), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "continuousCapture"));
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void eventSequenceGapIsIncompleteEvenWithoutDroppedCounterIncrement() {
        var s = route(true, true);
        s.collect(snapshot(6, 7, 0, List.of(event(3, 6, 1024)), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "continuousCapture"));
        assertEquals("INCOMPLETE", status(s, "residentRestore"));
    }

    @Test
    void resetEpochPreventsCrossSessionPassAndStillExportsEvidence() {
        var s = route(true, true);
        assertTrue(s.tick(snapshot(6, 8, 0, List.of(), tile(1024, false, true)), null));
        assertEquals("INCOMPLETE", s.check().status());
        assertEquals(6, s.report("reset").samples().size());
        assertEquals(8, s.report("reset").finalSnapshot().epoch());
    }

    @Test
    void duplicatePeriodicStateDoesNotBecomeAnotherPhaseObservation() {
        var s = new FarTraceSession(WATCH);
        phase(s, "baseline", 1, false, true, pose(1600, 300, 0));
        phase(s, "approach", 1, true, true, pose(1032, 300, 0));
        assertEquals(1, s.samples.size());
        assertEquals("INCOMPLETE", status(s, "nativeEnter"));
    }

    @Test
    void ringOverwritesAreSafeWhenAlreadyCopiedButLostEventsAreIncomplete() {
        var s = route(true, false);
        s.collect(snapshot(6, 7, 0, List.of(event(1, 6, 1024)), tile(1024, false, true)), null);
        s.collect(snapshot(7, 7, 1, List.of(event(2, 7, 1024)), tile(1024, false, true)), null);
        assertEquals("PASS", status(s, "continuousCapture"));
        assertEquals(2, s.events.size());
        s.collect(snapshot(8, 7, 3, List.of(event(5, 8, 1024)), tile(1024, false, true)), null);
        assertEquals("INCOMPLETE", status(s, "continuousCapture"));
    }

    @Test
    void sampleAndEventBuffersRemainBoundedAndOverflowIsIncomplete() {
        var samples = new FarTraceSession(WATCH);
        for (int i = 1; i <= FarTraceSession.MAX_SAMPLES + 1; i++)
            samples.collect(snapshot(i, 7, 0, List.of(), tile(1024, false, false)), null);
        assertEquals(FarTraceSession.MAX_SAMPLES, samples.samples.size());
        assertEquals("INCOMPLETE", status(samples, "continuousCapture"));
        var events = new FarTraceSession(WATCH);
        var history =
                java.util.stream.LongStream.rangeClosed(1, FarTraceSession.MAX_EVENTS + 1)
                        .mapToObj(i -> event(i, i, 1024))
                        .toList();
        events.collect(snapshot(1, 7, 0, history, tile(1024, false, false)), null);
        assertEquals(FarTraceSession.MAX_EVENTS, events.events.size());
        assertEquals("INCOMPLETE", status(events, "continuousCapture"));
    }

    @Test
    void unobservedSamplingAndTickLimitNeverPass() {
        var s = new FarTraceSession(WATCH);
        var empty = new Snapshot(WATCH, -1, 0, 7, null, List.of(), List.of(), 0);
        for (int i = 1; i < FarTraceSession.MAX_TICKS; i++) assertFalse(s.tick(empty, null));
        assertTrue(s.tick(empty, null));
        assertEquals("INCOMPLETE", s.check().status());
        assertEquals("INCOMPLETE", status(s, "sampling"));
    }

    @Test
    void outOfRegionTileIsAContractFailureRatherThanMissingCoverage() {
        var s = route(true, true);
        s.collect(snapshot(6, 7, 0, List.of(), tile(0, false, true)), null);
        assertEquals("FAIL", status(s, "tileIdentity"));
        assertEquals("FAIL", s.check().status());
    }

    @Test
    void marksAreBounded() {
        var s = new FarTraceSession(WATCH);
        for (int i = 0; i < FarTraceSession.MAX_MARKS; i++) s.mark("phase" + i);
        assertThrows(IllegalStateException.class, () -> s.mark("overflow"));
        assertEquals(FarTraceSession.MAX_MARKS, s.marks.size());
    }

    @Test
    void boundedCaptureCannotStillReportPassAfterProvingRoute() {
        var s = route(true, true);
        var last = s.report("ongoing").finalSnapshot();
        while (!s.tick(last, pose(1600, 600, 120))) {}
        assertEquals("INCOMPLETE", s.check().status());
        assertEquals("INCOMPLETE", status(s, "continuousCapture"));
        assertEquals(5, s.samples.size());
    }

    @Test
    void anotherPlanetsPoseCannotProveMotion() {
        var s = route(true, true);
        s.mark("turn");
        var other = new FarTraceSession.Pose(1, 0, 1600, 600, 1200, 120);
        var fresh = new FarTraceSession(WATCH);
        for (var sample : s.samples) {
            fresh.mark(sample.phase());
            fresh.collect(
                    snapshot(
                            sample.state().nanoTime(),
                            7,
                            0,
                            List.of(),
                            sample.state().tiles().getFirst()),
                    other);
        }
        assertEquals("INCOMPLETE", fresh.check().status());
        assertEquals("INCOMPLETE", status(fresh, "turnMotion"));
    }

    @Test
    void jsonReportRetainsMachineAssertionsEvenWhenCoverageFails() {
        var s = route(false, true);
        var json =
                com.google.gson.JsonParser.parseString(
                                new com.google.gson.Gson().toJson(s.report("failed: incomplete")))
                        .getAsJsonObject();
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("INCOMPLETE", json.getAsJsonObject("result").get("status").getAsString());
        assertFalse(json.getAsJsonObject("result").get("residentRestoreAttempted").getAsBoolean());
        assertEquals(5, json.getAsJsonArray("samples").size());
        assertTrue(json.getAsJsonObject("finalSnapshot").has("region"));
        assertTrue(json.getAsJsonObject("result").getAsJsonArray("assertions").size() >= 10);
    }

    private static FarTraceSession retainedThroughNative() {
        var s = new FarTraceSession(WATCH);
        s.collect(snapshot(1, 7, 0, List.of(), tile(1024, false, true)), null);
        s.collect(snapshot(2, 7, 0, List.of(), tile(1024, true, true)), null);
        return s;
    }

    private static final class Resident {
        boolean mesh = true, complete = true, nativeWindow, previous, retiring, fading, queued;
        int requested = 1, selected = 1, jobs;
        Versions versions = VERSIONS;
        long meshMip = 3, meshSurface = 1;

        TileState tile() {
            return new TileState(
                    0,
                    1024,
                    1024,
                    nativeWindow,
                    requested,
                    selected,
                    mesh,
                    complete,
                    previous,
                    retiring,
                    fading,
                    queued,
                    jobs,
                    16,
                    16,
                    true,
                    true,
                    false,
                    versions,
                    meshMip,
                    meshSurface);
        }
    }

    private static Event evidence(
            long sequence, long time, Source source, Kind kind, String reason) {
        return new Event(sequence, time, 7, 0, 0, 1024, 1024, source, kind, reason, 1, VERSIONS);
    }

    private static Event publication(long sequence, long time, Versions versions) {
        return new Event(
                sequence,
                time,
                7,
                0,
                0,
                1024,
                1024,
                Source.RESIDENT,
                Kind.PUBLICATION,
                "resident-installed-fading",
                1,
                versions);
    }

    private static Event event(long sequence, long time, int x) {
        return new Event(
                sequence,
                time,
                7,
                0,
                0,
                x,
                1024,
                Source.RESIDENT,
                Kind.REQUEST,
                "outside-native",
                1,
                VERSIONS);
    }
}
