package dev.starfold.biotech.terrain;

import static org.junit.jupiter.api.Assertions.*;
import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.plan.PlanetPlan;
import dev.starfold.worldgen.plan.terrain.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.*;

class HiveLandformTest {
    private static com.google.gson.JsonObject definition() throws Exception {
        try (var in = HiveLandformTest.class.getResourceAsStream("/data/starfold_biotech/starfold/planet_type/flesh.json")) {
            return JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
        }
    }
    @BeforeAll static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test
    void hiveHasSphericalVoronoiWallsAndSavedFeaturesCannotInvadeAnomalies() throws Exception {
        var definition =
            definition();
        var recipe = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, definition.get("terrain")).getOrThrow();
        var plan = new TerrainPlanner(42, 512, recipe, 70).generate();
        assertFalse(plan.landforms().isEmpty());
        var form = plan.landforms().getFirst();
        assertTrue(form.parameters().getAsJsonArray("neighbours").size() >= 5);
        var center = form.center();
        var tangent = center.cross(Math.abs(center.y()) < .8 ? Face.TOP.normal : Face.EAST.normal);
        tangent = tangent.scale(1 / tangent.length());
        var other = center.cross(tangent);
        double angularVariation = 0;
        var noise = new dev.starfold.worldgen.noise.TerrainNoise(42);
        for (double radius = .05; radius <= 1; radius += .05) {
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 64; i++) {
                double angle = i * Math.PI / 32;
                var p = center.add(tangent.scale(radius * Math.cos(angle)))
                            .add(other.scale(radius * Math.sin(angle)));
                p = p.scale(1 / p.length());
                double height = TerrainRegistries.LANDFORMS.get(form.type()).implementation()
                                    .evaluate(p, form, 512, noise) * form.amplitude();
                low = Math.min(low, height);
                high = Math.max(high, height);
            }
            angularVariation = Math.max(angularVariation, high - low);
        }
        assertTrue(angularVariation > 1,
            "Voronoi walls vary with angle on at least one circle; radial rings cannot pass");
        var fields = new float[6][plan.nodeCount()];
        var biomes = new short[plan.nodeCount()];
        var water = new byte[plan.nodeCount()];
        var corner = new Point(1, 1, 1).scale(1 / Math.sqrt(3));
        var invading = new Landform(
            form.type(), form.parameters(), corner, form.radius(), form.amplitude(), form.operation());
        var invalid = new TerrainPlan(42, 512, recipe, fields, biomes, water, List.of(invading), List.of());
        assertThrows(IllegalArgumentException.class,
            () -> PlanetPlan.decode(new PlanetPlan(512, invalid, List.of(), List.of()).compressed()));
    }

    @Test void fixedSeedOutputsMatchPreMoveBaseline() throws Exception {
        var definition = definition();
        var recipe = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, definition.get("terrain")).getOrThrow();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (long seed : new long[] {42, 918273}) {
            var plan = new TerrainPlanner(seed, 512, recipe, 70).generate();
            byte[] bytes = new PlanetPlan(512, plan, List.of(), List.of()).compressed();
            assertEquals(seed == 42
                    ? "28597a72c5cbd55f55c22a32ec45f83e6eae7e0821e722f4eb011a6aa3eec5ef"
                    : "3acf86f5cca4fa6d1fe83b3f3c512479ba14c2d9783376b17db26e8038cc26ea",
                    HexFormat.of().formatHex(digest.digest(bytes)));
            var samples = new StringBuilder();
            for (var form : plan.landforms()) {
                samples.append(form.center()).append(form.parameters()).append(form.radius()).append(form.amplitude());
                var noise = new dev.starfold.worldgen.noise.TerrainNoise(seed);
                for (var face : Face.VALUES)
                    samples.append(TerrainRegistries.LANDFORMS.get(form.type()).implementation().evaluate(face.normal, form, 512, noise));
            }
            assertEquals(seed == 42
                    ? "103421aa848e952dcfffff02beb9fa330af0f56e58bd6f9de28e5537fb92f5f5"
                    : "28339647b862fc7419fe8b0c8ba307f49f78f465f0dca8d5a0519d052a358d8f",
                    HexFormat.of().formatHex(digest.digest(samples.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
    }
}
