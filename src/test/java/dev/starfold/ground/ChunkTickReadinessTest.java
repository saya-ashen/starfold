package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.worldgen.PlanetEcologyTest;

import org.junit.jupiter.api.*;

import java.util.*;

class ChunkTickReadinessTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void interiorDoesNotInspectNeighbours() {
        for (int width : new int[] {16, 32, 128})
            for (int u = 1; u < width - 1; u++)
                for (int v = 1; v < width - 1; v++)
                    assertTrue(
                            ChunkTickReadiness.ready(
                                    u + 128L * v,
                                    width,
                                    17,
                                    29,
                                    (x, z) -> {
                                        fail("interior lookup");
                                        return false;
                                    }));
        assertTrue(
                ChunkTickReadiness.ready(
                        -1,
                        128,
                        17,
                        29,
                        (x, z) -> {
                            fail("noncanonical lookup");
                            return false;
                        }));
    }

    @Test
    void everyEdgeAndCornerRequiresAllGhostNeighbours() {
        for (int width : new int[] {16, 32, 128})
            for (int u : new int[] {0, 1, width - 1})
                for (int v : new int[] {0, 1, width - 1}) {
                    var expected = new HashSet<String>();
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++)
                            if (u + dx < 0 || u + dx >= width || v + dz < 0 || v + dz >= width)
                                expected.add((200 + dx) + "," + (300 + dz));
                    var seen = new HashSet<String>();
                    long key = u + 128L * v;
                    assertTrue(
                            ChunkTickReadiness.ready(
                                    key,
                                    width,
                                    200,
                                    300,
                                    (x, z) -> {
                                        seen.add(x + "," + z);
                                        return true;
                                    }));
                    assertEquals(expected, seen);
                    for (String missing : expected)
                        assertFalse(
                                ChunkTickReadiness.ready(
                                        key,
                                        width,
                                        200,
                                        300,
                                        (x, z) -> !missing.equals(x + "," + z)));
                }
    }

    @Test
    void pendingOwnersReleaseWithoutAnyTicketOrPlayerChange() {
        var pending = new java.util.concurrent.CompletableFuture<Boolean>();
        ChunkTickReadiness.NeighbourReady lookup = (x, z) -> pending.getNow(false);
        assertFalse(ChunkTickReadiness.ready(128L * 60, 128, 256, 60, lookup));
        pending.complete(true);
        assertTrue(ChunkTickReadiness.ready(128L * 60, 128, 256, 60, lookup));
    }

    @Test
    void gateRequiresPublishedFullChunkNotLightingProtoWrapper() throws Exception {
        var source = Sources.text("ChunkTickReadiness");
        assertTrue(
                source.contains(
                        "owner->level.getChunkSource().getChunkNow(ChunkPos.getX(owner),ChunkPos.getZ(owner))!=null"));
        var pending = new java.util.concurrent.CompletableFuture<Boolean>();
        var columns =
                new ChunkViews.Columns(
                        new int[0],
                        new int[0],
                        new int[0],
                        new int[0],
                        new byte[0],
                        new long[] {1, 2});
        // Lighting may expose an ImposterProtoChunk while FULL publication is still pending.
        assertFalse(
                ChunkViews.ready(
                        columns,
                        false,
                        o -> {
                            fail("loading");
                            return true;
                        },
                        o -> o == 1 || pending.getNow(false)));
        pending.complete(true);
        assertTrue(
                ChunkViews.ready(
                        columns,
                        false,
                        o -> {
                            fail("loading");
                            return true;
                        },
                        o -> o == 1 || pending.getNow(false)));
    }

    @Test
    void observedTopHaloIncludesSameRowEastOwnerAtEveryDepth() {
        var access = PlanetAccess.SHARED;
        var columns = ChunkViews.columns(access, 128, 58);
        long missing = net.minecraft.world.level.ChunkPos.asLong(256, 58);
        assertTrue(java.util.Arrays.stream(columns.owners()).anyMatch(owner -> owner == missing));
        for (int y : new int[] {-477, -476, 127, 128, 300}) {
            var owner = access.address(new net.minecraft.core.BlockPos(2048, y, 929)).owner();
            assertEquals(new net.minecraft.core.BlockPos(4096, y, 929), owner);
        }
        var pending = new java.util.concurrent.CompletableFuture<Boolean>();
        ChunkTickReadiness.NeighbourReady readable =
                (x, z) ->
                        ChunkViews.ready(
                                ChunkViews.columns(access, x, z),
                                false,
                                o -> {
                                    fail("loading");
                                    return true;
                                },
                                o -> o != missing || pending.getNow(false));
        assertFalse(ChunkTickReadiness.ready(access.key(127, 58), 128, 127, 58, readable));
        pending.complete(true);
        assertTrue(ChunkTickReadiness.ready(access.key(127, 58), 128, 127, 58, readable));
    }

    @Test
    void faceAndPlanetKeyOffsetsDoNotChangeTheRule() {
        for (int planet = 0; planet < 4; planet++)
            for (int face = 0; face < 6; face++) {
                long offset = planet * 98304L + face * 16384L;
                assertFalse(
                        ChunkTickReadiness.ready(
                                offset + 128L * 60, 128, 256, 60, (x, z) -> false));
                assertTrue(
                        ChunkTickReadiness.ready(
                                offset + 1 + 128L * 60,
                                128,
                                257,
                                60,
                                (x, z) -> {
                                    fail("interior lookup");
                                    return false;
                                }));
            }
    }
}
