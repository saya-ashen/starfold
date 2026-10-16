package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.universe.PlanetTypeTest;
import dev.starfold.universe.Universe;
import dev.starfold.universe.UniverseLayout;
import dev.starfold.worldgen.PlanetEcologyTest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Literal numerical contracts for the celestial builders, independent of their representation. */
class CelestialBaselineTest {
    private static final String RESOURCE = "/celestial/C1-baseline.tsv";
    private static final long[] TICKS = {0, 1, 12345, 1_000_000, 999_999_999_999L};
    private static final List<Point> POINTS =
            List.of(
                    new Point(0, 0, 0),
                    new Point(0, 0, 8192),
                    new Point(0, 0, 16384),
                    new Point(1, -2, 3),
                    new Point(-6000, 2048, 8192),
                    new Point(8192, 0, 8192),
                    new Point(24576, 0, 8192),
                    new Point(49152, 0, 8192),
                    new Point(98304, 1024, -12000),
                    new Point(-49152, -8192, 49152),
                    new Point(200000, 30000, -150000),
                    new Point(-1000000, 400000, 2000000));

    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private static Map<String, GroundEphemeris.Model> models() throws Exception {
        var models = new LinkedHashMap<String, GroundEphemeris.Model>();
        var solar = GroundSolar.defaults();
        models.put("solar", solar);
        models.put("solar-spinning", GroundEphemeris.spinning(solar, true, 12345));
        models.put("groups-2", PlanetSystem.model(new int[] {2}));
        models.put("groups-3", PlanetSystem.model(new int[] {3}));
        models.put("groups-2-3", PlanetSystem.model(new int[] {2, 3}));
        models.put("groups-4-1-2", PlanetSystem.model(new int[] {4, 1, 2}));
        models.put(
                "authored",
                AuthoredSystemTest.read(AuthoredSystemTest.definition())
                        .materialize(42)
                        .sky()
                        .model());
        var layout =
                UniverseLayout.CODEC
                        .parse(
                                PlanetTypeTest.ops(),
                                JsonParser.parseString(
                                        Files.readString(
                                                Path.of(
                                                        "src/main/resources/data/starfold/starfold/universe/default.json"))))
                        .getOrThrow();
        var home = PlanetSystem.model(new int[] {2, 3});
        var universe = new Universe(List.of(), List.of());
        for (int x = 2; x <= 4 && universe.stars().size() < 2; x++)
            for (int z = 2; z <= 4 && universe.stars().size() < 2; z++)
                universe.discover(20261004L, layout, home, x, z, Universe.CAPACITY - home.count());
        assertEquals(2, universe.stars().size(), "fixture must include discovered suns");
        assertFalse(universe.bodies().isEmpty(), "fixture must include discovered planets");
        models.put("discovered", universe.model(home));
        return models;
    }

    private static double[] point(Point p) {
        return new double[] {p.x(), p.y(), p.z()};
    }

    private static double[] frame(GroundEphemeris.Frame f) {
        var c = f.center();
        var q = f.rotation();
        var v = f.velocity();
        var a = f.angular();
        return new double[] {
            c.x(), c.y(), c.z(), q.x, q.y, q.z, q.w, v.x(), v.y(), v.z(), a.x(), a.y(), a.z()
        };
    }

    private static void sample(
            Map<String, double[]> values, String name, GroundEphemeris.Model model) {
        values.put(
                name + "/bounds",
                new double[] {model.count(), model.extent(), model.drift(), model.turn()});
        int stored = model.count();
        for (int slot = 0; slot < stored; slot++) {
            for (long tick : TICKS)
                values.put(name + "/frame/" + slot + "/" + tick, frame(model.at(slot, tick)));
            for (int p = 0; p < POINTS.size(); p++)
                values.put(
                        name + "/least/" + slot + "/" + p,
                        new double[] {model.least(slot, POINTS.get(p))});
        }
        var stars = model.everyStar();
        values.put(name + "/stars", new double[] {stars.size()});
        for (int s = 0; s < stars.size(); s++) {
            var star = stars.get(s);
            var at = point(star.position());
            values.put(name + "/star-list/" + s, new double[] {at[0], at[1], at[2], star.radius()});
        }
        for (int p = 0; p < POINTS.size(); p++) {
            var star = model.star(POINTS.get(p));
            var at = point(star.position());
            values.put(name + "/star/" + p, new double[] {at[0], at[1], at[2], star.radius()});
        }
    }

    private static Map<String, double[]> samples() throws Exception {
        var values = new LinkedHashMap<String, double[]>();
        for (var entry : models().entrySet()) sample(values, entry.getKey(), entry.getValue());
        return values;
    }

    private static Map<String, double[]> literals() throws Exception {
        var values = new LinkedHashMap<String, double[]>();
        try (var stream = CelestialBaselineTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, "the numerical fixture must be present");
            try (var reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    var columns = line.split("\\t");
                    var numbers = new double[columns.length - 1];
                    for (int i = 1; i < columns.length; i++)
                        numbers[i - 1] = Double.parseDouble(columns[i]);
                    assertNull(
                            values.put(columns[0], numbers), "duplicate fixture key " + columns[0]);
                }
            }
        }
        return values;
    }

    @Test
    void everyBuilderMatchesLiteralFramesLightsAndBounds() throws Exception {
        var expected = literals();
        var actual = samples();
        assertEquals(
                expected.keySet(), actual.keySet(), "no numerical contract may silently disappear");
        for (var entry : expected.entrySet()) {
            var a = actual.get(entry.getKey());
            var e = entry.getValue();
            assertEquals(e.length, a.length, entry.getKey());
            for (int i = 0; i < e.length; i++)
                assertEquals(
                        e[i],
                        a[i],
                        1e-9 * Math.max(1e-12, Math.abs(e[i])),
                        entry.getKey() + " component " + i);
        }
    }
}
