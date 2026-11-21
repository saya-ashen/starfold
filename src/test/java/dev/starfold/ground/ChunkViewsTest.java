package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Server seam topology at the chunk-lookup layer: halo chunks are views of canonical owners.
 * Pure address maths plus wiring checks; in-game behaviour is verified with the scripted dev client. */
class ChunkViewsTest {
    @BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    @Test
    void cachedColumnsFollowLayoutChangesAcrossWorlds() {
        try {
            for (int size : new int[] {2048, 512, 2048}) {
                dev.starfold.chart.PlanetShape.system(
                        new int[] {size, size, size, size}, new int[] {191, 191, 191, 191});
                for (int planet = 0; planet < 4; planet++)
                    for (var face : Face.values())
                        for (int[] chunk :
                                new int[][] {{-1, 16}, {16, -1}, {-1, -1}, {size / 16, 16}}) {
                            int cx =
                                    (PlanetStorage.offset(planet)
                                                            + face.ordinal()
                                                                    * GroundCoordinates.STRIDE)
                                                    / 16
                                            + chunk[0];
                            int cz = PlanetStorage.offsetZ(planet) / 16 + chunk[1];
                            var columns = ChunkViews.columns(PlanetAccess.SHARED, cx, cz);
                            for (int i = 0; i < 256; i++) {
                                var query =
                                        new BlockPos(
                                                cx * 16 + (i & 15),
                                                GroundCoordinates.FLOOR,
                                                cz * 16 + (i >> 4));
                                try {
                                    var address = PlanetAccess.SHARED.address(query);
                                    assertEquals(address.owner().getX(), columns.x()[i]);
                                    assertEquals(address.owner().getZ(), columns.z()[i]);
                                    assertEquals(address.turns(), columns.turns()[i]);
                                } catch (IllegalArgumentException unowned) {
                                    assertFalse(columns.present(i));
                                }
                            }
                        }
            }
        } finally {
            dev.starfold.chart.PlanetShape.reset();
            ChunkViews.resetLayout();
        }
    }

