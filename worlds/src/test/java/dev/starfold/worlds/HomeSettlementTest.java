package dev.starfold.worlds;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetConditions;
import dev.starfold.universe.PlanetType;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Registry-only opening audit. No level, chunks, feature placement, mobs, or guaranteed resources.
 * See docs/home-settlement-offline-notes.md for the precise limits of each measured quantity.
 */
class HomeSettlementTest {
    private static final Path HOME =
            Path.of("modpack/kubejs/data/starfold_worlds/starfold/planet_type/home.json");
    private static final long[] SEEDS = {
        20261009L, 0, 1, -1, 42, 12345, 8675309, Long.MAX_VALUE, 20261001L
    };
    private static final Face FACE = Face.TOP;
    private static final int RADIUS = 256, STEP = 16, DEPTH = 64;
    private static final List<String> PREFIX =
            List.of(
                    "minecraft:iron_ore",
                    "minecraft:copper_ore",
                    "minecraft:coal_ore",
                    "minecraft:gold_ore");
    private static final Set<String> FOOD_MOBS =
            Set.of(
                    "minecraft:cow",
                    "minecraft:pig",
                    "minecraft:sheep",
                    "minecraft:chicken",
                    "minecraft:rabbit");
    private static final Set<String> FOOD_FISH =
            Set.of("minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish");
    private static JsonObject source;
    private static PlanetType prefixHome;
    private static HomeOreEligibility oreEligibility;
    private static int minBuildY, maxBuildY;
    private static final Map<String, BiomeEligibility> biomeEligibility = new HashMap<>();

    @BeforeAll
    static void homeRegistries() throws Exception {
        PlanetBiomeFixtures.boot();
        source = JsonParser.parseString(Files.readString(HOME)).getAsJsonObject();
        // The authored experience preset uses native_deep. Mirror Search.planned's level bounds
        // from its real dimension data; no ServerLevel or generated chunks are needed here.
        var dimension =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/data/starfold/dimension_type/native_deep.json")))
                        .getAsJsonObject();
        minBuildY = dimension.get("min_y").getAsInt();
        maxBuildY = minBuildY + dimension.get("height").getAsInt();
        var ores = source.getAsJsonObject("terrain").getAsJsonArray("ores");
        oreEligibility = new HomeOreEligibility(ores);
        // The test launcher has no Create runtime. Decode an UNCHANGED PREFIX, never substitute
        // zinc or remove it from the analytic table. Later entries cannot beat this prefix.
        var decoded = source.deepCopy();
        var prefix = new JsonArray();
        for (int i = 0; i < PREFIX.size(); i++) {
            assertEquals(
                    PREFIX.get(i),
                    ores.get(i).getAsJsonObject().get("block").getAsString(),
                    "Revisit the prefix proof if pack priority changes");
            prefix.add(ores.get(i).deepCopy());
        }
        decoded.getAsJsonObject("terrain").add("ores", prefix);
        prefixHome =
                PlanetType.CODEC
                        .parse(
                                RegistryOps.create(JsonOps.INSTANCE, PlanetBiomeFixtures.lookup),
                                decoded)
                        .getOrThrow();
        assertEquals(
                Blocks.WATER,
                prefixHome.terrain().recipe().liquid(),
                "Offline water identity proof requires the pack's vanilla water policy");
        for (var id : prefixHome.terrain().recipe().biomeIds()) {
            var biome = PlanetBiomeFixtures.palette.getOptional(id).orElseThrow();
            boolean trees =
                    biome.getGenerationSettings().features().stream()
                            .flatMap(step -> step.stream())
                            .flatMap(placed -> placed.value().getFeatures())
                            .anyMatch(feature -> feature.feature() == Feature.TREE);
            boolean food =
                    biome.getMobSettings().getMobs(MobCategory.CREATURE).unwrap().stream()
                            .anyMatch(
                                    spawn ->
                                            FOOD_MOBS.contains(
                                                    BuiltInRegistries.ENTITY_TYPE
                                                            .getKey(spawn.type)
                                                            .toString()));
            boolean fish =
                    Stream.of(MobCategory.WATER_AMBIENT, MobCategory.WATER_CREATURE)
                            .flatMap(
                                    category ->
                                            biome
                                                    .getMobSettings()
                                                    .getMobs(category)
                                                    .unwrap()
                                                    .stream())
                            .anyMatch(
                                    spawn ->
                                            FOOD_FISH.contains(
                                                    BuiltInRegistries.ENTITY_TYPE
                                                            .getKey(spawn.type)
                                                            .toString()));
            biomeEligibility.put(
                    id.toString(),
                    new BiomeEligibility(trees, food, fish, SurvivalSpawn.eligible(biome)));
        }
        System.out.println(
                "HOME opening: ANALYTIC SurvivalSpawn candidate/planned filters only; "
                        + "no live clearing/logs/source-water checks; terrain seeds (not world seeds), TOP, sizes 1024/2048; "
                        + "disk r=256 step=16, local-ground depth<=64; count@horizontal/depth is a sampled witness; "
                        + "tree/land-food are dry biome eligibility, fish require a water cap; "
                        + "andesite/zinc are full-table analytic eligibility.");
    }

