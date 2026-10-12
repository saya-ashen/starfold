package dev.starfold.space;

import static dev.starfold.space.SpaceTest.point;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundEphemeris;

import org.junit.jupiter.api.Test;

class FarTargetTest {
    private static final SpaceSpeed.Caps CAPS = new SpaceSpeed.Caps(300, 800);
    private static final Point OFFSET = new Point(86.0 / 20, 0, 0),
            NOSE = new Point(0, 0, 1),
            ZERO = GroundEphemeris.ZERO;

    @Test
    void capAndSheddingUseTargetRelativeSpeed() {
        for (boolean boost : new boolean[] {false, true}) {
            double limit = boost ? 40 : 15;
            var before = NOSE.scale(limit).add(OFFSET);
            var next = SpaceServer.cruise(before, before.add(NOSE), NOSE, boost, OFFSET, CAPS);
            point(NOSE.scale(limit), next.sub(OFFSET));
            assertTrue(next.length() > limit);
        }
        var before = NOSE.scale(40).add(OFFSET);
        var next = SpaceServer.cruise(before, before, NOSE, false, OFFSET, CAPS);
        assertEquals(300 + 500 / 1.05, next.sub(OFFSET).length() * 20, 1e-10);
    }

    @Test
    void brakeOpposesRelativeDriftAndStopsInTargetFrame() {
        // Reference drift points right; target-relative drift points left. Right thrust is braking
        // only in the target frame.
        var before = new Point(2, 0, 0);
        var push = new Point(1, 0, 0);
        var next = SpaceServer.cruise(before, before, push, true, OFFSET, CAPS);
        point(new Point(2.1, 0, 0), next);
        var relative = NOSE.scale(40);
        int ticks = 0;
        while (relative.length() > 1e-10 && ticks++ < 400) {
            var drift = relative.add(OFFSET);
            relative =
                    SpaceServer.cruise(
                                    drift,
                                    drift.sub(NOSE.scale(.12)),
                                    NOSE.scale(-1),
                                    true,
                                    OFFSET,
                                    CAPS)
                            .sub(OFFSET);
        }
        assertTrue(ticks < 400);
        point(ZERO, relative);
    }

    @Test
    void forwardThrustRestartsAgainstMovingTargetResidue() {
        var offset = OFFSET;
        var drift = offset.sub(NOSE.scale(.02 / 20));
        for (int i = 0; i < 40; i++) {
            drift =
                    SpaceServer.cruise(
                            drift, drift.add(NOSE.scale(48.0 / 400)), NOSE, true, offset, CAPS);
            offset = offset.add(NOSE.scale(.02 / 20));
        }
        assertTrue(
                drift.sub(offset).dot(NOSE) * 20 > 90,
                "forward speed after 40 ticks: " + drift.sub(offset).dot(NOSE) * 20);
    }

    @Test
    void referenceSwitchPreservesRelativeCapAndBrake() {
        var switchVelocity = new Point(-3, 1, 2);
        for (boolean boost : new boolean[] {false, true})
            for (var push : new Point[] {NOSE, NOSE.scale(-1)}) {
                var before = new Point(2, 3, 41);
                var drift = before.add(push.scale(.12));
                var a = SpaceServer.cruise(before, drift, push, boost, OFFSET, CAPS).sub(OFFSET);
                var offset = OFFSET.sub(switchVelocity);
                var b =
                        SpaceServer.cruise(
                                        before.sub(switchVelocity),
                                        drift.sub(switchVelocity),
                                        push,
                                        boost,
                                        offset,
                                        CAPS)
                                .sub(offset);
                point(a, b);
            }
    }

