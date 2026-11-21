package dev.starfold.universe;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.plan.terrain.TerrainPlan;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;

import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Real planner/evaluator regressions; no level, scatter, blocks supplied, or game launch. */
class PlanetSpawnDryLandTest {
    private static final Face FACE = Face.TOP;
    private static final int SIZE = 1024, CENTRE = SIZE / 2;
    private static PlanetType home;

    @BeforeAll
    static void boot() throws Exception {
        PlanetBiomeFixtures.boot();
        var json =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "modpack/kubejs/data/starfold_worlds/starfold/planet_type/home.json")))
                        .getAsJsonObject();
        // The core launcher has no Create registry. Keep the unchanged vanilla ore prefix;
        // removing later ores affects neither the planned heights/water nor the surface strata.
        var ores = json.getAsJsonObject("terrain").getAsJsonArray("ores");
        var prefix = new JsonArray();
        var expected =
                List.of(
                        "minecraft:iron_ore",
                        "minecraft:copper_ore",
                        "minecraft:coal_ore",
                        "minecraft:gold_ore");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), ores.get(i).getAsJsonObject().get("block").getAsString());
            prefix.add(ores.get(i).deepCopy());
        }
        json.getAsJsonObject("terrain").add("ores", prefix);
        home =
                PlanetType.CODEC
                        .parse(
                                RegistryOps.create(JsonOps.INSTANCE, PlanetBiomeFixtures.lookup),
                                json)
                        .getOrThrow();
    }

    static Stream<Arguments> openings() {
        return Arrays.stream(
                        new long[] {
                            20261009L, 0, 1, -1, 42, 12345, 8675309, Long.MAX_VALUE, 20261001L
                        })
                .boxed()
                .flatMap(seed -> Stream.of(Arguments.of(seed, 1024), Arguments.of(seed, 2048)));
    }

    @ParameterizedTest(name = "terrain seed={0}, home size={1}")
    @MethodSource("openings")
    void homeSeedsChooseRealDrySupportedGroundWithLandingClearance(long seed, int size) {
        var terrain = new PlanetTerrain(seed, PlanetTerrain.VERSION, home.generation(size));
        int centre = size / 2;
        if (seed == Long.MAX_VALUE || seed == 20261009L && size == 2048) {
            int ground = terrain.groundY(FACE, centre, centre);
            assertTrue(ground >= 129, "regression must pass the old nominal sea-level check");
            assertTrue(
                    terrain.submerged(FACE, centre, centre, ground),
                    "regression must exercise raised local water at the old centre spawn");
        }
        assertSafe(terrain, PlanetSpawn.dryLand(terrain, FACE));
    }

    @Test
    void preservesAValidCentre() {
        var terrain = flat("minecraft:stone", 150, false, List.of());
        assertArrayEquals(new int[] {CENTRE, CENTRE}, PlanetSpawn.dryLand(terrain, FACE));
    }

    @Test
    void coldDryGroundWithARealSnowCapKeepsTheCentre() {
        var terrain = flat("minecraft:stone", 150, false, List.of(), -10);
        var column = terrain.column(FACE, CENTRE, CENTRE);
        assertTrue(terrain.temperature(FACE, CENTRE, CENTRE) < 0);
        assertTrue(column.state(150).is(Blocks.STONE));
        assertTrue(column.state(151).is(Blocks.SNOW), "production cold-column snow cap");
        assertTrue(column.state(152).isAir());
        assertTrue(column.state(153).isAir());
        int[] spawn = PlanetSpawn.dryLand(terrain, FACE);
        assertArrayEquals(new int[] {CENTRE, CENTRE}, spawn);
        assertSafe(terrain, spawn);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void snowCapStillRequiresBothClearanceCells(int offset) {
        var terrain = flat("minecraft:stone", 150, false, List.of(), -10);
        var grid = new TerrainPlan.Grid(SIZE);
        var road =
                new TerrainPlan.Road(
                        List.of(
                                grid.node(FACE, CENTRE / 16, CENTRE / 16),
                                grid.node(FACE, CENTRE / 16 + 1, CENTRE / 16)),
                        List.of(150.0 + offset, 150.0 + offset),
                        List.of(TerrainPlan.RoadMode.BRIDGE),
                        2);
        var fields = fields(150);
        Arrays.fill(fields[TerrainPlan.TEMPERATURE], -10);
        terrain.initializeTerrain(
                new TerrainPlan(
                                42,
                                SIZE,
                                terrain.terrainPlan().recipe(),
                                fields,
                                new short[fields[0].length],
                                new byte[fields[0].length],
                                List.of(),
                                List.of())
                        .withContent(List.of(), List.of(), List.of(road)));
        var column = terrain.column(FACE, CENTRE, CENTRE);
        assertTrue(column.state(151).is(Blocks.SNOW), "bridge must leave the snow cap intact");
        assertTrue(column.state(150 + offset).is(Blocks.OAK_PLANKS));
        int[] spawn = PlanetSpawn.dryLand(terrain, FACE);
        assertArrayEquals(new int[] {CENTRE - 16, CENTRE - 16}, spawn);
        assertTrue(terrain.column(FACE, spawn[0], spawn[1]).state(151).is(Blocks.SNOW));
        assertSafe(terrain, spawn);
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:magma_block", "minecraft:powder_snow"})
    void snowCapDoesNotMakeHazardousSupportSafe(String block) {
        var terrain = flat(block, 150, false, List.of(), -10);
        assertTrue(terrain.column(FACE, CENTRE, CENTRE).state(151).is(Blocks.SNOW));
        assertThrows(IllegalStateException.class, () -> PlanetSpawn.dryLand(terrain, FACE));
    }

    @Test
    void snowCapNeedsACompleteColumnAboveIt() {
        int ground = PlanetTerrain.MAX_Y - 3;
        var terrain = flat("minecraft:stone", ground, false, List.of(), -10);
        var column = terrain.column(FACE, CENTRE, CENTRE);
        assertTrue(column.state(ground + 1).is(Blocks.SNOW));
        assertTrue(column.state(ground + 2).isAir());
        assertEquals(column.end(), ground + 3);
        assertTrue(column.state(ground + 3).isAir(), "out-of-range sentinel is AIR");
        assertThrows(IllegalStateException.class, () -> PlanetSpawn.dryLand(terrain, FACE));
    }

    @Test
    void skipsRaisedLakeAndKeepsTheOriginalFirstRingOrder() {
        var terrain = flat("minecraft:stone", 150, false, List.of());
        var plan = terrain.terrainPlan();
        var fields = fields(150);
        Arrays.fill(fields[TerrainPlan.WATER_LEVEL], 170);
        var grid = new TerrainPlan.Grid(SIZE);
        var water = new byte[grid.positions.length];
        water[grid.node(FACE, CENTRE / 16, CENTRE / 16)] = (byte) TerrainPlan.Water.LAKE.ordinal();
        terrain.initializeTerrain(
                new TerrainPlan(
                        42,
                        SIZE,
                        plan.recipe(),
                        fields,
                        new short[water.length],
                        water,
                        List.of(),
                        List.of()));
        assertTrue(terrain.submerged(FACE, CENTRE, CENTRE, 150));
        assertTrue(terrain.column(FACE, CENTRE, CENTRE).state(151).is(Blocks.WATER));
        int[] spawn = PlanetSpawn.dryLand(terrain, FACE);
        assertArrayEquals(new int[] {CENTRE - 16, CENTRE - 16}, spawn);
        assertArrayEquals(spawn, PlanetSpawn.dryLand(terrain, FACE));
        assertSafe(terrain, spawn);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsCarvedSupportAndEitherBlockedHeadroomCell(int offset) {
        var parameters = home.generation(SIZE).recipe().volumes().getFirst().parameters();
        parameters.addProperty("operation", offset == 0 ? "carve" : "fill");
        var volume =
                new TerrainPlan.Volume(
                        "starfold:floating_rock",
                        parameters,
                        PlanetTerrain.direction(
                                home.generation(SIZE).shape(), FACE, CENTRE + .5, CENTRE + .5),
                        150 + offset,
                        4,
                        .2,
                        4,
                        offset == 0 ? TerrainPlan.Operation.CARVE : TerrainPlan.Operation.FILL);
        var terrain = flat("minecraft:stone", 150, false, List.of(volume));
        var column = terrain.column(FACE, CENTRE, CENTRE);
        assertEquals(
                offset == 0,
                column.state(150 + offset).isAir(),
                "fixture must carve support or fill the requested headroom cell");
        if (offset == 2) assertTrue(column.state(151).isAir());
        int[] spawn = PlanetSpawn.dryLand(terrain, FACE);
        assertArrayEquals(new int[] {CENTRE - 16, CENTRE - 16}, spawn);
        assertSafe(terrain, spawn);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"minecraft:magma_block", "minecraft:pointed_dripstone", "minecraft:air"})
    void refusesHazardousOrMissingSupportInsteadOfFallingBack(String block) {
        var terrain = flat(block, 150, false, List.of());
        assertTrue(terrain.column(FACE, CENTRE, CENTRE).state(151).isAir());
        assertThrows(IllegalStateException.class, () -> PlanetSpawn.dryLand(terrain, FACE));
    }

    @Test
    void refusesAnEntirelySubmergedSearchInsteadOfFallingBack() {
        var terrain = flat("minecraft:stone", 150, true, List.of());
        assertThrows(IllegalStateException.class, () -> PlanetSpawn.dryLand(terrain, FACE));
    }

    @Test
    void outOfColumnAirDoesNotCountAsHeadroom() {
        var terrain = flat("minecraft:stone", PlanetTerrain.MAX_Y - 2, false, List.of());
        var column = terrain.column(FACE, CENTRE, CENTRE);
        assertEquals(column.end(), column.ground() + 2);
        assertTrue(column.state(column.ground() + 2).isAir(), "out-of-range sentinel is AIR");
        assertThrows(IllegalStateException.class, () -> PlanetSpawn.dryLand(terrain, FACE));
    }

    private static void assertSafe(PlanetTerrain terrain, int[] spawn) {
        int u = spawn[0], v = spawn[1], ground = terrain.groundY(FACE, u, v);
        var column = terrain.column(FACE, u, v);
        var surface = column.state(ground);
        int top = terrain.surfaceY(FACE, u, v);
        assertAll(
                "spawn=" + u + "," + v,
                () -> assertTrue(ground >= 129),
                () -> assertFalse(terrain.submerged(FACE, u, v, ground)),
                () -> assertTrue(surface.isSolid(), "real solid support"),
                () -> assertTrue(surface.getFluidState().isEmpty()),
                () ->
                        assertFalse(
                                List.of(
                                                Blocks.MAGMA_BLOCK,
                                                Blocks.CACTUS,
                                                Blocks.POWDER_SNOW,
                                                Blocks.POINTED_DRIPSTONE,
                                                Blocks.LAVA,
                                                Blocks.FIRE,
                                                Blocks.SOUL_FIRE,
                                                Blocks.CAMPFIRE,
                                                Blocks.SOUL_CAMPFIRE)
                                        .contains(surface.getBlock())),
                () ->
                        assertTrue(
                                top == ground
                                        || top == ground + 1 && column.state(top).is(Blocks.SNOW)),
                () -> assertTrue(ground >= column.bottom() && top + 2 < column.end()),
                () -> assertTrue(column.state(top + 1).isAir()),
                () -> assertTrue(column.state(top + 2).isAir()));
    }

    private static float[][] fields(int height) {
        var fields = new float[6][new TerrainPlan.Grid(SIZE).positions.length];
        Arrays.fill(fields[TerrainPlan.HEIGHT], height);
        Arrays.fill(fields[TerrainPlan.TEMPERATURE], 20);
        return fields;
    }

    private static PlanetTerrain flat(
            String block, int height, boolean flooded, List<TerrainPlan.Volume> volumes) {
        return flat(block, height, flooded, volumes, 20);
    }

    private static PlanetTerrain flat(
            String block,
            int height,
            boolean flooded,
            List<TerrainPlan.Volume> volumes,
            float temperature) {
        var generation = home.generation(SIZE);
        var json = generation.recipe().json();
        var material = new JsonObject();
        material.addProperty("type", "starfold:surface");
        var parameters = new JsonObject();
        parameters.addProperty("block", block);
        parameters.addProperty("depth_min", 0);
        parameters.addProperty("depth_max", 1024);
        parameters.addProperty("temperature_min", -300);
        parameters.addProperty("temperature_max", 300);
        parameters.addProperty("slope_min", 0);
        parameters.addProperty("shore", false);
        parameters.addProperty("biome", "");
        material.add("parameters", parameters);
        var materials = new JsonArray();
        materials.add(material);
        json.add("materials", materials);
        var recipe = new TerrainRecipe(json);
        var fields = fields(height);
        Arrays.fill(fields[TerrainPlan.TEMPERATURE], temperature);
        Arrays.fill(fields[TerrainPlan.WATER_LEVEL], 170);
        var water = new byte[fields[0].length];
        if (flooded) Arrays.fill(water, (byte) TerrainPlan.Water.LAKE.ordinal());
        var plan =
                new TerrainPlan(
                                42,
                                SIZE,
                                recipe,
                                fields,
                                new short[water.length],
                                water,
                                List.of(),
                                List.of())
                        .withContent(List.of(), volumes, List.of());
        var terrain = new PlanetTerrain(42, PlanetTerrain.VERSION, generation.withRecipe(recipe));
        terrain.initializeTerrain(plan);
        return terrain;
    }
}
