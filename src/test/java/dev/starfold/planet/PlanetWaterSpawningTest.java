package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.chart.PlanetShape;
import dev.starfold.sky.Space;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetEnvironment;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.plan.terrain.TerrainPlan;
import dev.starfold.worldgen.plan.terrain.TerrainPlan.Water;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;
import dev.starfold.worldgen.surface.SurfaceTestTags;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

class PlanetWaterSpawningTest {
    private static final BlockPos HOME = new BlockPos(256, 128, 256);

    @BeforeAll
    static void boot() throws Exception {
        PlanetBiomeFixtures.boot();
        SurfaceTestTags.bind();
    }

    @BeforeEach
    void shapes() {
        PlanetShape.system(new int[] {512, 512}, new int[] {191, 191});
    }

    @AfterEach
    void reset() {
        PlanetShape.reset();
    }

    @Test
    void columnLiquidOverridesOceanAndDryShoreUsesAuthoredSeaWithoutFloorOffset() throws Exception {
        var ocean = terrain(128, Water.OCEAN, 128, false);
        var lake = terrain(128, Water.LAKE, 170.25f, false);
        var shore = terrain(128, Water.DRY, 0, false);
        var dryPlanet = terrain(128, Water.DRY, 0, true);
        var inlandLake = terrain(128, Water.LAKE, 170.25f, true);
        assertEquals(128, height(HOME, ocean));
        assertEquals(171, height(HOME, lake));
        assertEquals(128, height(HOME, shore));
        assertEquals(63, height(HOME, dryPlanet));
        assertEquals(171, height(HOME, inlandLake));
    }

    @Test
    void lookupIsIsolatedByStoragePlanetAndNeverRunsInSpaceOrChartGaps() throws Exception {
        var home = terrain(128, Water.OCEAN, 128, false);
        var other = terrain(201, Water.OCEAN, 201, false);
        var lookedUp = new java.util.ArrayList<Integer>();
        IntFunction<PlanetTerrain> lookup =
                id -> {
                    lookedUp.add(id);
                    return id == 0 ? home : other;
                };
        assertEquals(128, PlanetWaterSpawning.seaLevel(HOME, lookup, 63));
        assertEquals(201, PlanetWaterSpawning.seaLevel(HOME.offset(32768, 0, 0), lookup, 63));
        assertEquals(List.of(0, 1), lookedUp);
        IntFunction<PlanetTerrain> forbidden =
                id -> {
                    throw new AssertionError("Unexpected planet lookup " + id);
                };
        for (var pos :
                List.of(
                        BlockPos.containing(Space.X, 128, Space.Z),
                        new BlockPos(-100000, 128, -100000),
                        new BlockPos(2000, 128, 2000)))
            assertEquals(63, PlanetWaterSpawning.seaLevel(pos, forbidden, 63));
    }

    @Test
    void haloAndCanonicalOwnerUseTheSameLiquidColumn() throws Exception {
        var lake = terrain(128, Water.LAKE, 170.25f, false);
        var halo = new BlockPos(-1, 128, 256);
        assertTrue(PlanetAccess.SHARED.inChart(halo));
        var owner = PlanetAccess.SHARED.address(halo).owner();
        assertNotEquals(halo, owner);
        assertEquals(height(owner, lake), height(halo, lake));
        assertEquals(171, height(halo, lake));
    }

    @Test
    void vanillaWaterPredicateRetainsItsDepthWindowAndFluidChecks() throws Exception {
        var ocean = terrain(128, Water.OCEAN, 128, false);
        for (int y : new int[] {114, 115, 128, 129}) {
            var pos = HOME.atY(y);
            assertEquals(y >= 115 && y <= 128, water(pos, ocean, true, true), "Y=" + y);
        }
        assertFalse(water(HOME, ocean, false, true));
        assertFalse(water(HOME, ocean, true, false));
        var lake = terrain(128, Water.LAKE, 170, false);
        assertTrue(water(HOME.atY(157), lake, true, true));
        assertFalse(water(HOME.atY(156), lake, true, true));
        assertFalse(water(HOME.atY(171), lake, true, true));
    }

