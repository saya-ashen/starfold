package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class DevPlanetSoundProbeTest {
    @Test
    void defaultDisabledHasNoSessionOrMinecraftAccess() {
        assertFalse(DevPlanetSoundProbe.enabled());
        assertThrows(IllegalStateException.class, () -> DevPlanetSoundProbe.command(null, "start"));
        DevPlanetSoundProbe.created(null);
        DevPlanetSoundProbe.scaled(null, 0, true, .45f);
        DevPlanetSoundProbe.calculated(null, 4, .9f, "initial-play");
        DevPlanetSoundProbe.tick(null);
        DevPlanetSoundProbe.stop(null);
    }

    @Test
    void capacityIsEightIdentityKeysAndSixtyFourLines() {
        var lines = new ArrayList<String>();
        var session = new DevPlanetSoundProbe.Session(lines::add);
        var first = new String("same");
        assertTrue(session.add(first, "first"));
        assertFalse(session.add(first, "duplicate"));
        assertTrue(session.add(new String("same"), "distinct-identity"));
        for (int i = 2; i < 8; i++) assertTrue(session.add(new Object(), "fixture-" + i));
        assertFalse(session.add(new Object(), "overflow"));
        for (int i = 0; i < 100; i++) session.log("line-" + i);
        assertEquals(64, lines.size());
        assertEquals(8, session.entries.size());
        var stopped = new ArrayList<Object>();
        session.stop(stopped::add);
        assertEquals(8, stopped.size());
        assertTrue(session.entries.isEmpty());
    }

    @Test
    void cleanupDropsReferencesEvenIfSoundManagerStopFails() {
        var session = new DevPlanetSoundProbe.Session(ignored -> {});
        session.add(new Object(), "fixture");
        assertThrows(
                IllegalStateException.class,
                () ->
                        session.stop(
                                ignored -> {
                                    throw new IllegalStateException("fixture stop failure");
                                }));
        assertTrue(session.entries.isEmpty());
    }

    @Test
    void tapPreservesProductionValuesDeduplicatesAndSeparatesPhases() {
        var lines = new ArrayList<String>();
        var session = new DevPlanetSoundProbe.Session(lines::add);
        Object sound = new Object();
        session.add(sound, "fixture");
        session.scaled(sound, .37, true, .123f);
        session.calculated(sound, 4, .234f, "initial-play");
        assertTrue(
                lines.getFirst()
                        .contains(
                                "density=0.37 self=true scale=0.123 finalClamp=0.234 productionScaleObserved=true"));
        session.scaled(sound, .37, true, .123f);
        session.calculated(sound, 4, .234f, "initial-play");
        assertEquals(1, lines.size());
        session.scaled(sound, 0, false, .12f);
        session.calculated(sound, 4, .24f, "category-refresh");
        assertEquals(2, lines.size());
        assertTrue(lines.getLast().contains("phase=category-refresh"));
        assertTrue(lines.getLast().contains("self=false scale=0.12 finalClamp=0.24"));
        session.calculated(new Object(), 4, .5f, "unlabelled");
        assertEquals(2, lines.size());
        session.calculated(sound, 4, .24f, "instance-tick-or-external-refresh");
        assertTrue(lines.getLast().contains("productionScaleObserved=false"));
        session.stop(ignored -> {});
        session.scaled(sound, 1, true, 1);
        session.calculated(sound, 4, 1, "after-stop");
        assertEquals(3, lines.size());
    }
}
