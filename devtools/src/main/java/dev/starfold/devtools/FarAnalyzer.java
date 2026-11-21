package dev.starfold.devtools;

import dev.starfold.api.FarViewDiagnostics.*;

import java.util.*;

/** Rules describe CPU observations only. Absence of evidence never becomes an observed false. */
final class FarAnalyzer {
    static final long GRACE_NANOS = 2_000_000_000L;
    static final long MAX_SAMPLE_GAP_NANOS = 1_000_000_000L;

    record Finding(
            String rule,
            int face,
            int x,
            int z,
            long epoch,
            long fromNanos,
            long toNanos,
            String detail) {}

    record Result(
            String status,
            String stateAvailability,
            String geometryAvailability,
            String rawAvailability,
            String lifecycle,
            long graceNanos,
            List<String> incompleteReasons,
            List<Finding> findings) {}

    private static final class TileHistory {
        long time, exit = -1, gap = -1, detailGap = -1;
        boolean near, initialized, reported, detailReported;
    }

    static Result analyze(FarCapture capture, boolean rawObserved) {
        List<String> incomplete = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Map<String, TileHistory> histories = new HashMap<>();
        boolean retreat = false, restoreRequest = false, restoreDraw = false;
        long priorEpoch = Long.MIN_VALUE;
        for (var observation : capture.states()) {
            RegionState region = observation.region();
            if (region.epoch() != priorEpoch) {
                histories.clear();
                priorEpoch = region.epoch();
            }
            Set<String> seen = new HashSet<>();
            for (TileState tile : region.tiles()) {
                String key = tile.face() + ":" + tile.x() + ":" + tile.z();
                seen.add(key);
                var h = histories.computeIfAbsent(key, unused -> new TileHistory());
                boolean contiguous =
                        h.initialized
                                && region.nanoTime() > h.time
                                && region.nanoTime() - h.time <= MAX_SAMPLE_GAP_NANOS;
                if (!contiguous) {
                    h.exit = -1;
                    h.gap = -1;
                    h.detailGap = -1;
                    h.reported = false;
                    h.detailReported = false;
                }
                if (contiguous && h.near && !tile.nativeWindow()) {
                    h.exit = region.nanoTime();
                    retreat = true;
                }
                if (tile.nativeWindow()) h.exit = -1;
                if (h.exit >= 0) {
                    for (Event event : capture.events()) {
                        if (event.source() != Source.RESIDENT
                                || event.epoch() != region.epoch()
                                || event.planet() != region.planet()
                                || event.face() != tile.face()
                                || event.x() != tile.x()
                                || event.z() != tile.z()
                                || event.nanoTime() < h.exit
                                || event.nanoTime() > region.nanoTime()) continue;
                        if (event.kind() == Kind.REQUEST) restoreRequest = true;
                        if (event.kind() == Kind.DRAW) restoreDraw = true;
                    }
                }
                // Presence is CPU mesh state, not proof that a fallback drew or pixels were
                // visible.
                boolean gap =
                        region.coverageReady()
                                && region.selectionActive()
                                && !tile.nativeWindow()
                                && !tile.meshPresent()
                                && !tile.previousMesh()
                                && !tile.chartMeshPresent()
                                && !tile.parentMeshPresent()
                                && !tile.stagedMeshPresent();
                if (!gap) {
                    h.gap = -1;
                    h.reported = false;
                } else if (h.exit >= 0) {
                    if (h.gap < 0) h.gap = region.nanoTime();
                    if (!h.reported && region.nanoTime() - h.gap > GRACE_NANOS) {
                        findings.add(
                                new Finding(
                                        "PERSISTENT_CPU_HANDOFF_GAP",
                                        tile.face(),
                                        tile.x(),
                                        tile.z(),
                                        region.epoch(),
                                        h.gap,
                                        region.nanoTime(),
                                        "Observed native-window exit followed by no resident/previous/chart/parent/staged mesh past grace; exterior fallback and GPU pixels remain unobserved."));
                        h.reported = true;
                    }
                }
                boolean detailGap =
                        region.coverageReady()
                                && region.selectionActive()
                                && h.exit >= 0
                                && !tile.nativeWindow()
                                && tile.selectedStep() > 0
                                && !tile.meshPresent()
                                && !tile.previousMesh()
                                && !tile.queued()
                                && tile.jobs() == 0;
                if (!detailGap) {
                    h.detailGap = -1;
                    h.detailReported = false;
                } else {
                    if (h.detailGap < 0) h.detailGap = region.nanoTime();
                    if (!h.detailReported && region.nanoTime() - h.detailGap > GRACE_NANOS) {
                        findings.add(
                                new Finding(
                                        "RESIDENT_REQUEST_GAP",
                                        tile.face(),
                                        tile.x(),
                                        tile.z(),
                                        region.epoch(),
                                        h.detailGap,
                                        region.nanoTime(),
                                        "Resident detail remains selected after native exit, but no resident/previous mesh, queued request or job was observed past grace; coarse fallback may remain valid. GPU pixels unobserved."));
                        h.detailReported = true;
                    }
                }
                h.near = tile.nativeWindow();
                h.time = region.nanoTime();
                h.initialized = true;
            }
            histories.keySet().retainAll(seen);
        }
        boolean state = !capture.states().isEmpty();
        boolean geometry = capture.latest != null && !capture.latest.geometry().isEmpty();
        if (!state) incomplete.add("No periodic region state observed; booleans unavailable.");
        if (!retreat)
            incomplete.add("No contiguous native-window true-to-false transition observed.");
        if (!geometry) incomplete.add("No CPU mesh geometry evidence retained.");
        if (!rawObserved) incomplete.add("Selected tile raw world data unavailable or incomplete.");
        if (capture.missingEvents > 0 || capture.discardedEvents > 0 || capture.discardedStates > 0)
            incomplete.add(
                    "History truncated or event sequences missing; lifecycle evidence is partial.");
        if (capture.latest != null && capture.latest.droppedRecords() > 0)
            incomplete.add("Core CPU evidence records truncated; inspect droppedRecords.");
        if (capture.latest != null
                && capture.latest.watch() != null
                && capture.latest.rendererPlanet() != capture.latest.watch().planet())
            incomplete.add("Renderer planet differs from watched planet.");
        return new Result(
                incomplete.isEmpty() ? "OBSERVED" : "INCOMPLETE",
                state ? "OBSERVED_TRUE_AND_FALSE_ARE_EXPLICIT" : "UNAVAILABLE",
                geometry ? "OBSERVED_CPU_GEOMETRY" : "UNAVAILABLE",
                rawObserved ? "OBSERVED_BOUNDED_SAMPLE" : "UNAVAILABLE_OR_PARTIAL",
                !retreat
                        ? "NATIVE_RETREAT_UNOBSERVED"
                        : restoreDraw
                                ? "RETREAT_THEN_CPU_DRAW"
                                : restoreRequest
                                        ? "RETREAT_THEN_REQUEST"
                                        : "RETREAT_WITHOUT_RESTORATION_EVIDENCE",
                GRACE_NANOS,
                List.copyOf(incomplete),
                List.copyOf(findings));
    }

    private FarAnalyzer() {}
}
