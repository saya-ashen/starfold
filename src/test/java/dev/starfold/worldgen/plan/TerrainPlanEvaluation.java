package dev.starfold.worldgen.plan;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.universe.*;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.plan.terrain.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real fixed-plan timing and evaluated-column atlas; no Level, GUI, shader or GPU. */
public final class TerrainPlanEvaluation {
    private static volatile double sink;
    private static final List<String> TYPES = List.of("home", "archipelago", "lush", "desert", "ice", "rocky",
        "asteroid", "basalt", "crystal", "end", "mushroom", "warden", "flesh");

    public static void run(String[] args) throws Exception {
        PlanetPlanFixtures.boot();
        var output = Path.of("build/terrain-plan");
        Files.createDirectories(output);
        try {
            if (Arrays.asList(args).contains("--coarse-memory")) {
                coarseMemory(output);
                return;
            }
            if (Arrays.asList(args).contains("--structure-benchmark")) {
                var directory=output.resolve("s3-structure-benchmark");Files.createDirectories(directory);
                TerrainPlanS2Evaluation.naturalHome(directory,true);
                return;
            }
            if (Arrays.asList(args).contains("--natural")) {
                TerrainPlanS2Evaluation.naturalHome(output);
                return;
            }
            if (Arrays.asList(args).contains("--atlas")) {
                for (String type : TYPES) atlas(type, output);
                TerrainPlanS2Evaluation.scenes(output);
                return;
            }
            var recipe = recipe(definition("home"));
            profile(recipe, output);
            if (Arrays.asList(args).contains("--profile")) return;
            try (var csv = Files.newBufferedWriter(output.resolve("measurements.csv"));
                 var roadCsv = Files.newBufferedWriter(output.resolve("road-measurements.csv"))) {
                roadCsv.write("size,road_ms,road_count,road_nodes,gzip_bytes_with_two_synthetic_sites\n");
                csv.write("size,terrain_ms,plan_gzip_bytes,profile_bytes,max_packet_bytes,parts,height_ns,"
                          + "point_ns,extra_point_ns,coarse_setup_ms,coarse16_ns,coarse64_ns,sha256\n");
                for (int size = 416; size <= 2048; size += 16) {
                    long start = System.nanoTime();
                    var fixed = new TerrainPlanner(42, size, recipe).generate();
                    double ms = (System.nanoTime() - start) / 1e6;
                    var terrain = new PlanetTerrain(42, PlanetTerrain.VERSION,
                        generation("home", size, recipe, PlanetEnvironment.defaults(true, size)));
                    terrain.initializeTerrain(fixed);
                    var plan = terrain.plan();
                    var profile =
                        new PlanetPackets.Profile(17, 42, PlanetTerrain.VERSION, terrain.generation(),
                            net.minecraft.resources.ResourceLocation.withDefaultNamespace("plains"),
                            Optional.empty(), Optional.of(plan));
                    var transfer = PlanetPlanSync.prepare(profile, PlanetPlanFixtures.context.registries());
                    long summaryStart=System.nanoTime();
                    var function = new SurfaceFunction(terrain);
                    double summaryMs=(System.nanoTime()-summaryStart)/1e6;
                    var random = new Random(42);
                    var face = new Face[1024];
                    var u = new int[1024];
                    var v = new int[1024];
                    for (int i = 0; i < u.length; i++) {
                        face[i] = Face.VALUES[i % 6];
                        u[i] = random.nextInt(size);
                        v[i] = random.nextInt(size);
                    }
                    for (int i = 0; i < 3; i++) {
                        measure(terrain, function, face, u, v, false);
                        measure(terrain, function, face, u, v, true);
                    }
                    double height = Double.MAX_VALUE, point = Double.MAX_VALUE;
                    for (int i = 0; i < 3; i++) {
                        height = Math.min(height, measure(terrain, function, face, u, v, false));
                        point = Math.min(point, measure(terrain, function, face, u, v, true));
                    }
                    double coarse16=measureCoarse(function,face,u,v,16),coarse64=measureCoarse(function,face,u,v,64);
                    String row = String.format(Locale.ROOT, "%d,%.3f,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.3f,%.1f,%.1f,%s%n", size,
                        ms, plan.compressed().length, transfer.fullProfileBytes(), transfer.maxPacketBytes(),
                        transfer.parts().size(), height, point, point - height, summaryMs,coarse16,coarse64,plan.identity());
                    csv.write(row);
                    csv.flush();
                    System.out.print("TERRAIN_EVAL " + row);
                    TerrainPlanS2Evaluation.roadMeasurement(fixed, roadCsv);
                    roadCsv.flush();
                }
            }
            for (String type : TYPES) atlas(type, output);
            TerrainPlanS2Evaluation.scenes(output);
        } finally {
            PlanetPlanFixtures.close();
        }
    }
    private static void coarseMemory(Path output) throws Exception {
        var recipe=recipe(definition("home"));
        var terrain=new PlanetTerrain(42,PlanetTerrain.VERSION,generation("home",2048,recipe,PlanetEnvironment.defaults(true,2048)));
        terrain.terrainPlan();terrain.plan();
        System.gc();long before=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long start=System.nanoTime();var function=new SurfaceFunction(terrain);double setup=(System.nanoTime()-start)/1e6;
        System.gc();long after=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long reuseStart=System.nanoTime();var reused=new SurfaceFunction(terrain);long reuse=System.nanoTime()-reuseStart;
        sink=function.sample(Face.TOP,100,100,64).height()+reused.sample(Face.TOP,100,100,64).height();
        var result=new JsonObject();result.addProperty("size",2048);result.addProperty("setup_ms",setup);
        result.addProperty("retained_heap_delta_bytes",after-before);result.addProperty("reused_constructor_ns",reuse);
        result.addProperty("scope","GC heap delta includes the summary and caches induced by its construction; no structures or vegetation; cold JVM.");
        Files.writeString(output.resolve("s3-coarse-memory.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("COARSE_MEMORY "+result);
    }
    private static void profile(TerrainRecipe recipe, Path output) throws Exception {
        var terrain = new PlanetTerrain(42, PlanetTerrain.VERSION,
            generation("home", 2048, recipe, PlanetEnvironment.defaults(true, 2048)));
        var function = new SurfaceFunction(terrain);
        var random = new Random(71);
        int[] u = new int[8192], v = new int[8192];
        for (int i = 0; i < u.length; i++) { u[i] = random.nextInt(2048); v[i] = random.nextInt(2048); }
        var results = new JsonObject();
        for (String part : List.of("height", "temperature", "biome", "liquid", "surfaceTop", "surfaceState", "point")) {
            double[] rounds = new double[11];
            for (int round = 0; round < rounds.length; round++) {
                double sum = 0;
                long start = System.nanoTime();
                for (int i = 0; i < u.length; i++) {
                    var f = Face.VALUES[i % 6]; int a = u[i], b = v[i];
                    sum += switch (part) {
                        case "height" -> terrain.groundY(f,a,b);
                        case "temperature" -> terrain.temperature(f,a,b);
                        case "biome" -> terrain.biome(f,a,b).hashCode();
                        case "liquid" -> { var liquid = terrain.liquid(f,a,b); yield liquid == null ? 0 : liquid.level(); }
                        case "surfaceTop" -> terrain.surfaceTop(f,a,b,140);
                        case "surfaceState" -> terrain.surfaceState(f,a,b).hashCode();
                        default -> function.point(f,a,b).height();
                    };
                }
                sink = sum;
                rounds[round] = (System.nanoTime() - start) / (double) u.length;
            }
            var warm = Arrays.copyOfRange(rounds, 5, rounds.length); Arrays.sort(warm);
            results.addProperty(part + "_median_ns", (warm[2]+warm[3])*.5);
        }
        Files.writeString(output.resolve("profile.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        System.out.println("TERRAIN_PROFILE " + results);
    }
    private static JsonObject definition(String type) throws Exception {
        var path = type.equals("flesh")
            ? Path.of("biotech/src/main/resources/data/starfold_biotech/starfold/planet_type/flesh.json")
            : Path.of(
                  "worlds/src/main/resources/data/starfold_worlds/starfold/planet_type/" + type + ".json");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static TerrainRecipe recipe(JsonObject definition) {
        return TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, definition.get("terrain")).getOrThrow();
    }
    private static PlanetGeneration generation(
        String type, int size, TerrainRecipe recipe, PlanetEnvironment environment) {
        return new PlanetGeneration(type, PlanetGeneration.SURFACE_VERSION, recipe, size, 191, "core_shell",
            false, false, false, environment);
    }
    private static double measureCoarse(SurfaceFunction function,Face[] faces,int[] u,int[] v,int level) {
        double best=Double.MAX_VALUE;
        for(int round=0;round<5;round++) {
            double sum=0;long start=System.nanoTime();
            for(int i=0;i<u.length;i++)sum+=function.sample(faces[i],u[i],v[i],level).height();
            sink=sum;if(round>=3)best=Math.min(best,(System.nanoTime()-start)/(double)u.length);
        }
        return best;
    }
    private static double measure(
        PlanetTerrain terrain, SurfaceFunction function, Face[] face, int[] u, int[] v, boolean point) {
        long start = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < u.length; i++)
            sum +=
                point ? function.point(face[i], u[i], v[i]).height() : terrain.groundY(face[i], u[i], v[i]);
        sink = sum;
        return (System.nanoTime() - start) / (double) u.length;
    }
    private static void atlas(String type, Path output) throws Exception {
        var definition = definition(type);
        var recipe = recipe(definition);
        var environment =
            PlanetTypeEnvironment.CODEC
                .parse(JsonOps.INSTANCE,
                    definition.has("environment") ? definition.get("environment") : new JsonObject())
                .getOrThrow()
                .resolve(true, 512);
        var terrain =
            new PlanetTerrain(42, PlanetTerrain.VERSION, generation(type, 512, recipe, environment));
        var function = new SurfaceFunction(terrain);
        var plan = terrain.terrainPlan();
        int tile = 128;
        var images = new LinkedHashMap<String, BufferedImage>();
        for (String layer : List.of("height", "ground", "biomes", "rivers", "materials", "landforms"))
            images.put(layer, new BufferedImage(tile * 3, tile * 2, BufferedImage.TYPE_INT_RGB));
        var metadata = new JsonObject();
        var biomeLegend = new JsonObject();
        var materialLegend = new JsonObject();
        var faceOrder = new JsonArray();
        for (var f : Face.VALUES) faceOrder.add(f.name());
        metadata.addProperty("type", type);
        metadata.addProperty("seed", 42);
        metadata.addProperty("size", 512);
        metadata.addProperty("sampling",
            "4-block centres; height/materials are SurfaceFunction.point results; water is the evaluated "
            + "local liquid, landforms are footprint overlays; features=false, structures=false, standard "
            + "depth=191; CPU only");
        metadata.addProperty("height_gray_scale", "[-64,320] mapped linearly to [0,255]; height includes liquid/snow, ground exposes the seabed");
        metadata.addProperty("landform_count", plan.landforms().size());
        metadata.addProperty("river_segment_count", plan.paths().size());
        metadata.addProperty("cross_layout", "NORTH above TOP; WEST, TOP, EAST, BOTTOM (rotated 180 degrees) in the middle; SOUTH below TOP");
        metadata.add("face_order_left_to_right_then_top_to_bottom", faceOrder);
        metadata.add("biome_colors", biomeLegend);
        metadata.add("material_colors", materialLegend);
        metadata.addProperty("water_colors", "dry=#302820, ocean=#2050c0, lake=#20c0ff, river=#20ff90");
        for (var f : Face.VALUES)
            for (int y = 0; y < tile; y++)
                for (int x = 0; x < tile; x++) {
                    int u = x * 4 + 2, v = y * 4 + 2, px = f.ordinal() % 3 * tile + x,
                        py = f.ordinal() / 3 * tile + y;
                    var cell = function.point(f, u, v);
                    int shade = (int) Math.clamp((cell.height() + 64) * 255 / 384, 0, 255);
                    images.get("height").setRGB(px, py, (shade << 16) | (shade << 8) | shade);
                    int ground = terrain.groundY(f, u, v);
                    int groundShade = (int) Math.clamp((ground + 64) * 255 / 384, 0, 255);
                    images.get("ground").setRGB(px, py, (groundShade << 16) | (groundShade << 8) | groundShade);
                    String biome = terrain.biome(f, u, v),
                           material = cell.materials().getFirst().block().toString();
                    int biomeColor = color(biome), materialColor = color(material);
                    biomeLegend.addProperty(biome, String.format("#%06x", biomeColor));
                    materialLegend.addProperty(material, String.format("#%06x", materialColor));
                    images.get("biomes").setRGB(px, py, biomeColor);
                    images.get("materials").setRGB(px, py, materialColor);
                    var liquid = terrain.liquid(f, u, v);
                    int waterColor = 0x302820;
                    if (liquid != null && terrain.groundY(f, u, v) < liquid.level()) {
                        boolean river = Double.isFinite(
                            plan.riverLevel(f, u + .5, v + .5, terrain.unit(f, u + .5, v + .5)));
                        waterColor = river                                  ? 0x20ff90
                            : plan.water(f, u, v) == TerrainPlan.Water.LAKE ? 0x20c0ff
                                                                            : 0x2050c0;
                    }
                    images.get("rivers").setRGB(px, py, waterColor);
                    int forms = 0;
                    var p = terrain.unit(f, u + .5, v + .5);
                    for (var form : plan.landforms())
                        if (p.sub(form.center()).length() * 256 < form.radius())
                            forms = color(form.type());
                    images.get("landforms").setRGB(px, py, forms);
                }
        for (var entry : images.entrySet()) {
            ImageIO.write(entry.getValue(), "png", output.resolve(type + "-" + entry.getKey() + ".png").toFile());
            var cross = new BufferedImage(tile * 4, tile * 3, BufferedImage.TYPE_INT_RGB);
            // TOP in the centre; NORTH above, SOUTH below, WEST/EAST on either side;
            // BOTTOM attaches to EAST. Each transform follows the actual face axes.
            for (var f : Face.VALUES)
                for (int y = 0; y < tile; y++)
                    for (int x = 0; x < tile; x++) {
                        int dx = 0, dy = 0;
                        switch (f) {
                            case TOP -> { dx = tile + x; dy = tile + y; }
                            case NORTH -> { dx = tile + x; dy = y; }
                            case SOUTH -> { dx = tile + x; dy = 2 * tile + y; }
                            case WEST -> { dx = x; dy = tile + y; }
                            case EAST -> { dx = 2 * tile + x; dy = tile + y; }
                            case BOTTOM -> { dx = 4 * tile - 1 - x; dy = 2 * tile - 1 - y; }
                        }
                        cross.setRGB(dx, dy, entry.getValue().getRGB(f.ordinal() % 3 * tile + x,
                            f.ordinal() / 3 * tile + y));
                    }
            ImageIO.write(cross, "png", output.resolve(type + "-" + entry.getKey() + "-cross.png").toFile());
        }
        // Section passes through a planned river, otherwise a landform, otherwise the face centre.
        var focus = !plan.paths().isEmpty()
            ? plan.chart(plan.position(plan.paths().getFirst().nodes().getFirst()))
            : !plan.landforms().isEmpty() ? plan.chart(plan.landforms().getFirst().center())
                                          : new TerrainPlan.Chart(Face.TOP, 256, 256);
        metadata.addProperty("section",
            focus.face() + " centre=" + focus.u() + "," + focus.v()
                + ("; horizontal axis covers 512 block columns, vertical -64..319; colors use block-state "
                   + "hashes"));
        var section = new BufferedImage(512, 384, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 512; x++) {
            var blocks = function.blocks(focus.face(), (int) focus.u() - 256 + x, (int) focus.v());
            for (int y = -64; y < 320; y++) {
                var state = blocks[y + 64];
                section.setRGB(x, 319 - y, state.isAir() ? 0x101820 : color(state.toString()));
            }
        }
        ImageIO.write(section, "png", output.resolve(type + "-section.png").toFile());
        Files.writeString(output.resolve(type + "-atlas.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata));
        System.out.println("TERRAIN_ATLAS " + type);
    }
    private static int color(String id) {
        return 0x404040 | (id.hashCode() & 0xbfbfbf);
    }
}
