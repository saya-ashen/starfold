package dev.starfold.worldgen.surface;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.plan.terrain.TerrainPlan;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;
import dev.starfold.worlds.fixtures.ExperienceFixtures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/** Native registry features and fixed per-column water fields; no running level or game. */
class UnderwaterEcologyTest {
    @BeforeAll
    static void boot() throws Exception {
        ExperienceFixtures.boot();
        SurfaceTestTags.bind();
    }

    private static FeatureSandbox.Template nativePlant(String id) {
        var feature =
                ExperienceFixtures.registryLookup()
                        .lookupOrThrow(Registries.CONFIGURED_FEATURE)
                        .listElements()
                        .filter(
                                h ->
                                        h.key()
                                                .location()
                                                .equals(ResourceLocation.withDefaultNamespace(id)))
                        .findFirst()
                        .orElseThrow()
                        .value();
        return FeatureSandbox.plant(feature, SurfaceGenerationTest.generator("home"), 42);
    }

    private static FeatureSandbox.Template shape(BlockState... states) {
        var blocks = new TreeMap<Integer, BlockState>();
        for (int i = 0; i < states.length; i++)
            blocks.put(FeatureSandbox.key(0, i + 1, 0), states[i]);
        return new FeatureSandbox.Template(blocks);
    }

    private static Function<BlockPos, BlockState> water(int depth, BlockState bed) {
        return p ->
                p.getY() <= 0
                        ? bed
                        : p.getY() <= depth
                                ? Blocks.WATER.defaultBlockState()
                                : Blocks.AIR.defaultBlockState();
    }

    @Test
    void nativeKelpShortensToActualDepthAndKeepsItsGrowingHead() {
        var source = nativePlant("kelp");
        assertTrue(source.height() >= 2);
        var originalHead = source.block(0, source.height(), 0);
        assertTrue(originalHead.is(Blocks.KELP));
        assertTrue(
                originalHead.getValue(KelpBlock.AGE) >= 20
                        && originalHead.getValue(KelpBlock.AGE) <= 23);
        for (var bed : List.of(Blocks.SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.STONE)) {
            var fitted =
                    UnderwaterPlacement.fit(
                            source, Feature.KELP, water(2, bed.defaultBlockState()));
            assertNotNull(fitted, bed.toString());
            assertEquals(1, fitted.height());
            assertEquals(originalHead, fitted.block(0, 1, 0));
            assertTrue(fitted.block(0, 1, 0).getFluidState().is(FluidTags.WATER));
        }
        assertNull(
                UnderwaterPlacement.fit(
                        source, Feature.KELP, water(1, Blocks.SAND.defaultBlockState())));
        assertNull(
                UnderwaterPlacement.fit(
                        source, Feature.KELP, water(8, Blocks.MAGMA_BLOCK.defaultBlockState())));
        assertNull(
                UnderwaterPlacement.fit(
                        source, Feature.KELP, water(8, Blocks.AIR.defaultBlockState())));
    }

    @Test
    void tallSeagrassFitsAsAPairAndRejectsFrozenFlowingOrNonWaterTargets() {
        var generator = SurfaceGenerationTest.generator("home");
        var source =
                FeatureSandbox.plant(
                        new ConfiguredFeature<>(
                                Feature.SEAGRASS, new ProbabilityFeatureConfiguration(1)),
                        generator,
                        42);
        assertEquals(2, source.height());
        assertEquals(DoubleBlockHalf.LOWER, source.block(0, 1, 0).getValue(TallSeagrassBlock.HALF));
        assertEquals(DoubleBlockHalf.UPPER, source.block(0, 2, 0).getValue(TallSeagrassBlock.HALF));
        assertNotNull(
                UnderwaterPlacement.fit(
                        source, Feature.SEAGRASS, water(3, Blocks.GRAVEL.defaultBlockState())));
        assertNull(
                UnderwaterPlacement.fit(
                        source, Feature.SEAGRASS, water(2, Blocks.GRAVEL.defaultBlockState())));
        assertNull(
                UnderwaterPlacement.fit(
                        source,
                        Feature.SEAGRASS,
                        water(8, Blocks.MAGMA_BLOCK.defaultBlockState())));
        var wet = water(8, Blocks.SAND.defaultBlockState());
        for (var invalid :
                List.of(
                        Blocks.ICE.defaultBlockState(),
                        Blocks.LAVA.defaultBlockState(),
                        Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
                        Blocks.AIR.defaultBlockState())) {
            assertNull(
                    UnderwaterPlacement.fit(
                            source, Feature.SEAGRASS, p -> p.getY() == 2 ? invalid : wet.apply(p)),
                    invalid.toString());
        }
    }