    @Test
    void noTargetAndReferenceTargetAreBitForBitOldCruise() {
        var random = new java.util.Random(51);
        for (int i = 0; i < 1000; i++) {
            var before =
                    new Point(
                            random.nextDouble() * 80 - 40,
                            random.nextDouble() * 80 - 40,
                            random.nextDouble() * 80 - 40);
            var push =
                    new Point(
                            random.nextDouble() * 2 - 1,
                            random.nextDouble() * 2 - 1,
                            random.nextDouble() * 2 - 1);
            var drift = before.add(push);
            for (boolean boost : new boolean[] {false, true}) {
                var old = SpaceServer.cruise(before, drift, push, boost, CAPS);
                assertEquals(old, SpaceServer.cruise(before, drift, push, boost, null, CAPS));
                assertEquals(old, SpaceServer.cruise(before, drift, push, boost, ZERO, CAPS));
            }
        }
    }

    @Test
    void arrivalStillUsesNearestAfterTargetCruise() throws Exception {
        var drift =
                SpaceServer.cruise(
                        new Point(30, 0, 40), new Point(30, 0, 40), ZERO, true, OFFSET, CAPS);
        var nearestOffset = new Point(2, 3, 4);
        var relative = drift.add(nearestOffset);
        var arrived = SpaceServer.arrival(drift, NOSE, relative, Space.IN);
        var result = SpaceServer.near(arrived, NOSE, relative.add(arrived.sub(drift)), Space.IN);
        point(NOSE.scale(7.5), result.add(nearestOffset));
        var s = Sources.text("SpaceServer");
        assertTrue(
                s.contains(
                        "var relative=model.at(f.planet(),tick).velocity().add(drift).sub(model.at(nearest,tick).velocity())"));
        assertTrue(
                s.contains(
                        "Thrusters.boosted(lead,level.getGameTime()),targetVelocity,SpaceSpeedServer.caps(lead))"));
    }

    @Test
    void cycleIncludesEveryKnownPlanetAndThenNone() {
        var home = PlanetSystem.model();
        var more = new java.util.ArrayList<>(home.more());
        for (int i = 0; i < 6; i++)
            more.add(
                    new GroundEphemeris.Orbit(
                            home.center().add(new Point(100000 + i * 10000, 0, 0)),
                            home.axis(),
                            home.radial(),
                            home.radius(),
                            home.period(),
                            0,
                            home.epoch(),
                            home.b()));
        var model =
                new GroundEphemeris.Model(
                        home.center(),
                        home.axis(),
                        home.radial(),
                        home.radius(),
                        home.period(),
                        home.phase(),
                        home.epoch(),
                        home.a(),
                        home.b(),
                        home.sun(),
                        true,
                        more,
                        home.stars());
        var ship = model.at(0, 0).center();
        var order = SpaceView.nearest(model, ship, 0, model.count());
        int locked = -1;
        for (int id : order) {
            locked = SpaceNavigation.next(model, ship, 0, locked);
            assertEquals(id, locked);
        }
        assertEquals(-1, SpaceNavigation.next(model, ship, 0, locked));
        assertEquals(order.getFirst().intValue(), SpaceNavigation.next(model, ship, 0, 9999));
        assertTrue(order.size() > 4);
    }

    @Test
    void farCandidateAndClientWiring() throws Exception {
        var candidates = new java.util.ArrayList<SpaceNavigation.Candidate>();
        for (int i = 0; i < 8; i++)
            candidates.add(
                    new SpaceNavigation.Candidate(
                            i, new SpaceNavigation.Marker(.5, .5, false, 0), i == 7 ? 0 : 90));
        assertEquals(7, SpaceNavigation.choose(candidates, 800, 400));
        var s = Sources.text("SpaceNavigationClient");
        for (var text :
                new String[] {
                    "GLFW_KEY_G",
                    "event.register(nextKey)",
                    "for(int id=0;id<model.count();id++)",
                    "if(nearest.contains(id)||id==locked)",
                    "SpaceView.nearest(model,eye,tick,4)",
                    "velocity.length()*20,relativeTo,flight.cruiseCap()"
                }) assertTrue(s.contains(text), text);
        for (var lang : new String[] {"en_us", "zh_cn"})
            assertTrue(
                    java.nio.file.Files.readString(
                                    java.nio.file.Path.of(
                                            "src/main/resources/assets/starfold/lang/"
                                                    + lang
                                                    + ".json"))
                            .contains("key.starfold.next_planet"));
    }
}
