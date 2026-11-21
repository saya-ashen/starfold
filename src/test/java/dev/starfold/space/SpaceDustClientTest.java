package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpaceDustClientTest {
    @Test
    void densityRisesFromThresholdToNamedCap() {
        assertEquals(0, SpaceDustClient.density(0));
        assertEquals(0, SpaceDustClient.density(60));
        assertEquals(60, SpaceDustClient.DUST_MIN);
        assertEquals(28, SpaceDustClient.DUST_MAX_PER_TICK);
        double last = 0;
        for (int speed = 61; speed <= 800; speed++) {
            double next = SpaceDustClient.density(speed);
            assertTrue(next > last);
            assertTrue(next <= SpaceDustClient.DUST_MAX_PER_TICK);
            last = next;
        }
        assertEquals(SpaceDustClient.DUST_MAX_PER_TICK, last);
        assertEquals(last, SpaceDustClient.density(10000));
    }

    @Test
    void particlesAreCameraLocalRequirePilotButNotHud() throws Exception {
        assertTrue(SpaceDustClient.LIFETIME + 1 < 10);
    }
}
