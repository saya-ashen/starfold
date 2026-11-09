package dev.starfold.worldgen.plan;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.plan.terrain.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.*;

/** S2 A: evaluated river curves and every shipped biome palette, without a running world. */
class TerrainPlanS2ATest {
    @BeforeAll
    static void boot() throws Exception {
        PlanetPlanFixtures.boot();
    }
    @AfterAll
    static void close() throws Exception {
        PlanetPlanFixtures.close();
    }
    private static TerrainPlan fixed(List<TerrainPlan.Path> paths) throws Exception {
        int count = new Grid(512).positions.length;
        var fields = new float[6][count];
        Arrays.fill(fields[HEIGHT], 180);
        return new TerrainPlan(42, 512, TerrainPlanTest.recipe("home"), fields, new short[count],
            new byte[count], List.of(), paths);
    }
    private static TerrainPlan.Path river(int a, int b, double width) {
        return new TerrainPlan.Path(List.of(a, b), width, 8, 170, Operation.CARVE);
    }
    private static void assertChannel(TerrainPlan plan, Point p) {
        var c = plan.chart(p);
        assertEquals(162, plan.height(c.face(), c.u(), c.v()), .03);
        assertEquals(170, plan.riverLevel(c.face(), c.u(), c.v(), p));
    }
    @Test
    void roundedBendsLeaveTheGridAndConfluencesRetainTheirExactNode() throws Exception {
        var grid = new Grid(512);
        int a = grid.node(Face.TOP, 15, 16), b = grid.node(Face.TOP, 16, 16),
            c = grid.node(Face.TOP, 16, 17), d = grid.node(Face.TOP, 17, 16);
        var bend = fixed(List.of(river(a, b, 1), river(b, c, 1)));
        double lowest = 180;
        Point middle = null;
        for (double u = 246; u < 256; u += .25)
            for (double v = 256.25; v < 266; v += .25) {
                double h = bend.height(Face.TOP, u, v);
                if (h < lowest) {
                    lowest = h;
                    middle = dev.starfold.worldgen.PlanetTerrain.direction(
                        new dev.starfold.chart.PlanetShape(512), Face.TOP, u, v);
                }
            }
        assertTrue(lowest < 162.03, "rounded channel must cross the interior of the grid bend");
        assertChannel(bend, middle);
        assertChannel(bend, grid.positions[a]);
        assertChannel(bend, grid.positions[c]);
        assertEquals(180, bend.height(Face.TOP, 256, 256), 1e-8,
            "the obsolete grid corner must no longer carry this narrow river");
        var decoded = PlanetPlan.decode(new PlanetPlan(512, bend, List.of(), List.of()).compressed()).terrain();
        assertChannel(decoded, middle);
        var junction = fixed(List.of(river(a, b, 1), river(b, c, 1), river(d, b, 1)));
        assertChannel(junction, grid.positions[b]);
        assertChannel(junction, grid.positions[a]);
        assertChannel(junction, grid.positions[c]);
        assertChannel(junction, grid.positions[d]);
    }
    @Test
    void curvedRiverIndexCoversEveryCrossedBucketAndAllTwelveSeams() throws Exception {
        var grid = new Grid(512);
        var paths = new ArrayList<TerrainPlan.Path>();
        var seams = new HashSet<Integer>();
        for (var f : Face.VALUES)
            for (int[] uv : new int[][] {{0, 16}, {32, 16}, {16, 0}, {16, 32}}) {
                int node = grid.node(f, uv[0], uv[1]);
                if (!seams.add(node)) continue;
                int du = uv[0] == 0 || uv[0] == 32 ? 1 : 0, dv = 1 - du;
                paths.add(new TerrainPlan.Path(List.of(grid.node(f, uv[0] - du, uv[1] - dv), node,
                    grid.node(f, uv[0] + du, uv[1] + dv)), 3, 8, 170, Operation.CARVE));
                paths.add(river(node, grid.node(f, uv[0] + dv, uv[1] + du), 3));
            }
        assertEquals(12, seams.size());
        var plan = fixed(paths);
        var decoded = PlanetPlan.decode(new PlanetPlan(512, plan, List.of(), List.of()).compressed()).terrain();
        for (var f : Face.VALUES)
            for (int[] uv : new int[][] {{0, 16}, {32, 16}, {16, 0}, {16, 32}}) {
                assertChannel(plan, grid.positions[grid.node(f, uv[0], uv[1])]);
                assertEquals(162, plan.height(f, uv[0] * 16, uv[1] * 16), .03);
                assertEquals(plan.height(f, uv[0] * 16, uv[1] * 16),
                    decoded.height(f, uv[0] * 16, uv[1] * 16), 1e-10);
            }
        // A long serialized path must index its middle, even far from either endpoint.
        var longPath = fixed(List.of(new TerrainPlan.Path(
            List.of(grid.node(Face.TOP, 8, 16), grid.node(Face.TOP, 24, 16)), 1, 8, 170, Operation.CARVE)));
        for (int u = 130; u < 384; u++)
            assertTrue(longPath.height(Face.TOP, u, 256) < 170, "bucket at " + u);
    }
    @Test
    void allThirteenPalettesAreReachableAndUseEveryWorldsRegionalBiome() throws Exception {
        var used = new HashSet<String>();
        var summary = new JsonObject();
        for (String type : List.of("home", "archipelago", "lush", "desert", "ice", "rocky", "asteroid",
                 "basalt", "crystal", "end", "mushroom", "warden", "flesh")) {
            var file = type.equals("flesh")
                ? java.nio.file.Path.of("biotech/src/main/resources/data/starfold_biotech/starfold/planet_type/flesh.json")
                : java.nio.file.Path.of("worlds/src/main/resources/data/starfold_worlds/starfold/planet_type/" + type + ".json");
            var definition = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var recipe = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, definition.get("terrain")).getOrThrow();
            var plan = new TerrainPlanner(42, 512, recipe, type.equals("flesh") ? 70 : 0).generate();
            var counts = new TreeMap<String, Integer>();
            for (var f : Face.VALUES)
                for (int u = 2; u < 512; u += 4)
                    for (int v = 2; v < 512; v += 4)
                        counts.merge(plan.biome(f, u, v), 1, Integer::sum);
            var declared = new HashSet<String>();
            recipe.biomeIds().forEach(id -> declared.add(id.toString()));
            assertEquals(declared, counts.keySet(), type + " " + counts);
            assertTrue(counts.size() >= 2, type);
            if (List.of("ice", "rocky", "asteroid", "basalt", "flesh").contains(type))
                assertEquals(3, counts.size(), type);
            counts.forEach((id, count) -> assertTrue(count >= 100, type + " visible region " + id + " " + count));
            used.addAll(counts.keySet());
            var values = new JsonObject();
            counts.forEach(values::addProperty);
            summary.add(type, values);
        }
        try (var files = Files.list(java.nio.file.Path.of("worlds/src/main/resources/data/starfold_worlds/worldgen/biome"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList())
                assertTrue(used.contains("starfold_worlds:" + file.getFileName().toString().replace(".json", "")), file.toString());
        }
        Files.createDirectories(java.nio.file.Path.of("build/terrain-plan"));
        Files.writeString(java.nio.file.Path.of("build/terrain-plan/s2-a-biome-counts.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(summary));
    }
    @Test
    void overridesAndInlinePalettesStayComplete() throws Exception {
        var root = java.nio.file.Path.of("worlds/src/main/resources/data/starfold_worlds/starfold/planet_type");
        try (var files = Files.list(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var original = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("terrain");
                var override = JsonParser.parseString(Files.readString(java.nio.file.Path.of(
                    "modpack/kubejs/data/starfold_worlds/starfold/planet_type").resolve(file.getFileName())))
                    .getAsJsonObject().getAsJsonObject("terrain");
                original.remove("ores");
                override.remove("ores");
                assertEquals(original, override, file.toString());
            }
        }
        try (var files = Files.walk(java.nio.file.Path.of("src/main/resources"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var text = Files.readString(file);
                if (text.contains("\"planet_biomes\"")) assertInlinePalette(JsonParser.parseString(text));
            }
        }
    }
    private static void assertInlinePalette(JsonElement element) {
        if (element.isJsonObject()) {
            var object = element.getAsJsonObject();
            if (object.has("planet_biomes") && object.has("planet_generation")) {
                var recipe = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE,
                    object.getAsJsonObject("planet_generation").get("recipe")).getOrThrow();
                var palette = new HashSet<String>();
                object.getAsJsonObject("planet_biomes").getAsJsonArray("palette")
                    .forEach(id -> palette.add(id.getAsString()));
                assertEquals(recipe.biomeIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()),
                    palette);
            }
            object.entrySet().forEach(e -> assertInlinePalette(e.getValue()));
        } else if (element.isJsonArray()) element.getAsJsonArray().forEach(TerrainPlanS2ATest::assertInlinePalette);
    }
    @Test
    void terrainRuleUsesPhysicalSlopeAndLandformReliefAndRejectsInvalidParameters() throws Exception {
        var json = TerrainPlanTest.recipe("rocky").json();
        var parameters = json.getAsJsonArray("biomes").get(0).getAsJsonObject().getAsJsonObject("parameters");
        parameters.addProperty("landform_max",-2);
        var rule = new TerrainRecipe(json).biomes().getFirst();
        assertTrue(rule.matches(new TerrainRegistries.BiomeContext(0, 0, 150, false, false, 0, -4)));
        assertFalse(rule.matches(new TerrainRegistries.BiomeContext(0, 0, 150, false, false, 0, 4)));
        var highVolcano = TerrainPlanTest.recipe("basalt").json();
        var highRules = highVolcano.getAsJsonArray("biomes");
        highRules.asList().subList(1, highRules.size()).clear();
        var high = highRules.get(0).getAsJsonObject().getAsJsonObject("parameters");
        high.remove("region_min");high.remove("region_max");
        high.addProperty("height_min", 318);
        high.addProperty("height_max", 318);
        high.addProperty("slope_max", 200);
        high.addProperty("landform_min", -2048);
        high.addProperty("landform_max", 2048);
        var planner = new TerrainPlanner(42, 512, new TerrainRecipe(highVolcano));
        Arrays.fill(planner.fields[HEIGHT], 344);
        planner.biomes();
        for (short biome : planner.biomes) assertEquals(0, biome, "tall overlaps use the generated height");
        parameters.addProperty("slope_min", -1);
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, json).isError());
        parameters.addProperty("slope_min", 0);
        parameters.addProperty("landform_max", -3000);
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, json).isError());
        // An uncovered table is a configuration error, never an implicit last-biome fallback.
        json = TerrainPlanTest.recipe("home").json();
        json.getAsJsonArray("biomes").forEach(r -> {
            var p = r.getAsJsonObject().getAsJsonObject("parameters");
            p.addProperty("height_min", 319);
            p.addProperty("height_max", 320);
        });
        var uncovered = new TerrainRecipe(json);
        assertThrows(IllegalStateException.class, () -> new TerrainPlanner(42, 512, uncovered).generate());
    }
}
