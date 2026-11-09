package dev.starfold.universe;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

class UniverseSunDefaultsTest {
    @BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    /** Original PRNG protocol, independently checking the production generator's downstream draws. */
    private void checkSystem(
            Universe universe, int starIndex, SplittableRandom random, UniverseLayout layout) {
        int oldRadius = 384 + random.nextInt(257);
        var star = universe.stars().get(starIndex);
        assertEquals(oldRadius * 1.5, star.radius());
        assertTrue(star.radius() >= 576 && star.radius() <= 960);
        int count =
                layout.minPlanets() + random.nextInt(layout.maxPlanets() - layout.minPlanets() + 1);
        double phase = random.nextDouble() * 2 * Math.PI;
        var model = universe.model(PlanetSystem.model());
        var children =
                universe.bodies().stream()
                        .filter(
                                b ->
                                        UniverseTest.source(universe, b.parent())
                                                .position()
                                                .equals(star.position()))
                        .toList();
        assertEquals(count, children.size());
        int weights = layout.types().stream().mapToInt(UniverseLayout.Choice::weight).sum();
        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(weights);
            var choice = layout.types().getFirst();
            for (var c : layout.types()) {
                if (pick < c.weight()) {
                    choice = c;
                    break;
                }
                pick -= c.weight();
            }
            double expectedPhase =
                    count == 2 ? phase + i * 2 * Math.PI / 3 : random.nextDouble() * 2 * Math.PI;
            long terrainSeed = random.nextLong();
            var body = children.get(i);
            assertEquals(expectedPhase, body.phase());
            assertEquals(terrainSeed, body.planet().seed());
            assertEquals(choice.typeId(), body.planet().typeId());
            assertEquals(
                    choice.type()
                            .value()
                            .planet(terrainSeed, java.util.Optional.empty())
                            .generation(),
                    body.planet().generation());
            double orbit = 17800 + (count == 2 ? 0 : i) * layout.ring();
            assertEquals(orbit, body.radius());
            assertEquals(20 * 2 * Math.PI * Math.sqrt(orbit * orbit * orbit / SystemDefinition.STAR_GM), body.period());
        }
    }

    @Test
    void finiteGenerationKeepsOriginalBoundDrawsPlanetSeedsTypesAndPhases() throws Exception {
        var layout = UniverseTest.layout("small");
        for (long seed : new long[] {0, 1, 42, 12345, 987654321}) {
            var random = new SplittableRandom(seed ^ 0x43756265506C616EL);
            int count =
                    layout.minStars() + random.nextInt(layout.maxStars() - layout.minStars() + 1);
            var universe = Universe.generate(seed, layout, PlanetSystem.model());
            assertEquals(count, universe.stars().size());
            for (int i = 0; i < count; i++) checkSystem(universe, i, random, layout);
        }
    }

    @Test
    void endlessDiscoveryKeepsCellMappingAndDownstreamRandomStream() throws Exception {
        var layout = UniverseTest.layout("default");
        for (long seed : new long[] {0, 1, 42, 12345})
            for (int[] cell : new int[][] {{1, 0}, {-3, 7}, {10, -10}}) {
                int x = cell[0], z = cell[1];
                var random =
                        new SplittableRandom(
                                (seed ^ 0x43756265506C616EL)
                                        + x * 0x9E3779B97F4A7C15L
                                        + z * 0xC2B2AE3D27D4EB4FL);
                assertTrue(random.nextDouble() < layout.density());
                var universe = new Universe(List.of(), List.of());
                assertTrue(universe.discover(seed, layout, PlanetSystem.model(), x, z, 900));
                assertEquals(
                        PlanetSystem.model()
                                .origin()
                                .add(new Point(x * layout.gap(), 0, z * layout.gap())),
                        universe.stars().getFirst().position());
                checkSystem(universe, 0, random, layout);
                var tag = universe.save(new CompoundTag(), PlanetTypeFixtures.lookup());
                var restored =
                        Universe.load(
                                tag, PlanetTypeFixtures.lookup(), UniverseTest.reloadedIds(universe));
                assertEquals(universe.catalog(), restored.catalog());
            }
    }

    @Test
    void explicitAuthoredRadiusIsAuthoritativeThroughCodecMaterializationAndSave()
            throws Exception {
        for (double radius : new double[] {128, 512, 913.5}) {
            var json =
                    JsonParser.parseString(
                            "{\"suns\":[{\"position\":[0,0,17800],\"radius\":"
                                    + radius
                                    + ",\"planets\":[{\"type\":\"starfold:rocky\",\"size\":1024,\"orbit\":{\"radius\":17800}}]}]}");
            var definition = SystemDefinition.CODEC.parse(PlanetTypeFixtures.ops(), json).getOrThrow();
            var model = definition.materialize(42).sky().model();
            assertEquals(radius, model.primary().radius());
            var state = new dev.starfold.sky.GroundEphemerisSave(model, 200);
            var tag = state.save(new CompoundTag(), null);
            assertEquals(
                    radius,
                    dev.starfold.sky.GroundEphemerisSave.load(tag, null).model.primary().radius());
        }
    }
}