    @Test
    void packHomeExcludesOffworldMinerals() {
        // This is a resource-policy assertion, not proof that every block in every live chunk is
        // absent.
        var terrain = source.getAsJsonObject("terrain");
        var mineralBlocks = new ArrayList<String>();
        for (var ore : terrain.getAsJsonArray("ores")) {
            var entry = ore.getAsJsonObject();
            mineralBlocks.add(entry.get("block").getAsString());
            if (entry.has("deep")) mineralBlocks.add(entry.get("deep").getAsString());
        }
        for (var stratum : terrain.getAsJsonArray("strata"))
            mineralBlocks.add(stratum.getAsJsonObject().get("block").getAsString());
        assertTrue(
                mineralBlocks.containsAll(List.of("create:zinc_ore", "minecraft:andesite")),
                "Must read the pack override, not the Worlds base home");
        assertFalse(
                mineralBlocks.stream()
                        .anyMatch(id -> id.contains("osmium") || id.contains("diamond")),
                mineralBlocks.toString());
    }

    static Stream<Arguments> openings() {
        return java.util.Arrays.stream(SEEDS)
                .boxed()
                .flatMap(seed -> Stream.of(Arguments.of(seed, 1024), Arguments.of(seed, 2048)));
    }

    @ParameterizedTest(name = "terrain seed={0}, home size={1}")
    @MethodSource("openings")
    void chosenOpeningHasBoundedSurvivalCandidates(long seed, int size) {
        // PlanetType.planet passes this exact seed into PlanetTerrain; using the constructor avoids
        // initiating scatter template extraction. Shape/environment/depth still use the real codec.
        var terrain = new PlanetTerrain(seed, PlanetTerrain.VERSION, prefixHome.generation(size));
        // Select the FIRST planned candidate in the real pack policy's order, before auditing ores.
        // A live Search may reject it or move within four blocks after checking generated chunks.
        int[] search = {0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] spawn =
                SurvivalSpawn.candidate(
                        size,
                        (u, v) -> {
                            search[0]++;
                            int ground = terrain.groundY(FACE, u, v);
                            search[2] = Math.min(search[2], ground);
                            search[3] = Math.max(search[3], ground);
                            if (!plannedOpening(terrain, u, v)) return false;
                            search[1]++;
                            return plannedWater(terrain, u, v).count > 0;
                        });
        String selection =
                "HOME selection seed="
                        + seed
                        + " size="
                        + size
                        + " visited="
                        + search[0]
                        + " planned="
                        + search[1]
                        + " floor="
                        + terrain.floor()
                        + " requiredGroundAbove="
                        + (minBuildY + 513)
                        + " searchedGroundRange="
                        + search[2]
                        + ".."
                        + search[3]
                        + " candidate="
                        + (spawn == null ? "none" : spawn[0] + "," + spawn[1]);
        System.out.println(selection);
        assertNotNull(spawn, "no analytic SurvivalSpawn candidate: " + selection);
        int su = spawn[0], sv = spawn[1], ground = terrain.groundY(FACE, su, sv);
        var nearbyWater = plannedWater(terrain, su, sv);
        var cap = terrain.surface(FACE, su, sv, ground);
        var spawnBand = terrain.column(FACE, su, sv);
        int entry = ground + 1; // SurvivalSpawn feet above grass; no live canopy/structures here.
        boolean inBand = entry >= spawnBand.bottom() && entry + 1 < spawnBand.end();
        var atmosphere = terrain.generation().environment().atmosphere();
        boolean dry =
                !terrain.submerged(FACE, su, sv, ground) && cap.state().getFluidState().isEmpty();
        boolean safe =
                atmosphere.breathable()
                        && PlanetConditions.canBurn(atmosphere.density())
                        && !terrain.closure(FACE, su, sv)
                        && !hazard(cap.state())
                        && cap.state().is(Blocks.GRASS_BLOCK)
                        && cap.top() == ground
                        && cap.state().getFluidState().isEmpty()
                        && inBand
                        && spawnBand.state(entry).isAir()
                        && spawnBand.state(entry + 1).isAir();
        var stone = new Metric();
        var iron = new Metric();
        var copper = new Metric();
        var coal = new Metric();
        var andesite = new Metric();
        var zinc = new Metric();
        var trees = new Metric();
        var food = new Metric();
        var fish = new Metric();
        var water = new Metric();
        var biomes = new java.util.TreeMap<String, Integer>();
        int columns = 0, dryColumns = 0, oreColumns = 0, omitted = 0, checked = 0;
        int forbidden = 0;
        double minTemp = Double.POSITIVE_INFINITY, maxTemp = Double.NEGATIVE_INFINITY;
        for (int du = -RADIUS; du <= RADIUS; du += STEP) {
            for (int dv = -RADIUS; dv <= RADIUS; dv += STEP) {
                int distance2 = du * du + dv * dv;
                if (distance2 > RADIUS * RADIUS) continue;
                int u = su + du, v = sv + dv;
                // Avoid silently measuring transported coordinates on a neighbouring face.
                if (u < 0 || v < 0 || u >= size || v >= size) continue;
                columns++;
                int g = terrain.groundY(FACE, u, v);
                var surface = terrain.surface(FACE, u, v, g);
                String biome = terrain.biome(FACE, u, v);
                biomes.merge(biome, 1, Integer::sum);
                double temperature = terrain.temperature(FACE, u, v);
                assertTrue(Double.isFinite(temperature));
                minTemp = Math.min(minTemp, temperature);
                maxTemp = Math.max(maxTemp, temperature);
                var eligibility = biomeEligibility.get(biome);
                assertNotNull(eligibility, biome);
                if (surface.state().is(Blocks.WATER)) {
                    water.hit(distance2, 0);
                    if (eligibility.fish) fish.hit(distance2, 0);
                }
                if (terrain.submerged(FACE, u, v, g) || !surface.state().getFluidState().isEmpty())
                    continue;
                dryColumns++;
                if (eligibility.trees && terrain.humidity(FACE, u, v) > 0) trees.hit(distance2, 0);
                if (eligibility.food) food.hit(distance2, 0);
                // Conservative substrate probe: no local volume/road replacements, closure or
                // anomaly.
                // There is no attempt to emulate the later surface/structure composition here.
                if (terrain.terrainPlan().localContent(FACE, u, v)
                        || terrain.closure(FACE, u, v)
                        || Anomaly.distance(terrain.unit(FACE, u + .5, v + .5), terrain.shape())
                                < Anomaly.CLEAR) {
                    omitted++;
                    continue;
                }
                oreColumns++;
                var column = terrain.visibleBand(FACE, u, v, g, g - DEPTH);
                for (int depth = 0; depth <= DEPTH; depth++) {
                    int y = g - depth;
                    if (y <= terrain.floor()) continue;
                    var block = column.state(y);
                    String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
                    if (id.contains("diamond") || id.contains("osmium")) forbidden++;
                    String winner = oreEligibility.winner(terrain, FACE, u, v, g, y);
                    // Actual composed states establish the prefix. Every sampled ore cell is
                    // checked
                    // against the full-table prediction, so later entries cannot shift prefix
                    // salts.
                    if (depth >= PlanetTerrain.SURFACE_DEPTH) {
                        checked++;
                        for (String prefix : PREFIX) {
                            assertEquals(
                                    prefix.equals(winner),
                                    id.equals(prefix)
                                            || id.equals(
                                                    prefix.replace(
                                                            "minecraft:", "minecraft:deepslate_")),
                                    "Ore predicate diverged seed="
                                            + seed
                                            + " at "
                                            + u
                                            + ","
                                            + y
                                            + ","
                                            + v);
                        }
                    }
                    if (isOre(block, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE))
                        iron.hit(distance2, depth);
                    if (isOre(block, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE))
                        copper.hit(distance2, depth);
                    if (isOre(block, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE))
                        coal.hit(distance2, depth);
                    if ("minecraft:andesite".equals(winner) && block.is(Blocks.STONE))
                        andesite.hit(distance2, depth);
                    if ("create:zinc_ore".equals(winner) && block.is(Blocks.STONE))
                        zinc.hit(distance2, depth);
                    // Later analytic winners must not be counted as plain stone.
                    if (block.is(Blocks.STONE)
                            && winner == null
                            && depth <= 8
                            && distance2 <= 32 * 32) stone.hit(distance2, depth);
                }
            }
        }
        String row =
                String.format(
                        Locale.ROOT,
                        "HOME seed=%d size=%d analyticCandidate=%d,%d ground=%d cap=%s capY=%d liquidY=%s dry=%s safe=%s "
                                + "temp=%.2f humidity=%.3f columns=%d dryColumns=%d oreColumns=%d omitted=%d "
                                + "stone=%s iron=%s copper=%s coal=%s andesiteEligible=%s zincEligible=%s "
                                + "treeBiome=%s landFoodBiome=%s fishBiome=%s water=%s plannedWater64=%s "
                                + "forbidden=%d predicateChecks=%d climateRange=%.2f..%.2f biomes=%s",
                        seed,
                        size,
                        su,
                        sv,
                        ground,
                        BuiltInRegistries.BLOCK.getKey(cap.state().getBlock()),
                        cap.top(),
                        terrain.liquid(FACE, su, sv) == null
                                ? "none"
                                : terrain.liquid(FACE, su, sv).level(),
                        dry,
                        safe,
                        terrain.temperature(FACE, su, sv),
                        terrain.humidity(FACE, su, sv),
                        columns,
                        dryColumns,
                        oreColumns,
                        omitted,
                        stone,
                        iron,
                        copper,
                        coal,
                        andesite,
                        zinc,
                        trees,
                        food,
                        fish,
                        water,
                        nearbyWater,
                        forbidden,
                        checked,
                        minTemp,
                        maxTemp,
                        biomes);
        System.out.println(row);
        int forbiddenCount = forbidden;
        assertAll(
                row,
                () ->
                        assertTrue(
                                plannedOpening(terrain, su, sv),
                                "candidate fails pack planned filters"),
                () -> assertTrue(dry, "chosen analytic candidate is submerged"),
                () ->
                        assertTrue(
                                safe,
                                "candidate lacks analytic support/clearance or safe atmosphere/cap"),
                () ->
                        assertTrue(
                                nearbyWater.count > 0,
                                "no planned water on 8-block grid within 64"),
                () ->
                        assertTrue(
                                stone.count > 0, "no sampled stone within horizontal 32 / depth 8"),
                () -> assertTrue(iron.count > 0, "no sampled shallow iron"),
                () -> assertTrue(copper.count > 0, "no sampled shallow copper"),
                () -> assertTrue(coal.count > 0, "no sampled shallow coal"),
                () -> assertTrue(andesite.count > 0, "no sampled shallow andesite eligibility"),
                () -> assertTrue(zinc.count > 0, "no sampled shallow zinc eligibility"),
                () -> assertTrue(trees.count > 0, "no dry tree-feature biome eligibility"),
                () -> assertTrue(food.count > 0, "no dry land-food biome eligibility"),
                () -> assertTrue(water.count > 0, "no sampled visible water within 256"),
                () -> assertEquals(0, forbiddenCount, "sampled forbidden mineral blocks"));
    }

