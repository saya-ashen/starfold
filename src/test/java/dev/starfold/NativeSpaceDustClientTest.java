package dev.starfold;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceDustClientTest {
    @Test void densityRisesFromThresholdToNamedCap(){assertEquals(0,NativeSpaceDustClient.density(0));assertEquals(0,NativeSpaceDustClient.density(60));assertEquals(60,NativeSpaceDustClient.DUST_MIN);assertEquals(28,NativeSpaceDustClient.DUST_MAX_PER_TICK);double last=0;for(int speed=61;speed<=800;speed++){double next=NativeSpaceDustClient.density(speed);assertTrue(next>last);assertTrue(next<=NativeSpaceDustClient.DUST_MAX_PER_TICK);last=next;}assertEquals(NativeSpaceDustClient.DUST_MAX_PER_TICK,last);assertEquals(last,NativeSpaceDustClient.density(10000));}
    @Test void particlesAreCameraLocalRequirePilotButNotHud()throws Exception{var s=Files.readString(Path.of("src/main/java/dev/starfold/NativeSpaceDustClient.java"));assertTrue(s.contains("frame.levelVector(frame.drift())"));assertTrue(s.contains("getMainCamera().getPosition()"));assertTrue(s.contains("setParticleSpeed(-velocity.x,-velocity.y,-velocity.z)"));assertTrue(s.contains("ParticleStatus.MINIMAL"));assertTrue(s.contains("ParticleStatus.DECREASED?.5:1"));assertTrue(s.contains("getTrackingSubLevel(mc.player)"));assertTrue(s.contains("!NativeSpaceClient.active()"));assertFalse(s.contains("hideGui"));assertTrue(s.contains("NativeHelmClient.body()==null"));assertTrue(NativeSpaceDustClient.LIFETIME+1<10);}
}
