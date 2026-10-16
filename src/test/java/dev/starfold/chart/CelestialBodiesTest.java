package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CelestialBodiesTest {
    private static final Point Z = new Point(0, 0, 1);
    private static final GroundEphemeris.Spin STILL =
            new GroundEphemeris.Spin(false, GroundEphemeris.Y, new Quaterniond(), 0, 36000, 0);

    private static GroundEphemeris.Body light(Point position) {
        return new GroundEphemeris.Body(new GroundEphemeris.Fixed(position), 512, true, -1);
    }

    private static GroundEphemeris.Body orbit(int parent, double radius, double period, int slot) {
        return new GroundEphemeris.Body(
                new GroundEphemeris.Orbit(
                        parent, GroundEphemeris.Y, Z, radius, period, .4, 0, STILL),
                0,
                false,
                slot);
    }

    private static void close(Point expected, Point actual) {
        assertTrue(expected.sub(actual).length() < 1e-7, expected + " != " + actual);
    }

    @Test
    void slotOrderIsIndependentOfBodyOrderAndTheListIsImmutable() {
        var source =
                new ArrayList<>(
                        List.of(
                                light(new Point(10, 20, 30)),
                                orbit(0, 9000, 24000, 1),
                                orbit(0, 20000, 40000, 0)));
        var model = new GroundEphemeris.Model(source);
        source.clear();
        assertEquals(2, model.count());
        assertEquals(2, model.index(0));
        assertEquals(1, model.index(1));
        assertEquals(20000, model.orbit(0).radius());
        assertEquals(9000, model.orbit(1).radius());
        assertThrows(UnsupportedOperationException.class, () -> model.bodies().clear());
        assertThrows(IllegalArgumentException.class, () -> model.at(2, 0));
        assertThrows(IllegalArgumentException.class, () -> model.spin(-1));
        assertThrows(IllegalArgumentException.class, () -> model.least(2, GroundEphemeris.ZERO));
    }

    @Test
    void missingLightsDuplicateOrMissingSlotsAndNonEarlierParentsAreRefused() {
        var sun = light(GroundEphemeris.ZERO);
        assertThrows(IllegalArgumentException.class, () -> new GroundEphemeris.Model(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Model(
                                List.of(
                                        new GroundEphemeris.Body(
                                                new GroundEphemeris.Fixed(GroundEphemeris.ZERO),
                                                0,
                                                false,
                                                -1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundEphemeris.Model(List.of(sun, orbit(1, 9000, 24000, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Model(
                                List.of(sun, orbit(2, 9000, 24000, 0), orbit(0, 20000, 40000, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Model(
                                List.of(sun, orbit(0, 9000, 24000, 0), orbit(0, 20000, 40000, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundEphemeris.Model(List.of(sun, orbit(0, 9000, 24000, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(GroundEphemeris.ZERO), 0, true, -1));
    }

    @Test
    void moonComposesTranslationAndVelocityButNotParentRotation() {
        var reference = new Quaterniond().rotationXYZ(.2, -.3, .4);
        var spin = new GroundEphemeris.Spin(true, GroundEphemeris.Y, reference, .5, 48000, 0);
        var planet =
                new GroundEphemeris.Body(
                        new GroundEphemeris.Orbit(
                                0, GroundEphemeris.Y, Z, 9000, 24000, .4, 0, spin),
                        0,
                        false,
                        0);
        var model =
                new GroundEphemeris.Model(
                        List.of(light(new Point(10, 20, 30)), planet, orbit(1, 3000, 12000, 1)));
        var independent =
                new GroundEphemeris.Model(
                        List.of(light(GroundEphemeris.ZERO), orbit(0, 3000, 12000, 0)));
        for (double time : new double[] {1, 1234.5, 12000, 1000000, 999999999999.0}) {
            var parent = model.at(0, time);
            var moon = model.at(1, time);
            var offset = independent.at(0, time);
            close(parent.center().add(offset.center()), moon.center());
            close(parent.velocity().add(offset.velocity()), moon.velocity());
            assertEquals(offset.rotation(), moon.rotation());
            assertEquals(offset.angular(), moon.angular());
            assertEquals(parent, model.parent(1, time));
        }
        for (double time : new double[] {1, 1234.5, 23999, 1000001}) {
            var numerical =
                    model.at(1, time + .05)
                            .center()
                            .sub(model.at(1, time - .05).center())
                            .scale(10);
            close(model.at(1, time).velocity(), numerical);
        }
    }

    @Test
    void ancestorEnvelopesAndMotionBoundsRemainConservative() {
        var sun = light(new Point(40000, -1000, 8000));
        var planet = orbit(0, 20000, 24000, 1);
        var moon = orbit(1, 12000, 12000, 0);
        var model = new GroundEphemeris.Model(List.of(sun, planet, moon));
        var worlds =
                List.of(
                        GroundEphemeris.ZERO,
                        new Point(100000, 40000, -60000),
                        new Point(40000, -1000, 8000));
        assertEquals(new Point(40000, -1000, 8000), model.origin());
        assertEquals(20000 + 12000 + 8192, model.extent());
        assertEquals(4 * Math.PI * (20000.0 / 24000 + 12000.0 / 12000), model.drift());
        for (int tick = 0; tick <= 48000; tick += 137) {
            for (int slot = 0; slot < model.count(); slot++) {
                var frame = model.at(slot, tick);
                assertTrue(
                        frame.center().sub(model.origin()).length() + 8192
                                <= model.extent() + 1e-8);
                assertTrue(frame.angular().length() <= model.turn() + 1e-15);
                for (var world : worlds)
                    assertTrue(
                            model.least(slot, world) <= world.sub(frame.center()).length() + 1e-8);
            }
            assertTrue(
                    model.at(0, tick).velocity().sub(model.at(1, tick).velocity()).length()
                            <= model.drift());
        }
        var spun = GroundEphemeris.spinning(model, true, 12345);
        for (int slot = 0; slot < model.count(); slot++) {
            assertEquals(model.at(slot, 12345).center(), spun.at(slot, 12345).center());
            assertEquals(model.at(slot, 12345).velocity(), spun.at(slot, 12345).velocity());
            assertEquals(model.at(slot, 12345).rotation(), spun.at(slot, 12345).rotation());
        }
        assertEquals(48000, spun.spin(0).period());
        assertEquals(72000, spun.spin(1).period());
        assertEquals(model.least(0, worlds.get(0)), spun.least(0, worlds.get(0)));
        assertEquals(model.extent(), spun.extent());
        assertEquals(model.drift(), spun.drift());
        assertTrue(spun.turn() >= spun.at(0, 20000).angular().length());
    }

    @Test
    void lightsKeepListOrderAndNearestTiesWhilePrimaryIsNotNearest() {
        var first = light(new Point(-100, 0, 0));
        var second = light(new Point(100, 0, 0));
        var model = new GroundEphemeris.Model(List.of(first, second, orbit(1, 9000, 24000, 0)));
        assertEquals(new Point(-100, 0, 0), model.primary().position());
        assertEquals(model.primary(), model.star(GroundEphemeris.ZERO));
        assertEquals(new Point(100, 0, 0), model.star(new Point(1, 0, 0)).position());
        assertEquals(new Point(-1, 0, 0), GroundSolar.direction(model, new Point(1, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> model.everyStar().clear());
    }

    @Test
    void singleSurfaceDefaultHasNoHiddenCompanionAndGuidanceOffSelectsHome() {
        var model = GroundSolar.defaults();
        assertEquals(2, model.bodies().size());
        assertEquals(1, model.count());
        assertEquals(0, GroundGuidance.Options.OFF.target());
        assertThrows(IllegalArgumentException.class, () -> model.at(1, 0));
        assertThrows(IllegalArgumentException.class, () -> model.spin(1));
        assertThrows(IllegalArgumentException.class, () -> model.least(1, GroundEphemeris.ZERO));
        PlanetShape.system(new int[] {2048}, new int[] {191});
        try {
            assertThrows(IllegalArgumentException.class, () -> GroundCosmos.valid(1));
        } finally {
            PlanetShape.reset();
        }
    }
}
