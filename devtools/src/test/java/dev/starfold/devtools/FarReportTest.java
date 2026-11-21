package dev.starfold.devtools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.api.FarViewDiagnostics.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class FarReportTest {
    @TempDir Path directory;

    @Test
    void exportRetainsUnavailableNullsBoundsProvenanceAndCpuRecords() throws Exception {
        var capture = new FarCapture(100);
        capture.accept(
                new Snapshot(
                        new Watch(2, 3, 16, 32, 1, 7),
                        2,
                        99,
                        7,
                        null,
                        List.of(),
                        List.of(),
                        0,
                        List.of(
                                new CpuRecord(
                                        7,
                                        2,
                                        3,
                                        16,
                                        32,
                                        "PACKED_CPU",
                                        200,
                                        Map.of(
                                                "materialId",
                                                17,
                                                "meaning",
                                                "fixture CPU evidence")),
                                new CpuRecord(
                                        7,
                                        2,
                                        3,
                                        16,
                                        32,
                                        "DRAW_GPU_STATE",
                                        201,
                                        Map.of(
                                                "renderStage",
                                                "AFTER_ENTITIES",
                                                "gpu",
                                                Map.of(
                                                        "currentProgram",
                                                        17,
                                                        "coverage",
                                                        Map.of("unit", 3, "texture2D", 29),
                                                        "textureContents",
                                                        "UNOBSERVED")))),
                        4));
        capture.stoppedReason = "fixture stop";
        var unavailable = new FarRawCaptureTest.Fake();
        unavailable.unloaded = true;
        var raw = FarRawCapture.capture(3, 16, 32, unavailable);
        var report = FarReport.create(capture, raw, "stop");
        var path = FarReport.write(directory, report);
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(directory.resolve("diagnostics/far-view"), path.getParent());
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertTrue(json.getAsJsonObject("core").get("region").isJsonNull());
        assertEquals(7, json.getAsJsonObject("core").get("epoch").getAsLong());
        assertEquals(
                "PACKED_CPU",
                json.getAsJsonObject("core")
                        .getAsJsonArray("records")
                        .get(0)
                        .getAsJsonObject()
                        .get("stage")
                        .getAsString());
        var draw = json.getAsJsonObject("core").getAsJsonArray("records").get(1).getAsJsonObject();
        assertEquals("DRAW_GPU_STATE", draw.get("stage").getAsString());
        assertEquals(
                "AFTER_ENTITIES", draw.getAsJsonObject("fields").get("renderStage").getAsString());
        assertEquals(
                29,
                draw.getAsJsonObject("fields")
                        .getAsJsonObject("gpu")
                        .getAsJsonObject("coverage")
                        .get("texture2D")
                        .getAsInt());
        assertEquals(4, json.getAsJsonObject("core").get("droppedRecords").getAsInt());
        assertEquals(
                256,
                json.getAsJsonObject("raw")
                        .getAsJsonObject("counts")
                        .get("unloadedColumns")
                        .getAsInt());
        assertEquals("INCOMPLETE", report.analysis().status());
        assertEquals("UNAVAILABLE", report.analysis().stateAvailability());
        assertTrue(
                report.analysis().incompleteReasons().stream()
                        .anyMatch(s -> s.contains("records truncated")));
        String summary = Files.readString(path.resolveSibling(path.getFileName() + ".summary.txt"));
        assertTrue(summary.contains("INCOMPLETE"));
        assertTrue(summary.contains("no GPU pixel visibility claim"));
        assertNotEquals(path, FarReport.write(directory, report));
    }
}
