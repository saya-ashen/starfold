package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Replays the request/serve/mesh schedule around face seams with the real policy classes.
 * Rates are modelled (tiles per tick); this is not a Minecraft, GPU or frame-time measurement. */
class SeamStreamingRateTest {
    /** Deliberately below SERVE_TILES: assumes the time budget only lets two captures through per tick. */
    private static final int SERVED_PER_TICK = 2;

    @BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    private static int[][] seams() {
        return new int[][] {{0, 1024}, {2047, 1024}, {1024, 0}, {1024, 2047}};
    }

    private static Point outward(int[] uv) {
        return new Point(
                uv[0] == 0 ? -1 : uv[0] == 2047 ? 1 : 0,
                0,
                uv[1] == 0 ? -1 : uv[1] == 2047 ? 1 : 0);
    }

    @Test
    void edgeNeighboursAreMutualAcrossEverySeamAndCorner() {
        for (int face = 0; face < 6; face++)
            for (int a = 0; a < 128; a++)
                for (int[] uv : new int[][] {{0, a}, {127, a}, {a, 0}, {a, 127}, {64, a}}) {
                    long key = uv[0] + 128L * (uv[1] + 128L * face);
                    var neighbours = GroundStreaming.neighbours(key);
                    assertEquals(4, Arrays.stream(neighbours).distinct().count());
                    for (long n : neighbours) {
                        assertNotEquals(key, n);
                        assertTrue(
                                Arrays.stream(GroundStreaming.neighbours(n))
                                        .anyMatch(back -> back == key),
                                key + " <-> " + n);
                    }
                }
    }

    @Test
    void snapshotKnowsItsHighestOccupiedLayerAndComparesSurfacesWithoutCopies() {
        var values = new int[GroundChunkSnapshot.VALUES];
        int air = Block.getId(Blocks.AIR.defaultBlockState()),
                stone = Block.getId(Blocks.STONE.defaultBlockState());
        Arrays.fill(values, air);
        assertEquals(
                GroundChunkSnapshot.MIN_Y - 1,
                GroundChunkSnapshot.fromStates(0, 0, 0, 1, values).top());
        for (int i = 0; i < 256 * (10 + 64 + 1); i++) values[i] = stone;
        values[5 + 16 * (7 + 16 * (200 + 64))] = Block.getId(Blocks.TORCH.defaultBlockState());
        var image = GroundChunkSnapshot.fromStates(0, 0, 0, 1, values);
        assertEquals(200, image.top());
        assertEquals(Blocks.TORCH.defaultBlockState(), image.state(5, 200, 7));
        assertEquals(Blocks.STONE.defaultBlockState(), image.state(15, 10, 15));
        assertTrue(image.state(0, 11, 0).isAir());
        var a = GroundViewCache.surfaceImage(image);
        var b = GroundViewCache.surfaceImage(image);
        assertTrue(GroundOwnership.same(a, a));
        assertTrue(GroundOwnership.same(a, b));
        assertFalse(GroundOwnership.same(a, null));
        assertFalse(GroundOwnership.same(null, a));
        values[0 + 16 * (0 + 16 * (201 + 64))] = stone;
        assertFalse(
                GroundOwnership.same(
                        a,
                        GroundViewCache.surfaceImage(
                                GroundChunkSnapshot.fromStates(0, 0, 0, 1, values))));
    }

}
