package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

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
        var s = Sources.text("SpaceDustClient");
        assertTrue(s.contains("frame.levelVector(frame.drift())"));
        assertTrue(s.contains("getMainCamera().getPosition()"));
        assertTrue(s.contains("setParticleSpeed(-velocity.x,-velocity.y,-velocity.z)"));
        assertTrue(s.contains("ParticleStatus.MINIMAL"));
        assertTrue(s.contains("ParticleStatus.DECREASED?.5:1"));
        assertTrue(s.contains("getTrackingSubLevel(mc.player)"));
        assertTrue(s.contains("!SpaceClient.active()"));
        assertFalse(s.contains("hideGui"));
        assertTrue(s.contains("HelmClient.body()==null"));
        assertTrue(SpaceDustClient.LIFETIME + 1 < 10);
    }
}
