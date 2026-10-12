package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.PlanetGeneration;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

import org.junit.jupiter.api.*;

import java.util.*;

/** Storage/generation regression only. Does NOT stand in for a live A-to-B client test. */
public class SingleLevelStorageTest {
    final PlanetEcologyTest fixture = new PlanetEcologyTest();

    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    SystemChunkGenerator generator() {
        var a = fixture.generator(4);
        var settings =
                new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
                        Optional.of(net.minecraft.core.HolderSet.direct()),
                        PlanetEcologyTest.palette.getHolderOrThrow(
                                net.minecraft.world.level.biome.Biomes.STONY_PEAKS),
                        List.of());
        settings.getLayersInfo()
                .add(new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        var b =
                new PlanetChunkGenerator(
                        settings,
                        20261002L,
                        5,
                        Optional.empty(),
                        Optional.of(new PlanetGeneration("rocky", "rocky", 1, 1.2, .04, .09)));
        return new SystemChunkGenerator(PlanetStorage.LAYOUT, a, b);
    }

    public ProtoChunk chunk(SystemChunkGenerator g, int x, int z) {
        var c =
                new ProtoChunk(
                        new ChunkPos(x, z),
                        UpgradeData.EMPTY,
                        PlanetEcologyTest.HEIGHT,
                        PlanetEcologyTest.palette,
                        null);
        g.createBiomes(null, null, null, c).join();
        c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        assertSame(c, g.fillFromNoise(null, null, null, c).join());
        return c;
    }

    @Test
    void everyCanonicalChunkHasOneInvertiblePlanetQualifiedAddress() {
        var seen = new HashSet<ChunkPos>();
        for (int id = 0; id < 2; id++)
            for (var face : Face.values())
                for (int u = 0; u < 128; u++)
                    for (int v = 0; v < 128; v++) {
                        var owner = new PlanetStorage.Owner(id, new Cell(face, u * 16, 0, v * 16));
                        var pos = owner.block();
                        long key = PlanetStorage.key(pos.getX() >> 4, pos.getZ() >> 4);
                        assertEquals(owner.key(), key);
                        assertEquals(new ChunkPos(pos), PlanetStorage.chunk(key));
                        assertTrue(seen.add(new ChunkPos(pos)));
                        assertEquals(id, PlanetStorage.planet(pos));
                    }
        assertEquals(196608, seen.size());
    }

    @Test
    void legacyAAddressesAndSixFaceTopologyStayByteForByteEquivalent() {
        for (var face : Face.values())
            for (int u : new int[] {0, 1, 1024, 2047})
                for (int v : new int[] {0, 1, 1024, 2047}) {
                    var c = new Cell(face, u, -100, v);
                    assertEquals(GroundTopology.block(c), PlanetStorage.block(0, c));
                    var p = new PlanetShape.Pose(face, new Point(u + .5, 100, v + .5));
                    assertEquals(GroundTopology.feet(p), PlanetStorage.feet(0, p));
                    for (int id = 0; id < 2; id++)
                        assertEquals(p, PlanetStorage.body(id, PlanetStorage.feet(id, p)));
                }
    }

    @Test
    void everyHaloAndCornerRoutesWithinItsOwnPlanetWithSameRotation() {
        for (int id = 0; id < 2; id++)
            for (var f : Face.values())
                for (int u : new int[] {-1, 0, 1024, 2047, 2048})
                    for (int v : new int[] {-1, 0, 1024, 2047, 2048}) {
                        var local = new BlockPos(f.ordinal() * 4096 + u, 90, v);
                        var query = local.offset(PlanetStorage.offset(id), 0, 0);
                        var expected = GroundTopology.address(local);
                        var actual = PlanetStorage.address(query);
                        assertEquals(id, actual.owner().planet());
                        assertEquals(expected.turns(), actual.turns());
                        assertEquals(
                                expected.owner().offset(PlanetStorage.offset(id), 0, 0),
                                actual.owner().block());
                        assertEquals(
                                Blocks.OAK_STAIRS.defaultBlockState(),
                                actual.oriented()
                                        .write(
                                                actual.oriented()
                                                        .read(
                                                                Blocks.OAK_STAIRS
                                                                        .defaultBlockState())));
                    }
    }

