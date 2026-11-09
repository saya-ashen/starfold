package dev.starfold.universe;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.SunBoundary;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.space.HaulerFlightTimes;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** P1's centre-distance, static-endpoint estimate, including acceleration but not arrival/landing. */
public class UniverseScaleTest {
    public static double seconds(double distance, boolean boost) {
        return HaulerFlightTimes.seconds(distance, boost);
    }

    public static void duration(double distance) {
        double normal = seconds(distance, false);
        assertTrue(normal > 0 && Double.isFinite(normal));
        assertTrue(normal >= distance / 20000);
        assertEquals(normal / 2, seconds(distance, true), 1e-9);
    }

    public static void clearChord(Point a, Point b, Point sun, double radius) {
        Point delta = b.sub(a);
        double t = Math.clamp(sun.sub(a).dot(delta) / delta.dot(delta), 0, 1);
        assertTrue(a.add(delta.scale(t)).sub(sun).length() > radius + SunBoundary.MARGIN);
    }

    @BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    @Test
    void generatedSiblingPairsHaveClearChordsAndFiniteUncappedEstimates() throws Exception {
        var home = PlanetSystem.model();
        var layout = UniverseTest.layout("default");
        assertEquals(1, layout.density());
        for (long seed = 0; seed < 16; seed++) {
            var model = Universe.generate(seed, layout, home).model(home);
            for (double tick : new double[] {0, 6000, 17000, 1234567}) {
                for (int i = 0; i < model.count(); i++)
                    for (int j = i + 1; j < model.count(); j++) {
                        if (model.orbit(i).parent() != model.orbit(j).parent()) continue;
                        var a = model.at(i, tick).center();
                        var b = model.at(j, tick).center();
                        duration(a.sub(b).length());
                        var sun = model.frame(model.orbit(i).parent(), tick).center();
                        clearChord(a, b, sun, model.bodies().get(model.orbit(i).parent()).radius());
                    }
            }
        }
    }

    @Test
    void axialNeighboursKeepMinimumGridSeparationAtExtremePlanetPhases() throws Exception {
        var home = PlanetSystem.model();
        var layout = UniverseTest.layout("default");
        var universe = Universe.generate(42, layout, home);
        var model = universe.model(home);
        assertEquals(9, model.everyStar().size(), "density one leaves no empty neighbouring cells");
        // A full grid gap, not half a jittered gap, is now the minimum separation.
        for (int i = 0; i < model.everyStar().size(); i++)
            for (int j = i + 1; j < model.everyStar().size(); j++) {
                var a = model.everyStar().get(i).position();
                var b = model.everyStar().get(j).position();
                double d = a.sub(b).length();
                assertTrue(d >= layout.gap() - 1e-8);
                if (Math.abs(d - layout.gap()) > 1e-8) continue;
                duration(d);
                double radius = home.orbit(0).radius();
                duration(d - 2 * radius);
                duration(d + 2 * radius);
            }
    }
}
