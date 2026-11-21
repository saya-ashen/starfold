package dev.starfold.worlds;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetBiomeFixtures;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/** Registry and search contracts only; these do not simulate generated resources or a live arrival. */
class SurvivalSpawnTest {
    @BeforeAll
    static void registries() {
        PlanetBiomeFixtures.boot();
    }

    @Test
    void findsTheFirstAcceptableSiteInDeterministicNearbyRings() {
        var calls = new AtomicInteger();
        assertArrayEquals(
                new int[] {1024, 1024},
                SurvivalSpawn.candidate(
                        2048,
                        (u, v) -> {
                            calls.incrementAndGet();
                            return true;
                        }));
        assertEquals(1, calls.get());
        // Earlier nearby sites fail. Find the same witness on repeated searches.
        for (int i = 0; i < 2; i++)
            assertArrayEquals(
                    new int[] {1008, 1040},
                    SurvivalSpawn.candidate(2048, (u, v) -> u == 1008 && v == 1040));
    }

    @Test
    void rejectionIsFiniteHasNoDuplicateVisitsAndStaysInsideTheFace() {
        for (int size : new int[] {256, 1024, 2048}) {
            var visited = new HashSet<Long>();
            assertNull(
                    SurvivalSpawn.candidate(
                            size,
                            (u, v) -> {
                                assertTrue(
                                        u >= 69 && v >= 69 && u + 68 < size && v + 68 < size,
                                        "64-block water disk plus four-block clearing must stay on this face");
                                assertTrue(visited.add(((long) u << 32) | (v & 0xffffffffL)));
                                return false;
                            }));
            assertFalse(visited.isEmpty());
            assertTrue(visited.size() <= 9409);
        }
        assertNull(
                SurvivalSpawn.candidate(128, (u, v) -> fail("Face too small for resource disk")));
    }

    @Test
    void treeFeaturesAndLandFoodAreBothRequiredFromRegisteredBiomeData() {
        var forest = PlanetBiomeFixtures.palette.getOrThrow(Biomes.FOREST);
        assertTrue(SurvivalSpawn.eligible(forest));
        assertTrue(SurvivalSpawn.eligible(PlanetBiomeFixtures.palette.getOrThrow(Biomes.SAVANNA)));
        assertFalse(SurvivalSpawn.eligible(PlanetBiomeFixtures.palette.getOrThrow(Biomes.OCEAN)));
        assertFalse(
                SurvivalSpawn.eligible(
                        copy(forest, forest.getGenerationSettings(), MobSpawnSettings.EMPTY)));
        assertFalse(
                SurvivalSpawn.eligible(
                        copy(forest, BiomeGenerationSettings.EMPTY, forest.getMobSettings())));
    }

    private static Biome copy(
            Biome source, BiomeGenerationSettings features, MobSpawnSettings mobs) {
        return new Biome.BiomeBuilder()
                .hasPrecipitation(true)
                .temperature(.7f)
                .downfall(.8f)
                .specialEffects(source.getSpecialEffects())
                .mobSpawnSettings(mobs)
                .generationSettings(features)
                .build();
    }
}