    @Test
    void neighborCallbacksKeepTargetAndGhostSourceOnTheSamePlanet() {
        for (int id = 0; id < 2; id++)
            for (var f : Face.values()) {
                var target = new BlockPos(f.ordinal() * 4096 + 2048, 128, 1024);
                var source = target.west();
                var expected = GroundTopology.notification(target, source);
                int offset = PlanetStorage.offset(id);
                var actual =
                        PlanetStorage.notification(
                                target.offset(offset, 0, 0), source.offset(offset, 0, 0));
                assertEquals(expected.target().offset(offset, 0, 0), actual.target());
                assertEquals(expected.source().offset(offset, 0, 0), actual.source());
                assertEquals(expected.turns(), actual.turns());
            }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlanetStorage.notification(
                                new BlockPos(1024, 128, 1024), new BlockPos(33792, 128, 1024)));
    }

    @Test
    void gapsAndInvalidLayoutNeverAliasAPlanet() {
        for (int x : new int[] {-1, 128, 255, 1408, 1536, 1800, 2047, 2176, 3456, 4096})
            assertEquals(-1, PlanetStorage.key(x, 64));
        assertEquals(-1, PlanetStorage.key(64, -1));
        assertEquals(-1, PlanetStorage.key(2112, 128));
        assertThrows(IllegalArgumentException.class, () -> PlanetStorage.planet(28000, 100));
        assertThrows(IllegalArgumentException.class, () -> PlanetStorage.planet(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> PlanetStorage.chunk(-1));
        assertThrows(IllegalArgumentException.class, () -> PlanetStorage.chunk(196608));
        var g = generator();
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemChunkGenerator("unknown", g.planet(0), g.planet(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemChunkGenerator(PlanetStorage.LAYOUT, g.planet(1), g.planet(0)));
    }

    @Test
    void oneGeneratorWritesBothPlanetsIntoDifferentRealChunksAndMatchesAllHeightQueries() {
        var g = generator();
        for (int id = 0; id < 2; id++)
            for (var f : Face.values()) {
                var c = chunk(g, PlanetStorage.offset(id) / 16 + f.ordinal() * 256 + 64, 64);
                for (int x = 0; x < 16; x += 5)
                    for (int z = 0; z < 16; z += 5) {
                        int wx = c.getPos().getMinBlockX() + x, wz = c.getPos().getMinBlockZ() + z;
                        var column = g.getBaseColumn(wx, wz, PlanetEcologyTest.HEIGHT, null);
                        var expected =
                                g.planet(id)
                                        .getBaseColumn(
                                                wx - PlanetStorage.offset(id),
                                                wz,
                                                PlanetEcologyTest.HEIGHT,
                                                null);
                        for (int y = -64; y < 320; y++) {
                            assertSame(expected.getBlock(y), column.getBlock(y));
                            assertSame(
                                    column.getBlock(y), c.getBlockState(new BlockPos(wx, y, wz)));
                        }
                        for (var type :
                                List.of(
                                        Heightmap.Types.WORLD_SURFACE_WG,
                                        Heightmap.Types.OCEAN_FLOOR_WG))
                            assertEquals(
                                    c.getHeight(type, x, z) + 1,
                                    g.getBaseHeight(wx, wz, type, PlanetEcologyTest.HEIGHT, null));
                    }
            }
    }

    @Test
    void bReallyStoresRockBiomesInsteadOfReusingAsClimate() {
        var g = generator();
        var b = chunk(g, 2112, 64);
        var a = chunk(g, 64, 64);
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) {
                assertTrue(
                        b.getNoiseBiome(2112 * 4 + x, 32, 64 * 4 + z)
                                .is(net.minecraft.world.level.biome.Biomes.STONY_PEAKS));
                assertEquals(
                        g.planet(0)
                                .getBiomeSource()
                                .getNoiseBiome(64 * 4 + x, 32, 64 * 4 + z, null),
                        a.getNoiseBiome(64 * 4 + x, 32, 64 * 4 + z));
            }
    }

    @Test
    void persistedLayoutAndIndependentPoliciesRegenerateIdenticalChunks() {
        var ops = RegistryOps.create(JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var g = generator();
        var saved = SystemChunkGenerator.CODEC.codec().encodeStart(ops, g).getOrThrow();
        var copy = SystemChunkGenerator.CODEC.codec().parse(ops, saved).getOrThrow();
        assertEquals(
                PlanetStorage.LAYOUT, saved.getAsJsonObject().get("storage_layout").getAsString());
        for (int id = 0; id < 2; id++) {
            assertEquals(g.planet(id).seed(), copy.planet(id).seed());
            assertEquals(g.planet(id).generation(), copy.planet(id).generation());
            int x = PlanetStorage.offset(id) / 16 + 64;
            assertArrayEquals(fixture.values(chunk(g, x, 64)), fixture.values(chunk(copy, x, 64)));
        }
        var invalid = saved.deepCopy().getAsJsonObject();
        invalid.addProperty("storage_layout", "v99");
        assertThrows(
                RuntimeException.class,
                () -> SystemChunkGenerator.CODEC.codec().parse(ops, invalid).getOrThrow());
        invalid.remove("storage_layout");
        assertTrue(SystemChunkGenerator.CODEC.codec().parse(ops, invalid).error().isPresent());
    }

    @Test
    void bEditCannotOverwriteAAndSurfaceStageDoesNotPaintOverEither() {
        var g = generator();
        var a = chunk(g, 64, 64);
        var b = chunk(g, 2112, 64);
        var posA = new BlockPos(1024, 250, 1024);
        var posB = posA.offset(32768, 0, 0);
        a.setBlockState(posA, Blocks.CHEST.defaultBlockState(), false);
        b.setBlockState(posB, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        g.buildSurface(null, null, null, a);
        g.buildSurface(null, null, null, b);
        assertTrue(a.getBlockState(posA).is(Blocks.CHEST));
        assertTrue(b.getBlockState(posB).is(Blocks.GOLD_BLOCK));
        assertNotEquals(
                PlanetStorage.address(posA).owner().key(),
                PlanetStorage.address(posB).owner().key());
    }

    @Test
    void voidBetweenStorageOwnersIsActuallyEmpty() {
        var g = generator();
        for (int x : new int[] {-1, 128, 1536, 1800, 2176, 3456}) {
            var c = chunk(g, x, 64);
            assertTrue(Arrays.stream(c.getSections()).allMatch(LevelChunkSection::hasOnlyAir));
            assertEquals(
                    -64,
                    g.getBaseHeight(
                            x * 16,
                            1024,
                            Heightmap.Types.WORLD_SURFACE_WG,
                            PlanetEcologyTest.HEIGHT,
                            null));
            assertTrue(
                    g.getBaseColumn(x * 16, 1024, PlanetEcologyTest.HEIGHT, null)
                            .getBlock(0)
                            .isAir());
        }
    }
}
