package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.SourceText;
import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundEphemeris;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.List;

class NavigationLockTest {
    private static SpaceNavigation.Candidate candidate(int id, double x, double y, double angle) {
        return new SpaceNavigation.Candidate(id, new SpaceNavigation.Marker(x, y, false, 0), angle);
    }

    @Test
    void coneIncludesBoundaryButNotBehindOrInvalid() {
        assertEquals(20, SpaceNavigation.LOCK_CONE);
        assertEquals(
                1,
                SpaceNavigation.choose(
                        List.of(
                                candidate(0, .5, .5, 20.0001),
                                candidate(1, .6, .5, 20),
                                candidate(2, .5, .5, 180),
                                candidate(3, .5, .5, Double.NaN)),
                        800,
                        400));
        assertEquals(-1, SpaceNavigation.choose(List.of(), 800, 400));
        assertEquals(-1, SpaceNavigation.choose(List.of(candidate(0, .5, .5, 90)), 800, 400));
    }

    @Test
    void ringUsesPixelDistanceNotPointerOrFractionDistance() {
        assertEquals(
                1,
                SpaceNavigation.choose(
                        List.of(candidate(0, .55, .5, 5), candidate(1, .5, .57, 10)), 800, 400));
        assertEquals(
                0,
                SpaceNavigation.choose(
                        List.of(candidate(0, .5, .5, 5), candidate(1, .5, .5, 1)), 800, 400));
    }

    @Test
    void edgeMarkerCanWinWithinCone() {
        var edge =
                new SpaceNavigation.Candidate(7, new SpaceNavigation.Marker(.95, .5, true, 0), 19);
        assertEquals(7, SpaceNavigation.choose(List.of(edge), 800, 400));
    }

    @Test
    void arrivalFormatsMinutesAndRoundsUp() {
        assertEquals("01:01", SpaceNavigation.arrival(new SpaceNavigation.Reading(120.1, 2)));
        assertEquals("00:00", SpaceNavigation.arrival(new SpaceNavigation.Reading(-1, 2)));
        assertEquals("100:00", SpaceNavigation.arrival(new SpaceNavigation.Reading(6000, 1)));
    }

    @Test
    void arrivalDoesNotInventAnEtaWithoutClosing() {
        for (double speed : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals("—", SpaceNavigation.arrival(new SpaceNavigation.Reading(100, speed)));
        assertEquals("—", SpaceNavigation.arrival(new SpaceNavigation.Reading(Double.NaN, 1)));
    }

    @Test
    void velocityChoosesLockedOrReferenceIncludingTheirOrbits() {
        var model = PlanetSystem.model();
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationXYZ(.3, .8, .2),
                        Space.ORIGIN,
                        0,
                        GroundEphemeris.ZERO,
                        new Point(2, 3, 4),
                        900);
        assertEquals(
                0,
                SpaceNavigation.velocity(model, frame, -1, 900).sub(frame.drift()).length(),
                1e-12);
        var expected =
                frame.drift().add(model.at(0, 900).velocity()).sub(model.at(1, 900).velocity());
        assertEquals(expected, SpaceNavigation.velocity(model, frame, 1, 900));
        assertEquals(
                SpaceNavigation.velocity(model, frame, -1, 900),
                SpaceNavigation.velocity(model, frame, 9999, 900));
        assertEquals(
                0,
                expected.sub(SpaceNavigation.velocity(model, frame.toward(1, model, 900), 1, 900))
                        .length(),
                1e-12);
    }

    @Test
    void velocityThresholdAndRetrograde() {
        assertNull(SpaceNavigation.velocityMarker(new Point(0, 0, -.049)));
        var front = SpaceNavigation.velocityMarker(new Point(0, 0, -.05));
        assertNotNull(front);
        assertFalse(front.retrograde());
        var back = SpaceNavigation.velocityMarker(new Point(1, 2, 3));
        assertTrue(back.retrograde());
        assertEquals(new Point(-1, -2, -3), back.camera());
    }

    @Test
    void keyConsumedBeforeVanillaAndOnlyAtHelm() throws Exception {
        SourceText mixin = Sources.text("NavigationKeyMixin"),
                client = Sources.text("SpaceNavigationClient");
        assertTrue(mixin.contains("at=@At(\"HEAD\"),cancellable=true"));
        assertTrue(
                client.contains(
                        "(!lockKey.matches(key,scan)&&!nextKey.matches(key,scan))||HelmClient.body()==null"));
        assertTrue(client.contains("action==org.lwjgl.glfw.GLFW.GLFW_PRESS&&visible()"));
        assertTrue(client.contains("body!=lockBody||mc.level!=lockLevel"));
        assertTrue(client.contains("transformDirection"));
        assertTrue(client.contains("sendToServer(new SpacePackets.Target(planet))"));
        assertTrue(client.contains("if(locked==planet)return"));
        for (String language : new String[] {"en_us", "zh_cn"})
            assertTrue(
                    java.nio.file.Files.readString(
                                    java.nio.file.Path.of(
                                            "src/main/resources/assets/starfold/lang/"
                                                    + language
                                                    + ".json"))
                            .contains("key.starfold.lock_planet"));
    }
}
