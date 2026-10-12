package dev.starfold.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.render.GroundSurfaceIndex;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetSpawn;

import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** Real isolated ProtoChunks and derived contracts; not a Minecraft server/GPU acceptance. */
public class PlanetTerrainTest {
    public static Registry<Biome> biomes;
    public static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);
    final PlanetTerrain terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);

    @BeforeAll
    public static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var r = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var biome =
                new Biome.BiomeBuilder()
                        .hasPrecipitation(false)
                        .temperature(.8f)
                        .downfall(0)
                        .specialEffects(
                                new BiomeSpecialEffects.Builder()
                                        .fogColor(0)
                                        .waterColor(0x3f76e4)
                                        .waterFogColor(0)
                                        .skyColor(0)
                                        .build())
                        .mobSpawnSettings(MobSpawnSettings.EMPTY)
                        .generationSettings(BiomeGenerationSettings.EMPTY)
                        .build();
        Registry.register(r, Biomes.PLAINS, biome);
        biomes = r.freeze();
    }

    PlanetChunkGenerator generator(long seed) {
        var settings =
                new FlatLevelGeneratorSettings(
                        Optional.of(HolderSet.direct()),
                        biomes.getHolderOrThrow(Biomes.PLAINS),
                        List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        return new PlanetChunkGenerator(settings, seed, PlanetTerrain.VERSION);
    }

    ProtoChunk chunk(PlanetChunkGenerator g, int x, int z) {
        var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, HEIGHT, biomes, null);
        assertSame(chunk, g.fillFromNoise(null, null, null, chunk).join());
        return chunk;
    }

    int[] values(ChunkAccess c) {
        var states = new int[GroundChunkSnapshot.VALUES];
        var p = c.getPos();
        for (int y = -64; y < 320; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    states[x + 16 * (z + 16 * (y + 64))] =
                            Block.BLOCK_STATE_REGISTRY.getId(
                                    c.getBlockState(
                                            new BlockPos(
                                                    p.getMinBlockX() + x,
                                                    y,
                                                    p.getMinBlockZ() + z)));
        return states;
    }

    @Test
    void seedIsReproducibleAndActuallyChangesMountainsAndOceans() {
        var same = new PlanetTerrain(terrain.seed());
        var other = new PlanetTerrain(terrain.seed() + 1);
        int min = 999, max = -999, different = 0;
        for (var f : Face.values())
            for (int u = 32; u < 2048; u += 64)
                for (int v = 32; v < 2048; v += 64) {
                    int y = terrain.groundY(f, u, v);
                    min = Math.min(min, y);
                    max = Math.max(max, y);
                    assertEquals(y, same.groundY(f, u, v));
                    if (y != other.groundY(f, u, v)) different++;
                    assertTrue(y >= 103 && y <= 175);
                }
        assertTrue(min < 118);
        assertTrue(max > 148);
        assertTrue(different > 5000);
    }

    @Test
    void allDirectedSeamsShareExactGlobalHeightsAndDirections() {
        int edges = 0;
        for (var f : Face.values())
            for (int[] edge : new int[][] {{0, -1}, {2048, -1}, {-1, 0}, {-1, 2048}}) {
                for (int t = 0; t <= 2048; t += 16) {
                    double u = edge[0] < 0 ? t : edge[0], v = edge[1] < 0 ? t : edge[1];
                    var cube =
                            f.normal.scale(1024).add(f.u.scale(u - 1024)).add(f.v.scale(v - 1024));
                    int neighbors = 0;
                    for (var g : Face.values())
                        if (g != f && Math.abs(cube.dot(g.normal) - 1024) < 1e-8) {
                            double a = 1024 + cube.dot(g.u), b = 1024 + cube.dot(g.v);
                            assertTrue(
                                    PlanetTerrain.direction(f, u, v)
                                                    .sub(PlanetTerrain.direction(g, a, b))
                                                    .length()
                                            < 1e-12);
                            assertEquals(
                                    terrain.elevation(f, u, v), terrain.elevation(g, a, b), 1e-10);
                            neighbors++;
                        }
                    assertTrue(neighbors > 0);
                }
                edges++;
            }
        assertEquals(24, edges);
    }

    @Test
    void allEightCornersHaveOneHeightNotSixIndependentNoises() {
        for (int a : new int[] {-1, 1})
            for (int b : new int[] {-1, 1})
                for (int c : new int[] {-1, 1}) {
                    var cube = new Point(a * 1024, b * 1024, c * 1024);
                    Double h = null;
                    int faces = 0;
                    for (var f : Face.values())
                        if (cube.dot(f.normal) == 1024) {
                            double value =
                                    terrain.elevation(
                                            f, 1024 + cube.dot(f.u), 1024 + cube.dot(f.v));
                            if (h != null) assertEquals(h, value, 1e-10);
                            h = value;
                            faces++;
                        }
                    assertEquals(3, faces);
                }
    }

    @Test
    void neighborVoxelCentresRemainAdjacentAcrossEverySeam() {
        for (var f : Face.values())
            for (int t = 16; t < 2048; t += 16)
                for (int[] p : new int[][] {{-1, t}, {2048, t}, {t, -1}, {t, 2048}}) {
                    var owner =
                            GroundTopology.address(
                                            new BlockPos(f.ordinal() * 4096 + p[0], 128, p[1]))
                                    .owner();
                    var cell = GroundTopology.cell(owner);
                    int u = Math.clamp(p[0], 0, 2047), v = Math.clamp(p[1], 0, 2047);
                    assertTrue(
                            Math.abs(
                                            terrain.groundY(f, u, v)
                                                    - terrain.groundY(
                                                            cell.face(), cell.u(), cell.v()))
                                    <= 3,
                            "Height jump at " + f + " " + t);
                }
    }

    @Test
    void actualSixFaceProtoChunksMatchBaseColumnsAndBothHeightmaps() {
        var g = generator(terrain.seed());
        for (var f : Face.values()) {
            var c = chunk(g, f.ordinal() * 256 + 64, 64);
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    int wx = c.getPos().getMinBlockX() + x, wz = c.getPos().getMinBlockZ() + z;
                    var column = g.getBaseColumn(wx, wz, HEIGHT, null);
                    int ground = terrain.groundY(f, 1024 + x, 1024 + z);
                    for (int y = -64; y < 320; y++)
                        assertEquals(column.getBlock(y), c.getBlockState(new BlockPos(wx, y, wz)));
                    assertEquals(
                            Math.max(ground, 127),
                            c.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
                    assertEquals(ground, c.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z));
                    assertEquals(
                            Math.max(ground, 127) + 1,
                            g.getBaseHeight(
                                    wx, wz, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, null));
                    assertEquals(
                            ground + 1,
                            g.getBaseHeight(wx, wz, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, null));
                }
        }
    }

    @Test
    void waterIsRealSourceWaterAboveSandAndNotAColoredSurface() {
        int ground = 100;
        assertTrue(terrain.state(ground, 100).is(Blocks.SAND));
        assertTrue(terrain.state(ground, 101).getFluidState().isSource());
        assertTrue(terrain.state(ground, 127).is(Blocks.WATER));
        assertTrue(terrain.state(ground, 128).isAir());
        assertTrue(terrain.state(ground, -64).is(Blocks.BEDROCK));
        assertTrue(terrain.state(ground, -65).isAir());
    }

    @Test
    void storageGapsRemainEmptyAndCannotBecomeAnotherPlanet() {
        var g = generator(terrain.seed());
        for (int x : new int[] {-1, 128, 255, 1408, 1536}) {
            var c = chunk(g, x, 64);
            assertTrue(Arrays.stream(c.getSections()).allMatch(s -> s.hasOnlyAir()));
            assertEquals(
                    -64,
                    g.getBaseHeight(x * 16, 1024, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, null));
            assertTrue(g.getBaseColumn(x * 16, 1024, HEIGHT, null).getBlock(0).isAir());
        }
    }

    @Test
    void generationIsDeterministicAndLaterSurfaceStageDoesNotRepaintEdits() {
        var g = generator(terrain.seed());
        var a = chunk(g, 64, 64);
        var b = chunk(g, 64, 64);
        assertArrayEquals(values(a), values(b));
        var pos = new BlockPos(1024, terrain.groundY(Face.TOP, 1024, 1024) + 1, 1024);
        a.setBlockState(pos, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        g.buildSurface(null, null, null, a);
        assertTrue(a.getBlockState(pos).is(Blocks.GOLD_BLOCK));
    }

    @Test
    void exactTerrainBaselineClearsUnknownButARealBuildingSurvivesIndexReload() {
        var g = generator(terrain.seed());
        var c = chunk(g, 64, 64);
        var states = values(c);
        var image = GroundChunkSnapshot.fromStates(0, 64, 64, 4, states);
        assertTrue(terrain.matches(image));
        assertFalse(image.baseline());
        var index = new GroundSurfaceIndex();
        var pos = new BlockPos(1024, 250, 1024);
        index.mark(pos);
        index.update(image, terrain);
        assertFalse(index.coverage().get((int) image.key()));
        states[16 * 16 * (250 + 64)] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        image = GroundChunkSnapshot.fromStates(0, 64, 64, 5, states);
        index.mark(pos);
        index.update(image, terrain);
        assertFalse(terrain.matches(image));
        assertTrue(index.coverage().get((int) image.key()));
        assertEquals(250, index.surface(image.key()).height(0, 0));
        var loaded =
                GroundSurfaceIndex.load(
                        index.save(new net.minecraft.nbt.CompoundTag(), null), null);
        assertTrue(loaded.dirty(image.key()));
        assertTrue(loaded.surface(image.key()).state(0, 0).is(Blocks.GOLD_BLOCK));
        assertTrue(
                new PlanetTerrain(terrain.seed())
                        .matches(GroundChunkSnapshot.fromStates(0, 64, 64, 6, values(c))));
    }

    @Test
    void generatorCodecRoundTripPreservesSeedAndRefusesUnknownVersion() {
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var features =
                new MappedRegistry<net.minecraft.world.level.levelgen.placement.PlacedFeature>(
                        Registries.PLACED_FEATURE, Lifecycle.stable());
        for (var key :
                List.of(
                        net.minecraft.data.worldgen.placement.MiscOverworldPlacements
                                .LAKE_LAVA_SURFACE,
                        net.minecraft.data.worldgen.placement.MiscOverworldPlacements
                                .LAKE_LAVA_UNDERGROUND))
            Registry.register(
                    features,
                    key,
                    lookup.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(key).value());
        var access =
                new RegistryAccess.ImmutableRegistryAccess(List.of(biomes, features.freeze()))
                        .freeze();
        var g = generator(Long.MIN_VALUE + 17);
        var ops = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, access);
        var json = PlanetChunkGenerator.CODEC.codec().encodeStart(ops, g).getOrThrow();
        var copy = PlanetChunkGenerator.CODEC.codec().parse(ops, json).getOrThrow();
        assertEquals(g.seed(), copy.seed());
        assertEquals(
                g.terrain().groundY(Face.EAST, 20, 30), copy.terrain().groundY(Face.EAST, 20, 30));
        assertThrows(
                IllegalArgumentException.class, () -> new PlanetChunkGenerator(g.settings(), 1, 4));
    }

    @Test
    void profilePacketPreservesSignedSeedAndRejectsUnknownOrEmptySessions() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new net.minecraft.network.RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            var packet = new PlanetPackets.Profile(7, Long.MIN_VALUE, 2);
            PlanetPackets.Profile.CODEC.encode(b, packet);
            assertEquals(packet, PlanetPackets.Profile.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
        } finally {
            raw.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new PlanetPackets.Profile(0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlanetPackets.Profile(1, 1, 99));
    }

    @Test
    void loadedEntryHeightUsesActualTerrainAndRetainsBuildings() {
        var g = generator(terrain.seed());
        for (var f : Face.values()) {
            var p = PlanetSpawn.dryLand(terrain, f);
            int x = f.ordinal() * 4096 + p[0], z = p[1];
            var c = chunk(g, x >> 4, z >> 4);
            Heightmap.primeHeightmaps(c, Set.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
            int y = PlanetSpawn.entryY(c, x, z);
            assertEquals(terrain.groundY(f, p[0], p[1]) + 2, y);
            assertTrue(c.getBlockState(new BlockPos(x, y, z)).isAir());
            assertTrue(c.getBlockState(new BlockPos(x, y + 1, z)).isAir());
            var tower = new BlockPos(x, 280, z);
            c.setBlockState(tower, Blocks.GOLD_BLOCK.defaultBlockState(), false);
            Heightmap.primeHeightmaps(c, Set.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
            assertEquals(282, PlanetSpawn.entryY(c, x, z));
            assertTrue(c.getBlockState(tower).is(Blocks.GOLD_BLOCK));
            assertThrows(IllegalArgumentException.class, () -> PlanetSpawn.entryY(c, x + 16, z));
        }
    }

    @Test
    void coldEntryRequestsFullChunkBeforeReadingHeightAndKeepsSafetyGuard() throws Exception {
        var s = Sources.text("PlanetSpawn");
        int load = s.indexOf("level.getChunk(x>>4,z>>4)");
        assertTrue(load >= 0);
        assertTrue(s.indexOf("entryY(chunk,x,z)") > load);
        assertFalse(s.contains("level.getHeight("));
        assertTrue(
                s.contains("!level.getBlockState(new net.minecraft.core.BlockPos(x,y,z)).isAir()"));
        assertFalse(s.contains("setBlock("));
    }

    @Test
    void freshFaceEntriesPreferDryLandWithinUnmaskedPlotInterior() {
        for (var f : Face.values()) {
            int[] p = PlanetSpawn.dryLand(terrain, f);
            assertTrue(p[0] >= 256 && p[0] <= 1792 && p[1] >= 256 && p[1] <= 1792);
            assertTrue(terrain.groundY(f, p[0], p[1]) >= 129);
        }
    }
}
