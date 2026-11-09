package dev.starfold.worldgen.plan;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.universe.*;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.plan.terrain.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import org.junit.jupiter.api.*;

/** S1 contract, geometric aliases, operations and actual isolated Minecraft chunk writes. */
class TerrainPlanTest {
    @BeforeAll
    static void boot() throws Exception {
        PlanetPlanFixtures.boot();
    }
    @AfterAll
    static void close() throws Exception {
        PlanetPlanFixtures.close();
    }
    static TerrainRecipe recipe(String name) throws Exception {
        return TerrainRecipe.CODEC
            .parse(JsonOps.INSTANCE,
                JsonParser
                    .parseString(Files.readString(java.nio.file.Path.of(
                        "worlds/src/main/resources/data/starfold_worlds/starfold/planet_type/" + name
                        + ".json")))
                    .getAsJsonObject()
                    .get("terrain"))
            .getOrThrow();
    }
    static PlanetChunkGenerator planet(String name) throws Exception {
        var recipe = recipe(name);
        var registry = PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME);
        var palette =
            recipe.biomeIds()
                .stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .map(
                    id -> (Holder<Biome>) registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME, id)))
                .toList();
        var policy = new PlanetGeneration(name, PlanetGeneration.SURFACE_VERSION, recipe, 512, 191,
            "core_shell", false, false, false, PlanetEnvironment.defaults(true, 512));
        return new PlanetChunkGenerator(
            new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), palette.getFirst(), List.of()),
            42, PlanetTerrain.VERSION, Optional.of(new PlanetBiomes(42, palette)), Optional.of(policy));
    }
    @Test
    void fixedBytesAndStrictNetworkAndArchiveDescription() throws Exception {
        var planet = planet("home");
        var first = planet.terrain().plan();
        var second = planet("home").terrain().plan();
        assertArrayEquals(first.compressed(), second.compressed());
        var decoded = PlanetPlan.decode(first.compressed());
        assertArrayEquals(first.compressed(), decoded.compressed());
        assertEquals(first.terrain().recipe(), decoded.terrain().recipe());
        var file = java.nio.file.Path.of("build/terrain-plan/no-structures.dat");
        var archive = new TraditionalPlanetPlanner.Archive(first, Map.of(), Map.of());
        PlanetPlanStore.write(file, planet, archive);
        var restored = planet("home");
        var read = PlanetPlanStore.read(file, restored, PlanetPlanFixtures.context);
        assertArrayEquals(first.compressed(), read.plan().compressed());
        assertSame(read.plan().terrain(), restored.terrain().terrainPlan());
        var changed = recipe("home").json();
        changed.getAsJsonObject("relief").addProperty("mountains", 0);
        var current = restored.generation().withRecipe(new TerrainRecipe(changed));
        var detached = new PlanetTerrain(42, PlanetTerrain.VERSION, current);
        detached.initializeTerrain(decoded.terrain());
        assertEquals(planet.terrain().groundY(Face.TOP, 210, 250), detached.groundY(Face.TOP, 210, 250));
        var invalid = recipe("home").json();
        invalid.addProperty("algorithm", "ecology");
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, invalid).isError());
        invalid = recipe("home").json();
        invalid.addProperty("version", 1);
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, invalid).isError());
        invalid = recipe("home").json();
        invalid.getAsJsonObject("planner").getAsJsonArray("stages").set(0, new JsonPrimitive("test:missing"));
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, invalid).isError());
        invalid = recipe("rocky").json();
        invalid.getAsJsonArray("landforms").get(0).getAsJsonObject().addProperty("type", "test:missing");
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, invalid).isError());
    }
    @Test
    void archiveRejectsEmbeddedIdentityTamperingBeforeBinding() throws Exception {
        var owner = planet("home");
        var file = java.nio.file.Path.of("build/terrain-plan/tampered.dat");
        for (var alien : List.of(planet("rocky").terrain().plan(),
                 new PlanetTerrain(43, PlanetTerrain.VERSION, owner.generation()).plan())) {
            PlanetPlanStore.write(
                file, owner, new TraditionalPlanetPlanner.Archive(alien, Map.of(), Map.of()));
            var target = planet("home");
            var before = target.terrain().terrainPlan();
            assertThrows(java.io.IOException.class,
                () -> PlanetPlanStore.read(file, target, PlanetPlanFixtures.context));
            assertSame(before, target.terrain().terrainPlan());
        }
    }
    @Test
    void registeredCustomRulesStagesAndDeepMaterialsNeedNoBuiltinParameterNames() throws Exception {
        TerrainRegistries.BIOMES.register("test:river_output", TerrainRegistries.parameters(p -> {
            TerrainRecipe.keys(p, "output");
            ResourceLocation.parse(p.get("output").getAsString());
        }),
            new TerrainRegistries.BiomeRule() {
                public ResourceLocation biome(JsonObject p) {
                    return ResourceLocation.parse(p.get("output").getAsString());
                }
                public boolean matches(TerrainRegistries.BiomeContext c, JsonObject p) {
                    return c.river();
                }
            });
        TerrainRegistries.MATERIALS.register("test:deep_gold",
            TerrainRegistries.parameters(p -> TerrainRecipe.keys(p)),
            (c, p)
                -> c.depth() == 12 ? net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState()
                                   : null);
        TerrainRegistries.LANDFORMS.register("test:mound",
            TerrainRegistries.parameters(p -> TerrainRecipe.keys(p)), new TerrainRegistries.LandformType() {
                public double profile(double r, double angle, JsonObject parameters) {
                    return 1 - r;
                }
                public void place(TerrainPlanner planner, TerrainRecipe.Config config) {
                    planner.landforms.add(new Landform(
                        config.type(), config.parameters(), Face.EAST.normal, 32, 10, Operation.RAISE));
                }
            });
        TerrainRegistries.registerStage("test:marker", p -> { p.fields[ROUGHNESS][0] = 3; });
        var json = recipe("home").json();
        var rule = new JsonObject();
        rule.addProperty("type", "test:river_output");
        var params = new JsonObject();
        params.addProperty("output", "minecraft:river");
        rule.add("parameters", params);
        json.getAsJsonArray("biomes").asList().addFirst(rule);
        rule = new JsonObject();
        rule.addProperty("type", "test:deep_gold");
        rule.add("parameters", new JsonObject());
        json.getAsJsonArray("materials").asList().addFirst(rule);
        rule = new JsonObject();
        rule.addProperty("type", "test:mound");
        rule.add("parameters", new JsonObject());
        json.getAsJsonArray("landforms").add(rule);
        json.getAsJsonObject("planner").getAsJsonArray("stages").add("test:marker");
        json.getAsJsonObject("caves").addProperty("width", 0);
        var recipe = new TerrainRecipe(json);
        assertTrue(recipe.biomeIds().contains(ResourceLocation.withDefaultNamespace("river")));
        var plan = new TerrainPlanner(42, 512, recipe).generate();
        assertEquals(3, plan.field(ROUGHNESS, 0));
        assertEquals(10, plan.landformHeight(Face.EAST.normal, Face.EAST, 256, 256), 1e-8);
        assertFalse(plan.paths().isEmpty());
        int river = plan.paths().getFirst().nodes().getFirst();
        assertEquals("test:river_output", recipe.biomes().get(plan.biome(river)).type());
        var roundTrip = PlanetPlan.decode(new PlanetPlan(512, plan, List.of(), List.of()).compressed());
        assertEquals("minecraft:river", roundTrip.terrain().recipe().biomes().getFirst().biomeId());
        var terrain =
            new PlanetTerrain(42, PlanetTerrain.VERSION, planet("home").generation().withRecipe(recipe));
        terrain.initializeTerrain(plan);
        int ground = terrain.groundY(Face.TOP, 256, 256);
        assertEquals(net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),
            terrain.column(Face.TOP, 256, 256).state(ground - 12));
        for (var form : plan.landforms()) assertTrue(TerrainPlanner.clear(form.center(), form.radius(), 512));
    }

    @Test
    void environmentOffsetIsInFixedClimateAndBiomeSelection() throws Exception {
        var json = recipe("home").json();
        var rules = json.getAsJsonArray("biomes");
        var hot = rules.get(0).deepCopy().getAsJsonObject();
        var params = hot.getAsJsonObject("parameters");
        params.addProperty("biome", "minecraft:desert");
        params.addProperty("temperature_min", 50);
        params.addProperty("height_max", 320);
        rules.asList().addFirst(hot);
        var recipe = new TerrainRecipe(json);
        var cold = new TerrainPlanner(42, 512, recipe, 0).generate();
        var warm = new TerrainPlanner(42, 512, recipe, 70).generate();
        int n = warm.node(Face.EAST, 16, 16);
        assertEquals(70, warm.field(TEMPERATURE, n) - cold.field(TEMPERATURE, n), 1e-5);
        assertNotEquals(cold.biome(n), warm.biome(n));
        assertEquals("minecraft:desert", recipe.biomes().get(warm.biome(n)).biomeId());
    }
    @Test
    void allTwelveEdgesEightCornersAndBicubicHaloShareActualGeometricNodes() throws Exception {
        var plan = planet("home").terrain().terrainPlan();
        int n = plan.size() / 16;
        var aliases = new HashMap<Integer, List<int[]>>();
        for (var f : Face.VALUES)
            for (int u = 0; u <= n; u++)
                for (int v = 0; v <= n; v++)
                    if (u == 0 || v == 0 || u == n || v == n)
                        aliases.computeIfAbsent(plan.node(f, u, v), k -> new ArrayList<>())
                            .add(new int[] {f.ordinal(), u, v});
        int corners = 0, edgeNodes = 0;
        var edgePairs = new HashSet<String>();
        for (var group : aliases.values()) {
            if (group.size() == 3)
                corners++;
            else {
                assertEquals(2, group.size());
                edgeNodes++;
                var faces = group.stream().map(a -> a[0]).sorted().toList();
                edgePairs.add(faces.toString());
            }
            var a = group.getFirst();
            double height = plan.height(Face.VALUES[a[0]], a[1] * 16, a[2] * 16);
            String biome = plan.biome(Face.VALUES[a[0]], a[1] * 16.0, a[2] * 16.0);
            for (var b : group) {
                int id = plan.node(Face.VALUES[b[0]], b[1], b[2]);
                assertEquals(plan.node(Face.VALUES[a[0]], a[1], a[2]), id);
                assertEquals(height, plan.height(Face.VALUES[b[0]], b[1] * 16, b[2] * 16), 1e-8);
                assertEquals(biome, plan.biome(Face.VALUES[b[0]], b[1] * 16.0, b[2] * 16.0));
                for (int field = 0; field < 6; field++)
                    assertEquals(plan.field(field, id),
                        plan.interpolate(field, Face.VALUES[b[0]], b[1] * 16, b[2] * 16), 1e-5);
            }
        }
        assertEquals(8, corners);
        assertEquals(12, edgePairs.size());
        assertEquals(12 * (n - 1), edgeNodes);
        assertEquals(6 * n * n + 2, plan.nodeCount());
        // Halo nodes cover every face, both sides, and two intervals required by bicubic interpolation.
        for (var f : Face.VALUES)
            for (int u = -2; u <= n + 2; u++)
                for (int v : new int[] {-2, -1, 0, n, n + 1, n + 2}) {
                    assertTrue(plan.node(f, u, v) >= 0);
                    assertTrue(plan.node(f, v, u) >= 0);
                }
        var terrain = planet("home").terrain();
        var function = new SurfaceFunction(terrain);
        for (var f : Face.VALUES)
            for (int k = 16; k < 512; k += 16)
                for (int[] p : new int[][] {{-1, k}, {512, k}, {k, -1}, {k, 512}}) {
                    var c = terrain.shape().topology().canonical(new Cell(f, p[0], 0, p[1])).cell();
                    assertEquals(function.point(c.face(), c.u(), c.v()), function.point(f, p[0], p[1]));
                    assertEquals(terrain.biome(c.face(), c.u(), c.v()), terrain.biome(f, p[0], p[1]));
                    assertEquals(terrain.liquid(c.face(), c.u(), c.v()), terrain.liquid(f, p[0], p[1]));
                }
    }
    @Test
    void fillCarveAndPlaceFollowDeclaredOrderAndSnowHasOneSurface() throws Exception {
        var planet = planet("ice");
        var terrain = planet.terrain();
        var original = terrain.terrainPlan();
        var fields = new float[6][original.nodeCount()];
        var biomes = new short[original.nodeCount()];
        var water = new byte[original.nodeCount()];
        for (int i = 0; i < biomes.length; i++) {
            fields[HEIGHT][i] = 180;
            fields[TEMPERATURE][i] = -20;
            fields[HUMIDITY][i] = .5f;
            fields[WATER_LEVEL][i] = 128;
        }
        int a = original.node(Face.TOP, 15, 16), b = original.node(Face.TOP, 17, 16);
        var carve = new TerrainPlan.Path(List.of(a, b), 8, 10, 170, Operation.CARVE);
        var fill = new TerrainPlan.Path(List.of(a, b), 8, 10, 200, Operation.FILL);
        var first = new TerrainPlan(
            42, 512, original.recipe(), fields, biomes, water, List.of(), List.of(carve, fill));
        var second = new TerrainPlan(
            42, 512, original.recipe(), fields, biomes, water, List.of(), List.of(fill, carve));
        assertEquals(200, first.height(Face.TOP, 240, 256), 1e-8);
        assertEquals(160, second.height(Face.TOP, 240, 256), 1e-8);
        terrain.initializeTerrain(first);
        var f = new SurfaceFunction(terrain);
        int ground = terrain.groundY(Face.TOP, 256, 256);
        var blocks = f.blocks(Face.TOP, 256, 256);
        assertEquals(net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState(),
            blocks[ground + 1 - terrain.floor()]);
        assertEquals(ground + 1, f.point(Face.TOP, 256, 256).height());
        assertEquals(f.visible(Face.TOP, 256, 256).block(),
            f.point(Face.TOP, 256, 256).materials().getFirst().block());
        var bounds = new PlanetPlan.Bounds(256, ground + 1, 256, 256, ground + 1, 256);
        var piece = new PlanetPlan.Piece(0, new BlockPos(256, ground + 1, 256),
            net.minecraft.world.level.block.Rotation.NONE, net.minecraft.world.level.block.Mirror.NONE,
            bounds, bounds, net.minecraft.world.level.levelgen.structure.TerrainAdjustment.NONE);
        var plan = new PlanetPlan(512, first,
            List.of(new PlanetPlan.Site(
                1, ResourceLocation.parse("test:place"), Face.TOP, 256, 256, bounds, List.of(piece))),
            List.of(new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(
                0, 0, 0, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState())))));
        terrain.initializePlan(() -> plan);
        assertEquals(net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),
            new SurfaceFunction(terrain).blocks(Face.TOP, 256, 256)[ground + 1 - terrain.floor()]);
    }
    @Test
    void coldWaterAtGroundDoesNotCreateAnAirCap() throws Exception {
        var terrain = planet("ice").terrain();
        var original = terrain.terrainPlan();
        var fields = new float[6][original.nodeCount()];
        var biomes = new short[original.nodeCount()];
        var water = new byte[original.nodeCount()];
        for (int i = 0; i < biomes.length; i++) {
            fields[HEIGHT][i] = 127;
            fields[TEMPERATURE][i] = -20;
            fields[WATER_LEVEL][i] = 128;
            water[i] = (byte) Water.OCEAN.ordinal();
        }
        terrain.initializeTerrain(
            new TerrainPlan(42, 512, original.recipe(), fields, biomes, water, List.of(), List.of()));
        var function = new SurfaceFunction(terrain);
        var visible = function.visible(Face.TOP, 256, 256);
        var point = function.point(Face.TOP, 256, 256);
        var blocks = function.blocks(Face.TOP, 256, 256);
        assertEquals(127, visible.top());
        assertEquals(127, point.height());
        assertFalse(visible.block().isAir());
        assertEquals(visible.block(), point.materials().getFirst().block());
        assertEquals(visible.block(), blocks[127 - terrain.floor()]);
        assertTrue(blocks[128 - terrain.floor()].isAir());
        assertFalse(
            dev.starfold.worldgen.lod.VoxelColumn.visible(function, Face.TOP, 256, 256).runs().isEmpty());
    }

    @Test
    void revisedRecipesProduceDiverseBiomesPowerLawCratersAndDensityControlledDrainage() throws Exception {
        var home = new TerrainPlanner(42, 512, recipe("home")).generate();
        var observed = new HashSet<String>();
        for (var f : Face.VALUES)
            for (int u = 2; u < 512; u += 8)
                for (int v = 2; v < 512; v += 8) observed.add(home.biome(f,u,v));
        assertTrue(observed.containsAll(recipe("home").biomeIds().stream().map(Object::toString).toList()), observed.toString());
        var sparse = recipe("home").json();
        sparse.getAsJsonObject("erosion").addProperty("river_threshold", 64);
        assertTrue(home.paths().size() > new TerrainPlanner(42,512,new TerrainRecipe(sparse)).generate().paths().size() * 2);
        var next = new HashMap<Integer,Integer>();
        for (var path : home.paths()) next.put(path.nodes().getFirst(),path.nodes().getLast());
        for (int node : next.keySet()) {
            var seen = new HashSet<Integer>();
            for (Integer n = node; n != null; n = next.get(n)) assertTrue(seen.add(n), "acyclic drainage");
        }
        for (String type : List.of("rocky","asteroid","crystal","end","warden")) {
            var plan = new TerrainPlanner(42,512,recipe(type)).generate();
            var craters = plan.landforms().stream().filter(f -> f.type().equals("starfold:crater")).toList();
            assertTrue(craters.size() >= 30, type + " " + craters.size());
            assertTrue(craters.stream().filter(f -> f.radius() < 32).count()
                > craters.stream().filter(f -> f.radius() > 64).count(),type);
        }
    }
    @Test
    void optimizedSurfaceAndRiverCacheMatchColdQueriesAndFullColumns() throws Exception {
        for (String type : List.of("home","ice","desert","rocky","basalt")) {
            var terrain = planet(type).terrain();
            var fixed = terrain.terrainPlan();
            var function = new SurfaceFunction(terrain);
            for (var f : Face.VALUES)
                for (int i = 0; i < 16; i++) {
                    int u = i * 31, v = (i * 73 + 17) % 512;
                    var p = terrain.unit(f,u+.5,v+.5);
                    // Displace the thread-local entry before comparing cold and height-populated queries.
                    fixed.height(Face.TOP,256,256);
                    double cold = fixed.riverLevel(f,u+.5,v+.5,p);
                    fixed.height(f,u+.5,v+.5);
                    assertEquals(cold,fixed.riverLevel(f,u+.5,v+.5,p),1e-10);
                    var point = function.point(f,u,v);
                    var blocks = function.blocks(f,u,v);
                    assertEquals(blocks[(int) point.height()-terrain.floor()],point.materials().getFirst().block(),type);
                }
        }
    }
    @Test
    void realChunksMatchRiversLakesCratersCanyonsVolcanoesAndSeamsInReverseOrder() throws Exception {
        boolean river = false, lake = false;
        for (String type : List.of("home", "rocky", "desert", "basalt", "ice")) {
            var planet = planet(type);
            var terrain = planet.terrain();
            var plan = terrain.terrainPlan();
            var locations = new ArrayList<Cell>();
            locations.add(new Cell(Face.TOP, 0, 0, 240));
            locations.add(new Cell(Face.EAST, 240, 0, 511));
            if (!plan.paths().isEmpty()) {
                river = true;
                var c = plan.chart(plan.position(plan.paths().getFirst().nodes().getFirst()));
                locations.add(
                    new Cell(c.face(), Math.clamp((int) c.u(), 0, 511), 0, Math.clamp((int) c.v(), 0, 511)));
            }
            for (int n = 0; n < plan.nodeCount(); n++)
                if (plan.water(n) == Water.LAKE) {
                    lake = true;
                    var c = plan.chart(plan.position(n));
                    locations.add(new Cell(
                        c.face(), Math.clamp((int) c.u(), 0, 511), 0, Math.clamp((int) c.v(), 0, 511)));
                    break;
                }
            if (!plan.landforms().isEmpty()) {
                var c = plan.chart(plan.landforms().getFirst().center());
                locations.add(new Cell(c.face(), (int) c.u(), 0, (int) c.v()));
            }
            var system = new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT, List.of(planet));
            var function = new SurfaceFunction(terrain);
            Collections.reverse(locations);
            for (var c : locations) {
                var chunk = new ProtoChunk(new ChunkPos(c.face().ordinal() * 256 + c.u() / 16, c.v() / 16),
                    UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384),
                    PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME), null);
                SystemGeneration.fill(system, chunk).join();
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++) {
                        int u = c.u() / 16 * 16 + x, v = c.v() / 16 * 16 + z;
                        var blocks = function.blocks(c.face(), u, v);
                        for (int y = terrain.floor(); y < 320; y++)
                            assertEquals(blocks[y - terrain.floor()],
                                chunk.getBlockState(new BlockPos(c.face().ordinal() * 4096 + u, y, v)),
                                type + " " + c + " y=" + y);
                    }
            }
        }
        assertTrue(river, "real planned river fixture");
        assertTrue(lake, "real priority-flood lake fixture");
    }
}