    /** Mirrors SurvivalSpawn.Search.planned; only candidate/eligible are accessible production helpers. */
    private static boolean plannedOpening(PlanetTerrain terrain, int u, int v) {
        int ground = terrain.groundY(FACE, u, v);
        if (ground <= minBuildY + 513
                || ground <= terrain.floor() + 4
                || ground + 3 >= maxBuildY
                || terrain.closure(FACE, u, v)
                || terrain.submerged(FACE, u, v, ground)
                || terrain.temperature(FACE, u, v) <= 0
                || terrain.humidity(FACE, u, v) <= 0
                || !terrain.surfaceState(FACE, u, v).is(Blocks.GRASS_BLOCK)) return false;
        return biomeEligibility.get(terrain.biome(FACE, u, v)).settlement;
    }

    /** Search.water(..., false)'s grid and submersion; counts are planned, not live.
     * The registry fixture does not bind fluid tags. Setup proves the configured liquid is
     * vanilla WATER, a vanilla water-tag member, so use its block identity without faking tags.
     */
    private static Metric plannedWater(PlanetTerrain terrain, int u, int v) {
        var result = new Metric();
        for (int r = 0; r <= 64; r += 8)
            for (int du = -r; du <= r; du += 8)
                for (int dv = -r; dv <= r; dv += 8) {
                    int distance2 = du * du + dv * dv;
                    if (Math.max(Math.abs(du), Math.abs(dv)) != r || distance2 > 64 * 64) continue;
                    int a = u + du, b = v + dv;
                    var sea = terrain.liquid(FACE, a, b);
                    if (sea != null
                            && sea.fluid().is(Blocks.WATER)
                            && terrain.submerged(FACE, a, b, terrain.groundY(FACE, a, b)))
                        result.hit(distance2, 0);
                }
        return result;
    }

    private static boolean isOre(
            BlockState state,
            net.minecraft.world.level.block.Block normal,
            net.minecraft.world.level.block.Block deep) {
        return state.is(normal) || state.is(deep);
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.POWDER_SNOW);
    }

    private record BiomeEligibility(
            boolean trees, boolean food, boolean fish, boolean settlement) {}

    private static final class Metric {
        int count, distance2 = Integer.MAX_VALUE, depth;

        void hit(int atDistance2, int atDepth) {
            count++;
            if (atDistance2 < distance2 || atDistance2 == distance2 && atDepth < depth) {
                distance2 = atDistance2;
                depth = atDepth;
            }
        }

        @Override
        public String toString() {
            return count == 0
                    ? "0@none"
                    : String.format(Locale.ROOT, "%d@%.1f/%d", count, Math.sqrt(distance2), depth);
        }
    }
}
