package dev.starfold.worldgen.surface;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.plan.terrain.TerrainPlan;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;
import dev.starfold.worlds.fixtures.ExperienceFixtures;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Land scatter must not overwrite caps when aquatic scatter is allowed below water. */
class SurfaceOverlayTest {
    @BeforeAll
    static void boot() throws Exception {
        ExperienceFixtures.boot();
        SurfaceTestTags.bind();
    }

    @Test
    void grassStaysAboveBareGroundAndLeavesTheSnowCapIntact() throws Exception {
        for (int temperature : new int[] {-5, 20}) {
            var terrain = land(temperature);
            var face = Face.NORTH;
            int u = 160, v = 160, ground = terrain.groundY(face, u, v);
            assertEquals(100, ground);
            var grass = Blocks.SHORT_GRASS.defaultBlockState();
            assertEquals(
                    grass,
                    SurfaceGenerationTest.at(terrain.scatter().column(face, u, v), ground + 1),
                    "The raw scatter must exercise the snow/grass collision");
            var function = new SurfaceFunction(terrain);
            var visible = function.visible(face, u, v);
            boolean snowy = temperature < 0;
            var cap = snowy ? Blocks.SNOW.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            assertEquals(ground + (snowy ? 1 : 0), visible.top());
            assertEquals(cap, visible.block());
            assertEquals(visible, function.visibleExact(face, u, v));
            var point = function.point(face, u, v);
            assertEquals((double) visible.top(), point.height());
            assertEquals(List.of(new SurfaceFunction.Coverage(cap, 1)), point.materials());
            assertEquals(0, point.canopyCoverage());
            assertTrue(visible.objects().stream().allMatch(r -> r.bottom() > visible.top()));
            assertEquals(
                    snowy ? Blocks.AIR.defaultBlockState() : grass,
                    SurfaceGenerationTest.at(visible.objects(), ground + 1));
            var blocks = function.blocks(face, u, v);
            assertEquals(cap, blocks[visible.top() - terrain.floor()]);
            assertEquals(snowy ? cap : grass, blocks[ground + 1 - terrain.floor()]);
            for (var side : visible.sides())
                if (side.bottom() <= visible.top())
                    assertEquals(cap, SurfaceGenerationTest.at(side.runs(), visible.top()));
        }
    }

    private static PlanetTerrain land(int temperature) throws IOException {
        var base = SurfaceGenerationTest.generator("home").generation();
        var json = base.recipe().json();
        json.getAsJsonObject("water").addProperty("sea_level", 100);
        var materials = json.getAsJsonArray("materials");
        var material = materials.get(0).getAsJsonObject().deepCopy();
        var p = material.getAsJsonObject("parameters");
        p.addProperty("block", "minecraft:dirt");
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
        Arrays.fill(fields[TerrainPlan.WATER_LEVEL], 100);
        Arrays.fill(fields[TerrainPlan.TEMPERATURE], temperature);
        Arrays.fill(fields[TerrainPlan.HUMIDITY], 1);
        var waters = new byte[count];
        Arrays.fill(waters, (byte) TerrainPlan.Water.OCEAN.ordinal());
        terrain.initializeTerrain(
                new TerrainPlan(
                        42, 512, recipe, fields, new short[count], waters, List.of(), List.of()));
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(1);
            out.writeUTF(terrain.biome(Face.NORTH, 162, 162));
            out.writeInt(1);
            out.writeUTF("grass");
            out.writeUTF("minecraft:simple_block");
            out.writeDouble(1);
            out.writeInt(1);
            new FeatureSandbox.Template(
                            new java.util.TreeMap<>(
                                    Map.of(
                                            FeatureSandbox.key(0, 1, 0),
                                            Blocks.SHORT_GRASS.defaultBlockState())))
                    .write(out);
        }
        terrain.initializeSurface(
                new SurfacePolicy(
                        "land-cap-test", Base64.getEncoder().encodeToString(bytes.toByteArray())),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("plains"));
        return terrain;
    }
}
