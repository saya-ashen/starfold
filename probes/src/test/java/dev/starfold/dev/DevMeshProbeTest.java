package dev.starfold.dev;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DevMeshProbeTest {
    @Test void histogramIncludesZeroAndReportsConservativeBounds() {
        var times = new DevMeshProbe.Times();
        times.add(0);
        times.add(1_000_000);
        times.add(3_000_000);
        String summary = times.summary();
        assertTrue(summary.contains("n=3 total_ms=4.000 mean_ms=1.333"), summary);
        assertTrue(summary.contains("p50_upper_ms=1.049 p95_upper_ms=4.194 max_ms=3.000"), summary);
        times.clear();
        assertTrue(times.summary().contains("n=0 total_ms=0.000"));
    }
    @Test void oldTasksCannotContaminateNextFlightLeg() {
        DevMeshProbe.begin();
        var trace = new DevMeshProbe.Trace(123, 5);
        assertTrue(DevMeshProbe.active(trace));
        DevMeshProbe.begin();
        assertFalse(DevMeshProbe.active(trace));
        assertFalse(DevMeshProbe.active(null));
        DevMeshProbe.end("test");
    }
}
