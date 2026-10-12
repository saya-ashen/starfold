package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class ThrusterSoundTest {
    @Test
    void silentWithoutThrustUnlessHelmed() {
        for (boolean boost : new boolean[] {false, true}) {
            assertEquals(0, ThrusterSound.target(0, 30, boost, false));
            assertEquals(0, ThrusterSound.target(-1, 30, boost, false));
            assertEquals(0, ThrusterSound.target(150, 0, boost, false));
            assertEquals(ThrusterSound.IDLE, ThrusterSound.target(0, 30, boost, true));
        }
        assertEquals(0, ThrusterSound.volume(0));
    }

    @Test
    void spoolFollowsShareOfInstalledThrustAndOnlyBoostReachesFull() {
        // The Hauler: thirty thrusters. Hover on ten at three quarters, then eight more for W, then
        // the booster.
        double hover = ThrusterSound.target(10 * 11.25, 30, false, true),
                burn = ThrusterSound.target(10 * 11.25 + 8 * 15, 30, false, true),
                boost = ThrusterSound.target(10 * 11.25 + 8 * 15, 30, true, true);
        assertEquals(.375, hover, 1e-9);
        assertEquals(ThrusterSound.DRY, burn, 1e-9);
        assertEquals(1, boost, 1e-9);
        assertTrue(ThrusterSound.IDLE < hover && hover < burn && burn < boost);
        assertEquals(1, ThrusterSound.target(1e9, 30, true, false));
        assertEquals(.4, ThrusterSound.target(8 * 15, 30, false, false), 1e-9);
        assertEquals(1, ThrusterSound.target(8 * 15, 30, true, false));
        double v = -1, p = -1;
        for (double s = 0; s <= 1; s += .01) {
            assertTrue(ThrusterSound.volume(s) > v);
            assertTrue(ThrusterSound.pitch(s) > p);
            v = ThrusterSound.volume(s);
            p = ThrusterSound.pitch(s);
        }
        assertEquals(ThrusterSound.VOLUME, ThrusterSound.volume(2));
        assertEquals(
                ThrusterSound.BASE_PITCH + ThrusterSound.PITCH_RANGE,
                ThrusterSound.pitch(2),
                1e-12);
    }

    @Test
    void spoolTakesASecondUpTwoDownAndCannotOvershoot() {
        double s = 0;
        for (int tick = 0; tick < 19; tick++) {
            double next = ThrusterSound.spool(s, 1);
            assertTrue(next > s && next < 1);
            s = next;
        }
        assertEquals(1, ThrusterSound.spool(s, 1), 1e-12);
        s = 1;
        for (int tick = 0; tick < 39; tick++) {
            double next = ThrusterSound.spool(s, 0);
            assertTrue(next < s && next > 0);
            s = next;
        }
        assertEquals(0, ThrusterSound.spool(s, 0));
        for (double start : new double[] {0, .1, .4, 1})
            for (double target : new double[] {0, .1, .75, 1}) {
                double value = start;
                for (int tick = 0; tick < 40; tick++) {
                    double next = ThrusterSound.spool(value, target);
                    assertTrue(
                            next >= Math.min(value, target) - 1e-12
                                    && next <= Math.max(value, target) + 1e-12);
                    value = next;
                }
                assertEquals(target, value, 1e-12);
            }
    }

    @Test
    void resourcePackCanReplaceOurEventAndBothSubtitlesExist() throws Exception {
        var root = Path.of("src/main/resources/assets/starfold");
        var sound =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("sounds.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("thruster");
        assertEquals("subtitles.starfold.thruster", sound.get("subtitle").getAsString());
        assertEquals(
                "starfold:thruster",
                sound.getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(
                ThrusterSound.RANGE,
                sound.getAsJsonArray("sounds")
                        .get(0)
                        .getAsJsonObject()
                        .get("attenuation_distance")
                        .getAsInt());
        for (String language : new String[] {"en_us", "zh_cn"})
            assertTrue(
                    com.google.gson.JsonParser.parseString(
                                    Files.readString(root.resolve("lang/" + language + ".json")))
                            .getAsJsonObject()
                            .has(sound.get("subtitle").getAsString()));
    }

    @Test
    void hullLoopIsClientOnlyAndSpaceRequiresCrew() throws Exception {
        var s = Sources.text("ThrusterSoundClient");
        assertTrue(s.contains("IdentityHashMap<ClientSubLevel,Engine>"));
        assertTrue(s.contains("aboard==body"));
        assertTrue(s.contains("getBlockEntityActors()"));
        assertTrue(s.contains("engine.target=0"));
        assertTrue(s.contains("AbstractTickableSoundInstance"));
        assertFalse(s.contains("sendToServer"));
        assertFalse(s.contains("playLocalSound"));
    }
}
