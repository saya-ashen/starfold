package dev.starfold.devtools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.starfold.api.FarViewDiagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class FarReport {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    record Report(
            int schemaVersion,
            String capturedAt,
            long capturedNanos,
            String trigger,
            String stoppedReason,
            long clientTicks,
            long startedNanos,
            int maxStates,
            int maxEvents,
            int maxTicks,
            long missingEvents,
            long discardedStates,
            long discardedEvents,
            FarViewDiagnostics.Snapshot core,
            List<FarCapture.State> states,
            List<FarViewDiagnostics.Event> events,
            FarRawCapture.Report raw,
            FarAnalyzer.Result analysis,
            List<String> limitations) {}

    static Report create(FarCapture capture, FarRawCapture.Report raw, String trigger) {
        var c = raw.counts();
        boolean observed =
                c.loadedSamples() > 0
                        && c.unloadedColumns() == 0
                        && c.unmappedColumns() == 0
                        && c.unknownColumns() == 0
                        && c.unknownSamples() == 0
                        && c.unloadedSamples() == 0;
        return new Report(
                1,
                Instant.now().toString(),
                System.nanoTime(),
                trigger,
                capture.stoppedReason,
                capture.ticks,
                capture.startedNanos,
                FarCapture.MAX_STATES,
                FarCapture.MAX_EVENTS,
                FarCapture.MAX_TICKS,
                capture.missingEvents,
                capture.discardedStates,
                capture.discardedEvents,
                capture.latest,
                capture.states(),
                capture.events(),
                raw,
                FarAnalyzer.analyze(capture, observed),
                List.of(
                        "CPU lifecycle/geometry and optional bounded DRAW_GPU_STATE observations; no GPU pixel visibility claim.",
                        "DRAW_GPU_STATE samples once per watch epoch, source, layer and render stage at the center tile; absent records are unavailable. Atlas bytes are CPU upload arguments, not GPU texture readback.",
                        "Raw world is sampled only at explicit export time, in the center 16x16 tile, around each loaded WORLD_SURFACE height; it is not historical raw world.",
                        "Region states, events, geometry and raw world carry separate times/provenance; export is not an atomic render/world frame.",
                        "Absent region/geometry is unavailable, never observed false. Missing events do not prove requests or draws did not occur.",
                        "GRACE is a diagnostic threshold, not a renderer correctness guarantee. Exterior fallback visibility is not observed.",
                        "The native-window lifecycle uses observed true-to-false transitions; capture beginning outside that window cannot establish retreat.",
                        "Report bounds, sampling caps, overwrites and raw truncation counters are part of the evidence."));
    }

    static Path write(Path gameDirectory, Report report) throws IOException {
        Path directory = gameDirectory.resolve("diagnostics").resolve("far-view");
        Files.createDirectories(directory);
        Path json = Files.createTempFile(directory, "far-", ".json");
        try (var writer = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
            GSON.toJson(report, writer);
        }
        Files.writeString(
                json.resolveSibling(json.getFileName() + ".summary.txt"),
                summary(report),
                StandardCharsets.UTF_8);
        return json;
    }

    static String summary(Report report) {
        var a = report.analysis();
        var c = report.raw().counts();
        StringBuilder text =
                new StringBuilder("Far-view diagnostics: ")
                        .append(a.status())
                        .append("\nLifecycle: ")
                        .append(a.lifecycle())
                        .append("\nState: ")
                        .append(a.stateAvailability())
                        .append("; geometry: ")
                        .append(a.geometryAvailability())
                        .append("; raw: ")
                        .append(a.rawAvailability())
                        .append("\nStates: ")
                        .append(report.states().size())
                        .append("; events: ")
                        .append(report.events().size())
                        .append("; missing sequences: ")
                        .append(report.missingEvents())
                        .append("; overwritten states/events: ")
                        .append(report.discardedStates())
                        .append('/')
                        .append(report.discardedEvents())
                        .append("\nRaw primary loaded/total: ")
                        .append(c.loadedSamples())
                        .append('/')
                        .append(c.primarySamples())
                        .append("; unloaded/unmapped/unknown columns: ")
                        .append(c.unloadedColumns())
                        .append('/')
                        .append(c.unmappedColumns())
                        .append('/')
                        .append(c.unknownColumns())
                        .append("; budget omissions: ")
                        .append(c.budgetOmissions())
                        .append("; fluid neighbor sets truncated: ")
                        .append(c.neighborSetsTruncated())
                        .append('\n');
        for (String reason : a.incompleteReasons())
            text.append("INCOMPLETE: ").append(reason).append('\n');
        for (var finding : a.findings())
            text.append(finding.rule())
                    .append(" face=")
                    .append(finding.face())
                    .append(" x=")
                    .append(finding.x())
                    .append(" z=")
                    .append(finding.z())
                    .append(": ")
                    .append(finding.detail())
                    .append('\n');
        for (String limitation : report.limitations()) text.append(limitation).append('\n');
        return text.toString();
    }

    private FarReport() {}
}