    @Test
    void vanillaTurtlePredicateRetainsShoreHeightSandAndLightChecks() throws Exception {
        var shore = terrain(128, Water.DRY, 0, false);
        assertTrue(turtle(HOME.atY(131), shore, true, 15));
        assertFalse(turtle(HOME.atY(132), shore, true, 15));
        assertFalse(turtle(HOME, shore, false, 15));
        assertFalse(turtle(HOME, shore, true, 8));
        var lake = terrain(128, Water.LAKE, 170, false);
        assertTrue(turtle(HOME.atY(173), lake, true, 15));
        assertFalse(turtle(HOME.atY(174), lake, true, 15));
    }

    @Test
    void nonServerAccessorKeepsTheOriginalHeight() {
        var world = world(HOME, 63, true, true, true, 15);
        assertEquals(63, PlanetWaterSpawning.seaLevel(world, HOME, 63));
        assertEquals(92, PlanetWaterSpawning.seaLevel(world, HOME, 92));
    }

    private static int height(BlockPos pos, PlanetTerrain terrain) {
        return PlanetWaterSpawning.seaLevel(pos, id -> terrain, 63);
    }

    private static boolean water(
            BlockPos pos, PlanetTerrain terrain, boolean belowWater, boolean aboveWater) {
        return WaterAnimal.checkSurfaceWaterAnimalSpawnRules(
                EntityType.COD,
                world(pos, height(pos, terrain), belowWater, aboveWater, false, 15),
                MobSpawnType.NATURAL,
                pos,
                RandomSource.create(1));
    }

    private static boolean turtle(BlockPos pos, PlanetTerrain terrain, boolean sand, int light) {
        return Turtle.checkTurtleSpawnRules(
                EntityType.TURTLE,
                world(pos, height(pos, terrain), true, true, sand, light),
                MobSpawnType.CHUNK_GENERATION,
                pos,
                RandomSource.create(1));
    }

    // Execute the actual upstream predicates with only the helper's sea-level value substituted.
    private static LevelAccessor world(
            BlockPos pos,
            int sea,
            boolean belowWater,
            boolean aboveWater,
            boolean sand,
            int light) {
        return (LevelAccessor)
                Proxy.newProxyInstance(
                        LevelAccessor.class.getClassLoader(),
                        new Class<?>[] {LevelAccessor.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getSeaLevel" -> sea;
                                    case "getFluidState" ->
                                            (belowWater ? Blocks.WATER : Blocks.LAVA)
                                                    .defaultBlockState()
                                                    .getFluidState();
                                    case "getBlockState" ->
                                            args[0].equals(pos.below())
                                                    ? (sand ? Blocks.SAND : Blocks.STONE)
                                                            .defaultBlockState()
                                                    : (aboveWater ? Blocks.WATER : Blocks.STONE)
                                                            .defaultBlockState();
                                    case "getRawBrightness" -> light;
                                    default ->
                                            throw new AssertionError(
                                                    "Unexpected world access " + method);
                                });
    }

    private static PlanetTerrain terrain(
            int sea, Water waterKind, float liquidHeight, boolean landOnly) throws Exception {
        var json = PlanetBiomeFixtures.recipe("home").json();
        json.getAsJsonObject("water").addProperty("sea_level", sea);
        json.addProperty("land_fraction", landOnly ? 1 : .4);
        var recipe = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        var generation =
                new PlanetGeneration(
                        "test",
                        PlanetGeneration.SURFACE_VERSION,
                        recipe,
                        512,
                        191,
                        PlanetGeneration.BEDROCK,
                        false,
                        false,
                        false,
                        PlanetEnvironment.defaults(true, 512));
        var terrain = new PlanetTerrain(42, PlanetTerrain.VERSION, generation);
        int nodes = new TerrainPlan.Grid(512).positions.length;
        var fields = new float[6][nodes];
        Arrays.fill(fields[TerrainPlan.HEIGHT], 110);
        Arrays.fill(fields[TerrainPlan.WATER_LEVEL], liquidHeight);
        var water = new byte[nodes];
        Arrays.fill(water, (byte) waterKind.ordinal());
        terrain.initializeTerrain(
                new TerrainPlan(
                        42, 512, recipe, fields, new short[nodes], water, List.of(), List.of()));
        return terrain;
    }
}
