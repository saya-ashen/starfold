package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.Lifecycle;

import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** CPU geometry/material inputs and the production GLSL wiring, not a GL render test. */
class GroundFarTopLightTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static double light(Point sun, Point up, Point normal) {
        return .12 + .88 * (.6 * Math.max(0, sun.dot(up)) + .4 * Math.max(0, sun.dot(normal)));
    }

    static Point unit(Point p) {
        return p.scale(1 / p.length());
    }

    static int[] texture(String name) throws Exception {
        try (var in = TestArchiveResources.open("/assets/minecraft/textures/block/" + name + ".png");
                var image = NativeImage.read(in)) {
            return image.getPixelsRGBA();
        }
    }

    @Test
    void actualFarAndResidentFlatTopsHaveEqualNoonLightAndEncodedAlbedo() throws Exception {
        var shape = PlanetShape.STANDARD;
        var materials = List.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.OAK_LEAVES,
                Blocks.JUNGLE_LEAVES);
        String[] textures = {"stone", "grass_block_top", "oak_leaves", "jungle_leaves"};
        int[] tints = {0xffffff, 0x91bd59, 0x59c93c, 0x59c93c};
        var report = new StringBuilder("Flat step-16 far / resident tops, noon, sky=1, AO=1\n");
        for (int m = 0; m < materials.size(); m++) {
            var material = materials.get(m).defaultBlockState();
            int albedo = GroundAlbedo.average(texture(textures[m]), tints[m]);
            for (var face : Face.values()) {
                var far = GroundFarMesh.build(shape, face.ordinal() * 64, 16,
                                (f, x, z, s) -> new GroundFarMesh.Surface(6, material)).stream()
                        .filter(q -> q.axis() == 1 && GroundFarMeshTest.min(q) == 6
                                && q.points().stream().mapToDouble(Point::x).min().orElseThrow() == 192
                                && q.points().stream().mapToDouble(Point::z).min().orElseThrow() == 192).findFirst().orElseThrow();
                var resident = new VoxelMesher.Quad(material, 3,
                        VoxelMesher.points(3, 192, 5, 192, 208, 6, 208));
                assertEquals(resident.material(), far.material());
                assertEquals(GroundAlbedo.position(face.ordinal(), resident.points()),
                        GroundAlbedo.position(face.ordinal(), far.points()));
                var up = shape.direction(face, 200, 200);
                double[] lights = new double[2];
                int i = 0;
                for (var q : List.of(far, resident)) {
                    var p = GroundCapturedMesh.project(shape, face, q.points());
                    var normal = unit(p.get(1).sub(p.getFirst()).cross(p.get(2).sub(p.getFirst())));
                    assertTrue(normal.dot(up) > .999, "outward flat top on " + face);
                    lights[i++] = light(up, up, normal);
                }
                assertEquals(1, lights[0], .001);
                assertEquals(lights[1], lights[0], .001);
                for (int shift : new int[] {16, 8, 0}) {
                    double encoded = (albedo >> shift & 255) / 255.0;
                    // BufferBuilder.setNormal stores signed normalised bytes, not AO/shade.
                    double decoded = ((int) (GroundPipeline.channel(albedo, shift) * 127)
                            / 127.0) * .5 + .5;
                    assertEquals(encoded, decoded, 1 / 254.0);
                    assertEquals(decoded * lights[1], decoded * lights[0], .001);
                }
                report.append(textures[m]).append(' ').append(face).append(" albedo=#")
                        .append(String.format("%06x", albedo)).append(" farLight=")
                        .append(lights[0]).append(" residentLight=").append(lights[1]).append('\n');
            }
        }
        Files.writeString(Path.of("build/lod3-far-light-inputs.txt"), report);
    }

    @Test
    void foliageTintFollowsEachCellNotItsEnclosingPatch() throws Exception {
        var low = ResourceLocation.parse("test:dark_foliage");
        var high = ResourceLocation.parse("test:bright_foliage");
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        Registry.register(registry, low, biome(0x204008));
        Registry.register(registry, high, biome(0x80c040));
        var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry.freeze()));
        var terrain=PlanetBiomeFixtures.tinted(List.of(low,high));
        var view=PlanetTint.view(null,access,0,terrain,low);
        ColorResolver foliage = (biome, x, z) -> biome.getFoliageColor();
        var origin = PlanetTint.position(0, 0, 0, 128, 0);
        int oldTint = view.getBlockTint(origin, foliage), different = 0;
        int[] leaves = texture("jungle_leaves");
        for (var far : GroundFarMesh.build(PlanetShape.STANDARD, 0, 16,
                (f, x, z, s) -> new GroundFarMesh.Surface(6, Blocks.JUNGLE_LEAVES.defaultBlockState()))) {
            if (far.axis() != 1 || GroundFarMeshTest.min(far) != 6) continue;
            int x = (int) far.points().stream().mapToDouble(Point::x).min().orElseThrow();
            int z = (int) far.points().stream().mapToDouble(Point::z).min().orElseThrow();
            var resident = new VoxelMesher.Quad(far.material(), 3,
                    VoxelMesher.points(3, x, 5, z, x + 16, 6, z + 16));
            var farPos = GroundAlbedo.position(0, far.points());
            var residentPos = GroundAlbedo.position(0, resident.points());
            int tint = view.getBlockTint(farPos, foliage);
            assertEquals(view.getBlockTint(residentPos, foliage), tint);
            if (tint != oldTint) {
                assertNotEquals(GroundAlbedo.average(leaves, oldTint),
                        GroundAlbedo.average(leaves, tint), "negative control: old patch-origin tint");
                different++;
            }
        }
        assertTrue(different > 0, "fixture must cross a regional boundary inside the patch");
    }

    static Biome biome(int foliage) {
        return new Biome.BiomeBuilder().hasPrecipitation(false).temperature(.8f).downfall(.9f)
                .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0)
                        .waterFogColor(0).skyColor(0).foliageColorOverride(foliage).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY).build();
    }

    @Test
    void tintUsesVanillaSquareEncodedColourAverage() {
        var low=ResourceLocation.parse("test:low");
        var high=ResourceLocation.parse("test:high");
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        Registry.register(registry,low,biome(0x204008));
        Registry.register(registry,high,biome(0x80c040));
        var access=new RegistryAccess.ImmutableRegistryAccess(List.of(registry.freeze()));
        var terrain=PlanetBiomeFixtures.tinted(List.of(low,high));
        var exact=PlanetTint.view(null,access,0,terrain,low);
        var blended=PlanetTint.view(null,access,0,terrain,low,2);
        var p=new BlockPos(100,128,100);
        ColorResolver resolver=(b,x,z)->b.getFoliageColor();
        int r=0,g=0,b=0;
        for(int z=-2;z<=2;z++) for(int x=-2;x<=2;x++) {
            int c=exact.getBlockTint(p.offset(x,0,z),resolver);
            r+=c>>16&255;g+=c>>8&255;b+=c&255;
        }
        assertEquals((r/25)<<16|(g/25)<<8|b/25,blended.getBlockTint(p,resolver));
    }

    @Test
    void wallsOnIntegerEdgesSampleTheirEmittingBiome() {
        var material = Blocks.JUNGLE_LEAVES.defaultBlockState();
        for (int side = 0; side < 4; side++) {
            var wall = new ArrayList<VoxelMesher.Quad>();
            GroundFarMesh.wall(wall, material, 48, 64, 64, 80, 6, -64, side);
            var pos = GroundAlbedo.position(0,wall.getFirst().points());
            assertEquals(side == 0 ? 48 : side == 1 ? 63 : 56,pos.getX());
            assertEquals(side == 2 ? 64 : side == 3 ? 79 : 72,pos.getZ());
        }
    }

    @Test
    void actualAdaptersShareTintAndFullSkyWithoutAnExtraAoOrDaylightFactor() throws Exception {
        var renderer = Sources.text("GroundMeshVertices");
        assertTrue(renderer.contains("GroundAlbedo.position(builders.surfaceFace,points)"));
        assertTrue(Sources.text("GroundRenderer").contains("GroundAlbedo.position(face,q.points())"));
        assertFalse(renderer.contains("newBlockPos(builders.surfaceFace*4096+x,128,z)"));
        assertTrue(renderer.contains(".setUv2(repeat?GroundFarTexture.upper(sprite.getU1()):240,repeat?GroundFarTexture.upper(sprite.getV1()):240).setNormal(GroundPipeline.channel(albedo,16)"));
        String vertex = CoreShaderSource.patch("rendertype_solid", true,
                VoxelFaceShaderTest.resource("core/rendertype_solid.vsh"));
        assertTrue(vertex.contains("cpNativeSkyVisibility=1.0"));
        assertFalse(vertex.contains("vertexColor.rgb/=max"));
        assertTrue(vertex.contains("vertexColor.rgb=texture(Sampler2,clamp(cpNativeLightUV2/256.0,vec2(0.5/16.0),vec2(15.5/16.0))).rgb"));
        assertEquals(15,GroundFarTexture.sky(0));
        assertEquals(12,GroundFarTexture.sky(3));
        assertEquals(0,GroundFarTexture.sky(30));
        assertTrue(vertex.contains("vertexColor.rgb=mix(cpSurfaceColor.rgb,Normal*0.5+0.5,CPNativeLowFrequency)"));
        assertEquals(1, GroundPipeline.lowFrequency(360 - 128));
        assertEquals(1, GroundPipeline.lowFrequency(360));
        assertTrue(GroundShaderSource.SOLAR_LIGHT.contains("return 0.6*skyDay+0.4*faceDay"));
    }
}
