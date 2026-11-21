package dev.starfold.worldgen.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetEnvironment;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.plan.terrain.TerrainPlanner;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;
import dev.starfold.worldgen.plan.terrain.TerrainRegistries;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

class HomeOceanPaletteTest {
    @BeforeAll
    static void boot() throws Exception {
        PlanetPlanFixtures.boot();
    }

    @AfterAll
    static void close() throws Exception {
        PlanetPlanFixtures.close();
    }

    private static TerrainRecipe recipe() throws Exception {
        var json =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "modpack/kubejs/data/starfold_worlds/starfold/planet_type/home.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("terrain");
        // This core-only launcher omits Create. These tests cover wet surface rules, not ores;
        // leave the complete ore table to the pack contract and full-pack runtime audits.
        json.add("ores", new com.google.gson.JsonArray());
        return new TerrainRecipe(json);
    }

    private static String biome(
            TerrainRecipe recipe, double temperature, double height, boolean coast) {
        var context =
                new TerrainRegistries.BiomeContext(temperature, .5, height, false, coast, 0, 0);
        return recipe.biomes().stream()
                .filter(rule -> rule.matches(context))
                .findFirst()
                .orElseThrow()
                .biomeId();
    }

    private static BlockState material(
            TerrainRecipe recipe, String biome, int depth, int waterDepth) {
        var context =
                new TerrainRegistries.MaterialContext(
                        "minecraft:" + biome,
                        depth,
                        0,
                        15,
                        false,
                        Blocks.STONE.defaultBlockState(),
                        waterDepth);
        return recipe.materials().stream()
                .map(rule -> rule.material(context))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(context.substrate());
    }

    @Test
    void marineClimateSelectsWarmCoralsWithoutTurningTheWholeOceanWarm() throws Exception {
        var recipe = recipe();
        for (var pair :
                new Object[][] {
                    {-10., "frozen_ocean", "deep_frozen_ocean"},
                    {2., "cold_ocean", "deep_cold_ocean"},
                    {12., "ocean", "deep_ocean"},
                    {19., "lukewarm_ocean", "deep_lukewarm_ocean"},
                    {25., "warm_ocean", "deep_lukewarm_ocean"}
                }) {
            assertEquals("minecraft:" + pair[1], biome(recipe, (double) pair[0], 120, false));
            assertEquals("minecraft:" + pair[2], biome(recipe, (double) pair[0], 90, false));
        }
        assertEquals("minecraft:beach", biome(recipe, 25, 130, true));
        assertEquals("minecraft:birch_forest", biome(recipe, 15, 145, false));
    }

    @Test
    void submergedBedsAndBanksUseDifferentMaterials() throws Exception {
        var recipe = recipe();
        for (String biome :
                new String[] {"ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean"}) {
            assertTrue(material(recipe, biome, 0, 8).is(Blocks.SAND), biome);
            assertTrue(material(recipe, biome, 0, 0).is(Blocks.GRASS_BLOCK), biome + " dry bank");
        }
        for (String biome :
                new String[] {
                    "deep_ocean",
                    "cold_ocean",
                    "frozen_ocean",
                    "deep_cold_ocean",
                    "deep_frozen_ocean"
                }) assertTrue(material(recipe, biome, 0, 30).is(Blocks.GRAVEL), biome);
        for (String biome : new String[] {"river", "swamp"}) {
            assertTrue(material(recipe, biome, 0, 2).is(Blocks.CLAY));
            assertTrue(material(recipe, biome, 0, 0).is(Blocks.GRASS_BLOCK));
            assertTrue(material(recipe, biome, 2, 2).is(Blocks.DIRT));
        }
        assertTrue(material(recipe, "beach", 0, 0).is(Blocks.SAND));
        assertTrue(
                material(recipe, "forest", 0, 8).is(Blocks.GRASS_BLOCK),
                "marine rules must stay scoped");
    }

    @Test
    void optionalWaterDepthBoundsAreStrictAndKeepOldRecipesCompatible() throws Exception {
        var recipe = recipe();
        var json = recipe.json();
        var rule =
                json.getAsJsonArray("materials")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("parameters");
        rule.addProperty("water_depth_min", -1);
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, json).isError());
        rule.addProperty("water_depth_min", 3);
        rule.addProperty("water_depth_max", 2);
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, json).isError());
        rule.addProperty("water_depth_max", 6);
        var bounded = new TerrainRecipe(json).materials().get(1);
        for (int water : new int[] {0, 2, 7})
            assertNull(
                    bounded.material(
                            new TerrainRegistries.MaterialContext(
                                    "minecraft:warm_ocean",
                                    0,
                                    0,
                                    25,
                                    false,
                                    Blocks.STONE.defaultBlockState(),
                                    water)));
        assertTrue(
                bounded.material(
                                new TerrainRegistries.MaterialContext(
                                        "minecraft:warm_ocean",
                                        0,
                                        0,
                                        25,
                                        false,
                                        Blocks.STONE.defaultBlockState(),
                                        3))
                        .is(Blocks.SAND));
        assertEquals(
                TerrainPlanTest.recipe("home").json(),
                new TerrainRecipe(TerrainPlanTest.recipe("home").json()).json());
    }

    @Test
    void generatedColumnsUseTheSameWetBedsOnAllSixFacesAndMultipleSeeds() throws Exception {
        var recipe = recipe();
        Set<Face> covered = new HashSet<>();
        for (long seed : new long[] {42, 20261001}) {
            var generation =
                    new PlanetGeneration(
                            "home",
                            PlanetGeneration.SURFACE_VERSION,
                            recipe,
                            512,
                            191,
                            "core_shell",
                            false,
                            false,
                            false,
                            PlanetEnvironment.defaults(true, 512));
            var terrain = new PlanetTerrain(seed, PlanetTerrain.VERSION, generation);
            terrain.initializeTerrain(new TerrainPlanner(seed, 512, recipe).generate());
            for (Face face : Face.VALUES) {
                int checked = 0;
                for (int u = 24; u < 488 && checked < 3; u += 24) {
                    for (int v = 24; v < 488 && checked < 3; v += 24) {
                        int ground = terrain.groundY(face, u, v);
                        var liquid = terrain.liquid(face, u, v);
                        if (liquid == null
                                || liquid.level() - ground < 5
                                || !terrain.biome(face, u, v).contains("ocean")) continue;
                        var column = terrain.visibleBand(face, u, v, ground, ground - 4);
                        String biome = terrain.biome(face, u, v).substring("minecraft:".length());
                        assertEquals(
                                material(recipe, biome, 0, liquid.level() - ground - 1),
                                column.state(ground),
                                seed + " " + face + " " + u + "," + v);
                        assertFalse(column.state(ground + 1).getFluidState().isEmpty());
                        checked++;
                        covered.add(face);
                    }
                }
                assertTrue(checked > 0, seed + " " + face + " missing sampled ocean");
            }
        }
        assertEquals(Set.of(Face.VALUES), covered);
    }
}
