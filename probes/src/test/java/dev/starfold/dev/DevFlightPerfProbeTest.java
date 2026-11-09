package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DevFlightPerfProbeTest {
    @Test
    void nearestRankPercentiles() {
        long[] samples = new long[100];
        for (int i = 0; i < samples.length; i++) samples[i] = i + 1;
        assertEquals(50, DevFlightPerfProbe.percentile(samples, .5));
        assertEquals(95, DevFlightPerfProbe.percentile(samples, .95));
        assertEquals(99, DevFlightPerfProbe.percentile(samples, .99));
        assertEquals(7, DevFlightPerfProbe.percentile(new long[] {7}, .99));
    }
}
