package dev.starfold.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.*;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetSpawn;
import dev.starfold.universe.PlanetWorldPreset;

import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

public class PlanetEcologyTest {
    public static Registry<Biome> palette;
    public static HolderLookup.Provider lookup;
    public static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);
    public final PlanetTerrain terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED, 4);

    @BeforeAll
    public static void boot() {
        PlanetTerrainTest.registry();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var r = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        for (var key :
                List.of(
                        Biomes.FOREST,
                        Biomes.PLAINS,
                        Biomes.DESERT,
                        Biomes.OCEAN,
                        Biomes.STONY_PEAKS,
                        Biomes.SNOWY_PLAINS))
            Registry.register(
                    r, key, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(key).value());
        palette = r.freeze();
        var base = lookup;
        lookup =
                HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                base.listRegistries()
                                        .filter(k -> !k.equals(Registries.BIOME))
                                        .map(
                                                k ->
                                                        (HolderLookup.RegistryLookup<?>)
                                                                base.lookupOrThrow(k)),
                                java.util.stream.Stream.of(palette.asLookup())));
    }

    public PlanetBiomes source(long seed) {
        return new PlanetBiomes(
                seed,
                palette.getHolderOrThrow(Biomes.FOREST),
                palette.getHolderOrThrow(Biomes.PLAINS),
                palette.getHolderOrThrow(Biomes.DESERT),
                palette.getHolderOrThrow(Biomes.OCEAN));
    }

    public PlanetChunkGenerator generator(int version) {
        var settings =
                new FlatLevelGeneratorSettings(
                        Optional.of(HolderSet.direct()),
                        palette.getHolderOrThrow(Biomes.PLAINS),
                        List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        return new PlanetChunkGenerator(
                settings,
                terrain.seed(),
                version,
                version == 4 ? Optional.of(source(terrain.seed())) : Optional.empty());
    }

    ProtoChunk chunk(PlanetChunkGenerator g, int x, int z) {
        var c = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, HEIGHT, palette, null);
        g.createBiomes(null, null, null, c).join();
        c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        g.fillFromNoise(null, null, null, c).join();
        return c;
    }

    public int[] values(ChunkAccess c) {
        var out = new int[GroundChunkSnapshot.VALUES];
        for (int y = -64; y < 320; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    out[x + 16 * (z + 16 * (y + 64))] =
                            Block.BLOCK_STATE_REGISTRY.getId(
                                    c.getBlockState(
                                            new BlockPos(
                                                    c.getPos().getMinBlockX() + x,
                                                    y,
                                                    c.getPos().getMinBlockZ() + z)));
        return out;
    }

    Cell site(PlanetEcology.Region region) {
        for (var f : Face.values())
            for (int u = 64; u < 1984; u += 32)
                for (int v = 64; v < 1984; v += 32)
                    if (terrain.groundY(f, u, v) >= 130
                            && terrain.groundY(f, u, v) <= 150
                            && terrain.ecology().region(f, u, v) == region)
                        return new Cell(f, u, 0, v);
        throw new AssertionError("Missing dry " + region);
    }

    PlanetEcology.Tree tree(boolean boundary) {
        for (var f : Face.values())
            for (int u = 16; u < 2032; u++)
                for (int v = 16; v < 2032; v += 7) {
                    if (boundary && u % 16 > 1 && u % 16 < 14 && v % 16 > 1 && v % 16 < 14)
                        continue;
                    var t = terrain.ecology().tree(f, u, v);
                    if (t != null) return t;
                }
        throw new AssertionError("No tree");
    }

    @Test
    void allThreeLandRegionsAndOceanAreRepresented() throws Exception {
        var counts = new EnumMap<PlanetEcology.Region, Integer>(PlanetEcology.Region.class);
        int ocean = 0;
        for (var f : Face.values())
            for (int u = 32; u < 2048; u += 64)
                for (int v = 32; v < 2048; v += 64) {
                    if (terrain.groundY(f, u, v) < 128) ocean++;
                    else counts.merge(terrain.ecology().region(f, u, v), 1, Integer::sum);
                }
        assertEquals(3, counts.size());
        assertTrue(ocean > 0);
        for (var region : PlanetEcology.Region.values()) assertNotNull(site(region));
        var t = tree(true);
        var out = Path.of("build/native-ecology");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve("reproduction.json"),
                "{\"gpu_verified\":false,\"old_version\":3,\"new_version\":4,\"land_regions\":3,\"ocean_samples\":"
                        + ocean
                        + ",\"region_counts\":"
                        + new com.google.gson.Gson().toJson(counts)
                        + ",\"tree\":{\"face\":"
                        + t.root().face().ordinal()
                        + ",\"u\":"
                        + t.root().u()
                        + ",\"v\":"
                        + t.root().v()
                        + ",\"ground\":"
                        + t.ground()
                        + ",\"height\":"
                        + t.height()
                        + "}}\n");
    }

    @Test
    void undergroundAndTopographyRemainTheAcceptedV3() {
        var old = new PlanetTerrain(terrain.seed(), 3);
        for (var region : PlanetEcology.Region.values()) {
            var c = site(region);
            assertEquals(
                    old.groundY(c.face(), c.u(), c.v()), terrain.groundY(c.face(), c.u(), c.v()));
            for (int y = -64; y < terrain.groundY(c.face(), c.u(), c.v()) - 3; y++)
                assertSame(
                        old.state(c.face(), c.u(), y, c.v()),
                        terrain.state(c.face(), c.u(), y, c.v()));
        }
    }

    @Test
    void desertHasRealSandAndSandstoneNotMerelyAColour() {
        var c = site(PlanetEcology.Region.DESERT);
        int y = terrain.groundY(c.face(), c.u(), c.v());
        assertTrue(terrain.state(c.face(), c.u(), y, c.v()).is(Blocks.SAND));
        assertTrue(terrain.state(c.face(), c.u(), y - 2, c.v()).is(Blocks.SANDSTONE));
    }

    @Test
    void treesAreRealLogsWithNonPersistentDistanceBoundLeaves() {
        var t = tree(false);
        var c = t.root();
        for (int y = t.ground() + 1; y <= t.ground() + t.height(); y++)
            assertTrue(terrain.state(c.face(), c.u(), y, c.v()).is(Blocks.OAK_LOG));
        var leaf = terrain.state(c.face(), c.u(), t.ground() + t.height() + 1, c.v());
        assertTrue(leaf.is(Blocks.OAK_LEAVES));
        assertFalse(leaf.getValue(LeavesBlock.PERSISTENT));
        assertEquals(1, leaf.getValue(LeavesBlock.DISTANCE));
        assertTrue(terrain.state(c.face(), c.u(), t.ground(), c.v()).is(Blocks.DIRT));
    }

    @Test
    void treesHaveCompleteCrownsAcrossChunkBoundariesRegardlessOfGenerationOrder() {
        var t = tree(true);
        var c = t.root();
        int x = c.face().ordinal() * 4096 + c.u(), z = c.v();
        var g = generator(4);
        var first = chunk(g, x >> 4, z >> 4);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    var other = chunk(g, (x + dx) >> 4, (z + dz) >> 4);
                    var pos = new BlockPos(x + dx, t.ground() + t.height() + 1, z + dz);
                    assertTrue(other.getBlockState(pos).is(Blocks.OAK_LEAVES));
                }
        assertArrayEquals(values(first), values(chunk(generator(4), x >> 4, z >> 4)));
    }

    @Test
    void grassAndFlowersAreActualSupportedPlants() {
        var c = site(PlanetEcology.Region.PLAINS);
        var found = new HashSet<Block>();
        for (int x = 0; x < 64; x++)
            for (int z = 0; z < 64; z++) {
                int u = c.u() + x, v = c.v() + z;
                int y = terrain.groundY(c.face(), u, v);
                var state = terrain.state(c.face(), u, y + 1, v);
                if (state.is(Blocks.SHORT_GRASS)
                        || state.is(Blocks.DANDELION)
                        || state.is(Blocks.POPPY)) {
                    assertTrue(terrain.state(c.face(), u, y, v).is(Blocks.GRASS_BLOCK));
                    found.add(state.getBlock());
                }
            }
        assertTrue(found.contains(Blocks.SHORT_GRASS));
        assertTrue(found.contains(Blocks.DANDELION) || found.contains(Blocks.POPPY));
    }

    @Test
    void climateHasNoFaceDiscontinuityAtSharedEdgesAndCorners() {
        var eco = terrain.ecology();
        for (var f : Face.values())
            for (int[] edge :
                    new int[][] {
                        {0, 1024}, {2048, 1024}, {1024, 0}, {1024, 2048}, {0, 0}, {2048, 2048}
                    }) {
                var p =
                        f.normal
                                .scale(1024)
                                .add(f.u.scale(edge[0] - 1024))
                                .add(f.v.scale(edge[1] - 1024));
                for (var g : Face.values())
                    if (g != f && p.dot(g.normal) == 1024)
                        assertEquals(
                                eco.region(PlanetTerrain.direction(f, edge[0], edge[1])),
                                eco.region(
                                        PlanetTerrain.direction(
                                                g, 1024 + p.dot(g.u), 1024 + p.dot(g.v))));
            }
    }

    @Test
    void canonicalHaloHasIdenticalPlantsAndTerrain() {
        var topology = new CubeSurfaceTopology(2048);
        for (var f : Face.values())
            for (int[] p : new int[][] {{-1, 1024}, {2048, 1024}, {1024, -1}, {1024, 2048}}) {
                var c = topology.canonical(new Cell(f, p[0], 0, p[1])).cell();
                for (int y = 120; y <= 180; y++)
                    assertSame(
                            terrain.state(c.face(), c.u(), y, c.v()),
                            terrain.state(f, p[0], y, p[1]));
            }
    }

    @Test
    void realChunkBiomeContainersContainTheSelectedVanillaBiomes() {
        var g = generator(4);
        assertEquals(4, g.getBiomeSource().possibleBiomes().size());
        for (var region : PlanetEcology.Region.values()) {
            var c = site(region);
            var chunk = chunk(g, c.face().ordinal() * 256 + (c.u() >> 4), c.v() >> 4);
            int x = (chunk.getPos().getMinBlockX() + 2) >> 2,
                    z = (chunk.getPos().getMinBlockZ() + 2) >> 2;
            assertEquals(
                    g.getBiomeSource().getNoiseBiome(x, 0, z, null), chunk.getNoiseBiome(x, 0, z));
        }
    }

    @Test
    void noiseColumnsAndAllRelevantHeightQueriesIncludeCanopies() {
        var t = tree(false);
        var c = t.root();
        int x = c.face().ordinal() * 4096 + c.u(), z = c.v();
        var g = generator(4);
        var chunk = chunk(g, x >> 4, z >> 4);
        var col = g.getBaseColumn(x, z, HEIGHT, null);
        for (int y = -64; y < 320; y++)
            assertSame(col.getBlock(y), chunk.getBlockState(new BlockPos(x, y, z)));
        assertEquals(
                t.ground() + t.height() + 2,
                g.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, null));
        assertEquals(
                chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15) + 1,
                g.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, null));
    }

    @Test
    void ecologySpawnUsesCanopyHeightAndKeepsUnobstructedHeadroom() {
        var t = tree(false);
        var c = t.root();
        int x = c.face().ordinal() * 4096 + c.u(), z = c.v();
        var chunk = chunk(generator(4), x >> 4, z >> 4);
        Heightmap.primeHeightmaps(
                chunk,
                Set.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.WORLD_SURFACE));
        int y = PlanetSpawn.ecologyEntryY(chunk, x, z);
        assertTrue(chunk.getBlockState(new BlockPos(x, y, z)).isAir());
        assertTrue(chunk.getBlockState(new BlockPos(x, y + 1, z)).isAir());
    }

    @Test
    void naturalCanopyBaselineIsNotMisclassifiedAsABuilding() {
        var t = tree(false);
        var c = t.root();
        var g = generator(4);
        var chunk = chunk(g, c.face().ordinal() * 256 + (c.u() >> 4), c.v() >> 4);
        var states = values(chunk);
        assertTrue(
                g.terrain()
                        .matches(
                                GroundChunkSnapshot.fromStates(
                                        c.face().ordinal(), c.u() >> 4, c.v() >> 4, 1, states)));
        states[16 * 16 * (250 + 64)] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        assertFalse(
                g.terrain()
                        .matches(
                                GroundChunkSnapshot.fromStates(
                                        c.face().ordinal(), c.u() >> 4, c.v() >> 4, 2, states)));
    }

    @Test
    void laterGenerationDoesNotRestoreAChoppedLogOrOverwriteAChest() {
        var t = tree(false);
        var c = t.root();
        int x = c.face().ordinal() * 4096 + c.u(), z = c.v();
        var g = generator(4);
        var chunk = chunk(g, x >> 4, z >> 4);
        var pos = new BlockPos(x, t.ground() + 1, z);
        chunk.setBlockState(pos, Blocks.CHEST.defaultBlockState(), false);
        g.buildSurface(null, null, null, chunk);
        assertTrue(chunk.getBlockState(pos).is(Blocks.CHEST));
    }

    @Test
    void ecologyProfileAndGeneratorCodecPinVersionAndBiomePalette() throws Exception {
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        var g = generator(4);
        var json = PlanetChunkGenerator.CODEC.codec().encodeStart(ops, g).getOrThrow();
        var copy = PlanetChunkGenerator.CODEC.codec().parse(ops, json).getOrThrow();
        assertEquals(4, copy.version());
        assertEquals(4, copy.getBiomeSource().possibleBiomes().size());
        var t = tree(false);
        assertSame(
                g.terrain().state(t.root().face(), t.root().u(), t.ground() + 1, t.root().v()),
                copy.terrain().state(t.root().face(), t.root().u(), t.ground() + 1, t.root().v()));
        var profile = new PlanetPackets.Profile(7, terrain.seed(), 4);
        assertEquals(4, profile.version());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanetChunkGenerator(g.settings(), terrain.seed(), 4));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlanetChunkGenerator(
                                g.settings(),
                                terrain.seed(),
                                4,
                                Optional.of(source(terrain.seed() + 1))));
    }

    @Test
    void ecologyPresetUsesTheSavedPolicyInsideASystem() throws Exception {
        var root = Path.of("src/main/resources/data/starfold");
        for (String removed :
                List.of(
                        "dimension/native_planet.json",
                        "worldgen/world_preset/native_mining.json",
                        "worldgen/world_preset/native_ecology.json"))
            assertFalse(Files.exists(root.resolve(removed)));
        var gen =
                com.google.gson.JsonParser.parseString(
                                Files.readString(
                                        root.resolve("worldgen/world_preset/native_system.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("dimensions")
                        .getAsJsonObject("starfold:native_planet")
                        .getAsJsonObject("generator");
        var system =
                dev.starfold.universe.SystemChunkGenerator.CODEC
                        .codec()
                        .parse(RegistryOps.create(JsonOps.INSTANCE, lookup), gen)
                        .getOrThrow();
        assertEquals(4, system.planet(0).version());
        assertEquals(20261001L, system.planet(0).seed());
        assertEquals(generator(4).terrain().generation(), system.planet(0).generation());
        assertEquals(2, generator(2).version());
        assertEquals(3, generator(3).version());
    }

    @Test
    void onlyAnExplicitSavedEcologyChoiceSurvivesDefaultRegistryBake() {
        var type =
                lookup.lookupOrThrow(Registries.DIMENSION_TYPE)
                        .getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        var key = ResourceKey.create(Registries.LEVEL_STEM, PlanetLevels.PLANET.location());
        var over = new LevelStem(type, generator(2));
        var old = new LevelStem(type, generator(2));
        var fresh =
                new LevelStem(
                        type,
                        new dev.starfold.universe.SystemChunkGenerator(
                                dev.starfold.planet.PlanetStorage.LAYOUT,
                                List.of(generator(4), generator(4))));
        var r = new MappedRegistry<LevelStem>(Registries.LEVEL_STEM, Lifecycle.stable());
        Registry.register(r, LevelStem.OVERWORLD, over);
        Registry.register(r, key, old);
        var original = r.freeze();
        var dims = new WorldDimensions(Map.of(LevelStem.OVERWORLD, over, key, fresh));
        var adjusted = PlanetWorldPreset.registryFor(dims, original);
        assertSame(fresh, dims.bake(adjusted).dimensions().get(key));
        assertSame(old, original.get(key));
        assertSame(
                original,
                PlanetWorldPreset.registryFor(
                        new WorldDimensions(Map.of(LevelStem.OVERWORLD, over, key, old)),
                        original));
    }

    @Test
    void generatedSurfacePreviewUsesNaturalCanopyAndDesertMaterials() {
        var t = tree(false);
        var c = t.root();
        assertEquals(t.ground() + t.height() + 1, terrain.surfaceY(c.face(), c.u(), c.v()));
        assertTrue(terrain.surfaceState(c.face(), c.u(), c.v()).is(Blocks.OAK_LEAVES));
        var cache = new PlanetHeightCache(terrain);
        assertEquals(
                terrain.surfaceY(c.face(), c.u(), c.v()), cache.ground(c.face(), c.u(), c.v()));
        var desert = site(PlanetEcology.Region.DESERT);
        assertTrue(
                terrain.surfaceState(desert.face(), desert.u(), desert.v()).is(Blocks.SAND)
                        || terrain.surfaceState(desert.face(), desert.u(), desert.v())
                                .is(Blocks.DEAD_BUSH));
    }

    @Test
    void oceansAndStorageGapsRemainWaterAndEmptyRespectively() {
        var g = generator(4);
        var gap = chunk(g, 128, 64);
        assertTrue(Arrays.stream(gap.getSections()).allMatch(s -> s.hasOnlyAir()));
        assertEquals(
                -64, g.getBaseHeight(2048, 1024, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, null));
        for (var f : Face.values())
            for (int u = 32; u < 2048; u += 256)
                for (int v = 32; v < 2048; v += 256) {
                    int y = terrain.groundY(f, u, v);
                    if (y < 127) {
                        var col = terrain.column(f, u, v);
                        assertTrue(col.state(127).is(Blocks.WATER));
                        assertTrue(col.state(-64).is(Blocks.BEDROCK));
                    }
                }
    }
}