    @Test
    void releasingWorldRemovesItsViewEntry() throws Exception {
        var field = ChunkViews.class.getDeclaredField("VIEWS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var views = (Map<net.minecraft.server.level.ServerLevel, Object>) field.get(null);
        views.put(null, new java.util.concurrent.ConcurrentHashMap<>());
        ChunkViews.release(null);
        assertFalse(views.containsKey(null));
    }

    private static final PlanetAccess[] LAYOUTS = new PlanetAccess[] {PlanetAccess.SHARED};

    private static int origin(PlanetAccess access, int planet, Face face) {
        return PlanetStorage.offset(planet) + face.ordinal() * GroundCoordinates.STRIDE;
    }

    @Test
    void chartTestMirrorsTheLayoutWithoutThrowing() {
        var random = new Random(7);
        for (var access : LAYOUTS)
            for (int n = 0; n < 20000; n++) {
                int x = random.nextInt(-40000, 110000), z = random.nextInt(-5000, 7000);
                assertEquals(
                        access.inChart(new BlockPos(x, 64, z)),
                        ChunkViews.inChart(access, x, z),
                        access + " " + x + "," + z);
            }
    }

    @Test
    void ownedChunksAreNeverGhostsAndHaloChunksAlways() {
        for (var access : LAYOUTS)
            for (int planet = 0; planet < 2; planet++)
                for (var face : Face.values()) {
                    int x0 = origin(access, planet, face) >> 4;
                    assertFalse(ChunkViews.ghost(access, x0, 0));
                    assertFalse(ChunkViews.ghost(access, x0 + 127, 127));
                    assertTrue(ChunkViews.ghost(access, x0 - 1, 64));
                    assertTrue(ChunkViews.ghost(access, x0 + 128, 64));
                    assertTrue(ChunkViews.ghost(access, x0 + 64, -1));
                    assertTrue(ChunkViews.ghost(access, x0 + 64, 128));
                }
    }

    /** Every column of a seam-adjacent halo chunk reads exactly the block Level reads route to. */
    @Test
    void ghostColumnsAreTheLevelAddressOfEveryHaloBlock() {
        for (var access : LAYOUTS)
            for (var face : Face.values()) {
                int x0 = origin(access, 1, face);
                for (int[] c :
                        new int[][] {
                            {-16, 1024},
                            {2048, 1024},
                            {1024, -16},
                            {1024, 2048},
                            {-32, 512},
                            {2064, 1536}
                        }) {
                    int cx = (x0 + c[0]) >> 4, cz = c[1] >> 4;
                    var columns = ChunkViews.columns(access, cx, cz);
                    for (int i = 0; i < 256; i++) {
                        var query =
                                new BlockPos(
                                        cx * 16 + (i & 15),
                                        GroundCoordinates.FLOOR,
                                        cz * 16 + (i >> 4));
                        var address = access.address(query);
                        assertEquals(address.owner().getX(), columns.x()[i]);
                        assertEquals(address.owner().getZ(), columns.z()[i]);
                        assertEquals(address.turns(), columns.turns()[i]);
                        long alias = ChunkViews.alias(access, query.atY(37).asLong());
                        assertEquals(address.owner().atY(37).asLong(), alias);
                    }
                    // Away from corners a halo chunk is one owner chunk turned as a whole.
                    assertEquals(1, columns.owners().length, face + " " + Arrays.toString(c));
                    long section = ChunkViews.aliasSection(access, SectionPos.asLong(cx, 3, cz));
                    assertEquals(
                            ChunkPos.asLong(columns.x()[0] >> 4, columns.z()[0] >> 4),
                            ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section)));
                    assertEquals(3, SectionPos.y(section));
                }
            }
    }

    /** BIOMES/load=false comes from precipitation. getChunk(...,FULL,false) still waits on
     * ticketed generation in vanilla; a non-loading ghost lookup must use the get-now path. */
    @Test
    void nonLoadingGhostLookupNeverWaitsForFullGeneration() {
        var columns = ChunkViews.columns(PlanetAccess.SHARED, -1, 64);
        assertEquals(1, columns.owners().length);
        var pending = new java.util.concurrent.CompletableFuture<Boolean>();
        var queried = new ArrayList<Long>();
        java.util.function.LongPredicate loading =
                owner -> {
                    fail("non-loading lookup scheduled or awaited generation");
                    return false;
                };
        java.util.function.LongPredicate current =
                owner -> {
                    queried.add(owner);
                    return pending.getNow(false);
                };
        assertFalse(ChunkViews.ready(columns, false, loading, current));
        assertFalse(pending.isDone(), "lookup must leave ticketed generation alone");
        assertEquals(List.of(columns.owners()[0]), queried);
        pending.complete(true);
        queried.clear();
        assertTrue(ChunkViews.ready(columns, false, loading, current));
        assertEquals(List.of(columns.owners()[0]), queried);
    }

    @Test
    void ghostReadinessRequiresEveryOwnerAndLoadingStillLoads() {
        var columns =
                new ChunkViews.Columns(
                        new int[0],
                        new int[0],
                        new int[0],
                        new int[0],
                        new byte[0],
                        new long[] {11, 22});
        assertFalse(
                ChunkViews.ready(
                        columns,
                        false,
                        o -> {
                            fail("unexpected load");
                            return true;
                        },
                        o -> o == 11));
        assertTrue(
                ChunkViews.ready(
                        columns,
                        false,
                        o -> {
                            fail("unexpected load");
                            return true;
                        },
                        o -> true));
        var loaded = new ArrayList<Long>();
        assertTrue(
                ChunkViews.ready(
                        columns,
                        true,
                        o -> {
                            loaded.add(o);
                            return true;
                        },
                        o -> {
                            fail("loading lookup used get-now");
                            return false;
                        }));
        assertEquals(List.of(11L, 22L), loaded);
        var empty =
                new ChunkViews.Columns(
                        new int[0], new int[0], new int[0], new int[0], new byte[0], new long[0]);
        assertFalse(
                ChunkViews.ready(
                        empty,
                        false,
                        o -> {
                            fail("empty load");
                            return true;
                        },
                        o -> {
                            fail("empty read");
                            return true;
                        }));
    }

    @Test
    void ownedPositionsAreNotAliased() {
        for (var access : LAYOUTS) {
            long p = new BlockPos(origin(access, 0, Face.EAST) + 5, 70, 2000).asLong();
            assertEquals(p, ChunkViews.alias(access, p));
            long s = SectionPos.asLong(origin(access, 0, Face.EAST) >> 4, 4, 3);
            assertEquals(s, ChunkViews.aliasSection(access, s));
        }
    }

    /** Walking across a seam and back in the halo is a bijection on the owner side (no duplicated owners). */
    @Test
    void haloStripIsInjectiveAwayFromCorners() {
        for (var face : Face.values()) {
            var seen = new HashMap<Long, BlockPos>();
            int x0 = origin(PlanetAccess.SHARED, 0, face);
            for (int u = -48; u < 0; u++)
                for (int v = 64; v < 1984; v++) {
                    var q = new BlockPos(x0 + u, 70, v);
                    long a = ChunkViews.alias(PlanetAccess.SHARED, q.asLong());
                    var prev = seen.put(a, q);
                    assertNull(prev, face + " " + q + " " + prev);
                }
        }
    }

    @Test
    void serverChunkLookupsLightAndMobsAreWired() throws Exception {
        var mixins = Files.readString(Path.of("src/main/resources/starfold.mixins.json"));
        for (var name :
                List.of(
                        "GroundChunkViewMixin",
                        "GroundLightSeamMixin",
                        "GroundBlockLightSeamMixin",
                        "GroundSkyLightSeamMixin",
                        "GroundPistonAccess",
                        "GroundNavigationAccess",
                        "GroundMobNavigationMixin",
                        "GroundMobLookMixin",
                        "GroundMobMeleeMixin",
                        "GroundMobSightMixin")) assertTrue(mixins.contains('"' + name + '"'), name);
        assertTrue(mixins.contains("\"GroundLightStorageAccess\""));
    }
}