    @Test
    void nativePicklesAndAllCoralShapesRetainWaterAndAttachmentStates() {
        var generator = SurfaceGenerationTest.generator("home");
        var configured =
                ExperienceFixtures.registryLookup()
                        .lookupOrThrow(Registries.CONFIGURED_FEATURE)
                        .listElements()
                        .filter(
                                h ->
                                        h.key()
                                                .location()
                                                .equals(
                                                        ResourceLocation.withDefaultNamespace(
                                                                "sea_pickle")))
                        .findFirst()
                        .orElseThrow()
                        .value();
        var pickles =
                FeatureSandbox.build(configured, generator, Blocks.SAND.defaultBlockState(), 42);
        assertNotNull(
                UnderwaterPlacement.fit(
                        pickles, Feature.SEA_PICKLE, water(16, Blocks.SAND.defaultBlockState())));
        pickles.forEach(
                (p, s) -> {
                    assertTrue(s.is(Blocks.SEA_PICKLE));
                    assertTrue(s.getValue(SeaPickleBlock.WATERLOGGED));
                    assertTrue(
                            s.getValue(SeaPickleBlock.PICKLES) >= 1
                                    && s.getValue(SeaPickleBlock.PICKLES) <= 4);
                });
        for (var feature :
                List.of(Feature.CORAL_TREE, Feature.CORAL_CLAW, Feature.CORAL_MUSHROOM)) {
            int accepted = 0;
            for (int seed = 0; seed < 16; seed++) {
                var source =
                        FeatureSandbox.build(
                                new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE),
                                generator,
                                Blocks.SAND.defaultBlockState(),
                                seed);
                var fitted =
                        UnderwaterPlacement.fit(
                                source, feature, water(24, Blocks.SAND.defaultBlockState()));
                if (fitted == null) continue;
                accepted++;
                assertTrue(fitted.blocks() > 1);
                fitted.forEach(
                        (p, s) -> {
                            assertFalse(s.is(Blocks.WATER));
                            assertFalse(s.getBlock() instanceof LiquidBlock);
                            assertTrue(
                                    s.is(BlockTags.CORAL_BLOCKS)
                                            || s.getFluidState().is(FluidTags.WATER));
                        });
                assertNull(
                        UnderwaterPlacement.fit(
                                source, feature, water(1, Blocks.SAND.defaultBlockState())));
            }
            assertTrue(accepted > 0, feature + " must have a native surviving variant");
        }
        var coral = Blocks.TUBE_CORAL_BLOCK.defaultBlockState();
        var fan =
                Blocks.TUBE_CORAL_WALL_FAN
                        .defaultBlockState()
                        .setValue(BaseCoralWallFanBlock.FACING, net.minecraft.core.Direction.EAST);
        var attached = new TreeMap<Integer, BlockState>();
        attached.put(FeatureSandbox.key(0, 1, 0), coral);
        attached.put(FeatureSandbox.key(1, 1, 0), fan);
        var reef = new FeatureSandbox.Template(attached);
        assertNotNull(
                UnderwaterPlacement.fit(
                        reef, Feature.CORAL_TREE, water(8, Blocks.SAND.defaultBlockState())));
        attached.put(
                FeatureSandbox.key(1, 1, 0),
                fan.setValue(BaseCoralWallFanBlock.FACING, net.minecraft.core.Direction.WEST));
        assertNull(
                UnderwaterPlacement.fit(
                        new FeatureSandbox.Template(attached),
                        Feature.CORAL_TREE,
                        water(8, Blocks.SAND.defaultBlockState())));
    }

    private static SurfacePolicy policy(List<BiomeScatter.Entry> entries, String biome)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(1);
            out.writeUTF(biome);
            out.writeInt(entries.size());
            for (var entry : entries) {
                out.writeUTF(entry.id());
                out.writeUTF(BuiltInRegistries.FEATURE.getKey(entry.feature()).toString());
                out.writeDouble(entry.density());
                out.writeInt(entry.variants().size());
                for (var variant : entry.variants()) variant.write(out);
            }
        }
        return new SurfacePolicy(
                "underwater-test", Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }

    private static PlanetTerrain fixed(int depth, String liquid, String bed, SurfacePolicy policy) {
        var base = SurfaceGenerationTest.generator("home").generation();
        var json = base.recipe().json();
        json.getAsJsonObject("relief").addProperty("hills", 0);
        json.getAsJsonObject("relief").addProperty("roughness", 0);
        json.getAsJsonObject("water").addProperty("block", liquid);
        json.getAsJsonObject("water").addProperty("sea_level", 101 + depth);
        json.getAsJsonObject("water").addProperty("freeze", false);
        json.getAsJsonArray("biomes")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("parameters")
                .addProperty("biome", "minecraft:deep_ocean");
        var materials = json.getAsJsonArray("materials");
        var material = materials.get(0).getAsJsonObject().deepCopy();
        var p = material.getAsJsonObject("parameters");
        p.addProperty("block", bed);
        p.addProperty("depth_min", 0);
        p.addProperty("depth_max", 4);
        p.addProperty("temperature_min", -300);
        p.addProperty("temperature_max", 300);
        p.addProperty("slope_min", 0);
        p.addProperty("shore", false);
        p.addProperty("biome", "");
        p.remove("water_depth_min");
        p.remove("water_depth_max");
        while (materials.size() > 0) materials.remove(0);
        materials.add(material);
        var recipe = new TerrainRecipe(json);
        var generation =
                new PlanetGeneration(
                        base.type(),
                        base.surfaceVersion(),
                        recipe,
                        512,
                        PlanetGeneration.STANDARD_DEPTH,
                        PlanetGeneration.BEDROCK,
                        false,
                        true,
                        false,
                        base.environment());
        var terrain = new PlanetTerrain(42, PlanetTerrain.VERSION, generation);
        int count = new TerrainPlan.Grid(512).positions.length;
        var fields = new float[6][count];
        Arrays.fill(fields[TerrainPlan.HEIGHT], 100);
        Arrays.fill(fields[TerrainPlan.WATER_LEVEL], 101 + depth);
        Arrays.fill(fields[TerrainPlan.TEMPERATURE], 20);
        Arrays.fill(fields[TerrainPlan.HUMIDITY], 1);
        var waters = new byte[count];
        Arrays.fill(waters, (byte) TerrainPlan.Water.OCEAN.ordinal());
        terrain.initializeTerrain(
                new TerrainPlan(
                        42, 512, recipe, fields, new short[count], waters, List.of(), List.of()));
        terrain.initializeSurface(policy, ResourceLocation.withDefaultNamespace("ocean"));
        return terrain;
    }

    @Test
    void sharedColumnsPreserveTheSeabedWaterCapAndObjectsOnAllSixFaces() throws Exception {
        var entry = new BiomeScatter.Entry("kelp", Feature.KELP, 1, List.of(nativePlant("kelp")));
        var policy = policy(List.of(entry), "minecraft:deep_ocean");
        var terrain = fixed(4, "minecraft:water", "minecraft:sand", policy);
        var client =
                fixed(
                        4,
                        "minecraft:water",
                        "minecraft:sand",
                        new SurfacePolicy(policy.identity(), policy.compressed()));
        var function = new SurfaceFunction(terrain);
        var replay = new SurfaceFunction(client);
        int checked = 0;
        for (var face : Face.VALUES) {
            int onFace = 0;
            for (int v : new int[] {64, 256, 448})
                for (int u : new int[] {64, 256, 448}) {
                    if (terrain.scatter().column(face, u, v).isEmpty())
                        continue; // The anomaly remains intentionally clear.
                    var visible = function.visible(face, u, v);
                    var blocks = function.blocks(face, u, v);
                    int ground = terrain.groundY(face, u, v);
                    assertEquals(100, ground);
                    assertEquals(104, visible.top());
                    assertTrue(visible.block().is(Blocks.WATER));
                    assertTrue(blocks[ground - terrain.floor()].is(Blocks.SAND));
                    assertTrue(blocks[visible.top() - terrain.floor()].is(Blocks.WATER));
                    assertEquals(0, function.point(face, u, v).canopyCoverage());
                    assertEquals(visible, function.visibleExact(face, u, v));
                    assertEquals(visible, replay.visible(face, u, v));
                    assertArrayEquals(blocks, replay.blocks(face, u, v));
                    for (var run : visible.objects())
                        for (int y = run.bottom(); y <= run.top(); y++) {
                            assertTrue(y > ground && y < visible.top());
                            assertEquals(run.block(), blocks[y - terrain.floor()]);
                            assertTrue(run.block().getFluidState().is(FluidTags.WATER));
                        }
                    assertFalse(visible.objects().isEmpty());
                    onFace++;
                    checked++;
                }
            assertTrue(onFace > 0, face.toString());
        }
        assertTrue(checked >= 6);
        // Canonical aliases must not independently select another variant at a face edge.
        for (var face : Face.VALUES)
            for (int edge = 0; edge < 4; edge++) {
                int u = edge < 2 ? (edge == 0 ? -1 : 512) : 120;
                int v = edge >= 2 ? (edge == 2 ? -1 : 512) : 120;
                var owner = terrain.shape().topology().canonical(new Cell(face, u, 0, v)).cell();
                assertEquals(
                        function.visible(owner.face(), owner.u(), owner.v()),
                        function.visible(face, u, v));
            }
    }

    @Test
    void realChunkWritesKeepNativeKelpBelowTheWaterSurface() throws Exception {
        var entry = new BiomeScatter.Entry("kelp", Feature.KELP, 1, List.of(nativePlant("kelp")));
        var policy = policy(List.of(entry), "minecraft:deep_ocean");
        var fixture = fixed(4, "minecraft:water", "minecraft:sand", policy);
        var generator =
                new dev.starfold.universe.PlanetChunkGenerator(
                        SurfaceGenerationTest.generator("home").settings(),
                        42,
                        PlanetTerrain.VERSION,
                        Optional.empty(),
                        Optional.of(fixture.generation()));
        generator.terrain().initializeTerrain(fixture.terrainPlan());
        generator
                .terrain()
                .initializeSurface(policy, ResourceLocation.withDefaultNamespace("ocean"));
        var function = new SurfaceFunction(generator.terrain());
        var replay = new SurfaceFunction(fixture);
        var face = Face.NORTH;
        var chunk = SurfaceGenerationTest.chunk(generator, face.ordinal() * 256 + 10, 10);
        int plants = 0;
        for (int u = 160; u < 176; u++)
            for (int v = 160; v < 176; v++) {
                int x = face.ordinal() * 4096 + u;
                var shown = function.visible(face, u, v);
                assertTrue(chunk.getBlockState(new BlockPos(x, 100, v)).is(Blocks.SAND));
                assertTrue(chunk.getBlockState(new BlockPos(x, 104, v)).is(Blocks.WATER));
                assertTrue(chunk.getBlockState(new BlockPos(x, 105, v)).isAir());
                assertEquals(shown, replay.visible(face, u, v));
                for (var run : shown.objects())
                    for (int y = run.bottom(); y <= run.top(); y++) {
                        var actual = chunk.getBlockState(new BlockPos(x, y, v));
                        assertEquals(run.block(), actual);
                        assertTrue(actual.is(Blocks.KELP) || actual.is(Blocks.KELP_PLANT));
                        assertTrue(actual.getFluidState().is(FluidTags.WATER));
                        assertTrue(y > 100 && y < 104);
                        plants++;
                    }
            }
        assertTrue(plants > 0);
    }

    @Test
    void latticeReefsAndPicklesAnchorToTheSeabedAndReplayAcrossFaces() throws Exception {
        var generator = SurfaceGenerationTest.generator("home");
        FeatureSandbox.Template reef = null;
        for (int seed = 0; seed < 16 && reef == null; seed++) {
            var source =
                    FeatureSandbox.build(
                            new ConfiguredFeature<>(
                                    Feature.CORAL_TREE, NoneFeatureConfiguration.INSTANCE),
                            generator,
                            Blocks.SAND.defaultBlockState(),
                            seed);
            if (UnderwaterPlacement.fit(
                            source, Feature.CORAL_TREE, water(24, Blocks.SAND.defaultBlockState()))
                    != null) reef = source;
        }
        assertNotNull(reef);
        var pickle =
                shape(Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 3));
        for (var entry :
                List.of(
                        new BiomeScatter.Entry("reef", Feature.CORAL_TREE, 1, List.of(reef)),
                        new BiomeScatter.Entry("pickle", Feature.SEA_PICKLE, 1, List.of(pickle)))) {
            var policy = policy(List.of(entry), "minecraft:deep_ocean");
            var terrain = fixed(24, "minecraft:water", "minecraft:sand", policy);
            var function = new SurfaceFunction(terrain);
            var replay =
                    new SurfaceFunction(fixed(24, "minecraft:water", "minecraft:sand", policy));
            for (var face : Face.VALUES) {
                boolean found = false;
                for (int u = 100; u <= 188 && !found; u += 8)
                    for (int v = 100; v <= 188 && !found; v += 8) {
                        var candidate = terrain.scatter().candidate(face, u, v);
                        if (candidate == null || !terrain.scatter().wins(face, candidate)) continue;
                        assertTrue(candidate.underwater());
                        assertEquals(100, candidate.base());
                        candidate
                                .template()
                                .forEach(
                                        (p, state) -> {
                                            int x = candidate.u() + p.getX(),
                                                    z = candidate.v() + p.getZ(),
                                                    y = candidate.base() + p.getY();
                                            var objects = terrain.scatter().column(face, x, z);
                                            var shown = function.visible(face, x, z);
                                            var blocks = function.blocks(face, x, z);
                                            assertTrue(shown.block().is(Blocks.WATER));
                                            assertEquals(124, shown.top());
                                            assertEquals(shown, replay.visible(face, x, z));
                                            assertEquals(shown, function.visibleExact(face, x, z));
                                            assertTrue(y > 100 && y < 124);
                                            assertEquals(
                                                    state, SurfaceGenerationTest.at(objects, y));
                                            assertEquals(
                                                    state,
                                                    SurfaceGenerationTest.at(shown.objects(), y));
                                            assertEquals(state, blocks[y - terrain.floor()]);
                                        });
                        found = true;
                    }
                assertTrue(found, entry.id() + " on " + face);
                for (int edge = 0; edge < 4; edge++)
                    for (int offset : new int[] {120, 128, 136}) {
                        int u = edge < 2 ? (edge == 0 ? -1 : 512) : offset;
                        int v = edge >= 2 ? (edge == 2 ? -1 : 512) : offset;
                        var owner =
                                terrain.shape()
                                        .topology()
                                        .canonical(new Cell(face, u, 0, v))
                                        .cell();
                        assertEquals(
                                function.visible(owner.face(), owner.u(), owner.v()),
                                function.visible(face, u, v));
                        assertEquals(function.visible(face, u, v), replay.visible(face, u, v));
                    }
            }
            for (var habitat :
                    List.of(
                            fixed(0, "minecraft:water", "minecraft:sand", policy),
                            fixed(24, "minecraft:lava", "minecraft:sand", policy)))
                assertNull(habitat.scatter().candidate(Face.NORTH, 120, 120));
        }
    }

    @Test
    void aLocalVolumeBelowTheSeabedPreservesUnderwaterObjects() throws Exception {
        var entry = new BiomeScatter.Entry("kelp", Feature.KELP, 1, List.of(nativePlant("kelp")));
        var policy = policy(List.of(entry), "minecraft:deep_ocean");
        var terrain = fixed(4, "minecraft:water", "minecraft:sand", policy);
        var parameters = terrain.generation().recipe().volumes().getFirst().parameters();
        parameters.addProperty("operation", "carve");
        var cavity =
                new TerrainPlan.Volume(
                        "starfold:cave_mouth",
                        parameters,
                        terrain.unit(Face.NORTH, 160.5, 160.5),
                        70,
                        12,
                        3,
                        12,
                        TerrainPlan.Operation.CARVE);
        terrain.initializeTerrain(
                terrain.terrainPlan().withContent(List.of(), List.of(cavity), List.of()));
        terrain.initializeSurface(policy, ResourceLocation.withDefaultNamespace("ocean"));
        assertTrue(terrain.terrainPlan().localContent(Face.NORTH, 160, 160));
        var function = new SurfaceFunction(terrain);
        var shown = function.visible(Face.NORTH, 160, 160);
        var blocks = function.blocks(Face.NORTH, 160, 160);
        assertFalse(shown.objects().isEmpty());
        assertTrue(shown.block().is(Blocks.WATER));
        assertEquals(104, shown.top());
        assertEquals(shown, function.visibleExact(Face.NORTH, 160, 160));
        for (var run : terrain.scatter().column(Face.NORTH, 160, 160))
            for (int y = run.bottom(); y <= run.top(); y++) {
                assertEquals(run.block(), SurfaceGenerationTest.at(shown.objects(), y));
                assertEquals(run.block(), blocks[y - terrain.floor()]);
            }
    }

    @Test
    void habitatFilteringKeepsLandPlantsDryAndAquaticsOutOfLavaAndDryColumns() throws Exception {
        var grass =
                new BiomeScatter.Entry(
                        "grass",
                        Feature.SIMPLE_BLOCK,
                        1,
                        List.of(shape(Blocks.SHORT_GRASS.defaultBlockState())));
        var kelp = new BiomeScatter.Entry("kelp", Feature.KELP, 1, List.of(nativePlant("kelp")));
        var simpleSeagrass =
                new BiomeScatter.Entry(
                        "simple-seagrass",
                        Feature.SIMPLE_BLOCK,
                        1,
                        List.of(nativePlant("seagrass_simple")));
        assertTrue(simpleSeagrass.aquatic());
        for (var aquatic : List.of(kelp, simpleSeagrass)) {
            var policy = policy(List.of(aquatic, grass), "minecraft:deep_ocean");
            for (var liquid : List.of("minecraft:water", "minecraft:lava")) {
                var terrain = fixed(4, liquid, "minecraft:dirt", policy);
                var runs = terrain.scatter().column(Face.NORTH, 160, 160);
                if (liquid.equals("minecraft:lava")) assertTrue(runs.isEmpty());
                else {
                    assertFalse(runs.isEmpty());
                    assertTrue(runs.stream().noneMatch(r -> r.block().is(Blocks.SHORT_GRASS)));
                }
            }
            var dry = fixed(0, "minecraft:water", "minecraft:dirt", policy);
            // depth=0 has a sea level just above ground, but no water cells: neither habitat can
            // spawn here.
            assertTrue(dry.scatter().column(Face.NORTH, 160, 160).isEmpty());
            var land = fixed(-1, "minecraft:water", "minecraft:dirt", policy);
            var runs = land.scatter().column(Face.NORTH, 160, 160);
            assertEquals(
                    Blocks.SHORT_GRASS.defaultBlockState(), SurfaceGenerationTest.at(runs, 101));
        }
    }
}
