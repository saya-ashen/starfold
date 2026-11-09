package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumns;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

class GroundSurfaceCacheTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static GroundSurface large() {
        int stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int[] offsets = new int[257], ends = new int[16384], states = new int[16384];
        for (int i = 0; i < 256; i++) {
            offsets[i] = 64 * i;
            for (int r = 0; r < 64; r++) {
                ends[i * 64 + r] = -64 + 6 * (r + 1);
                states[i * 64 + r] = (r & 1) == 0 ? air : stone;
            }
        }
        offsets[256] = 16384;
        int[] heights = new int[256], tops = new int[256];
        Arrays.fill(heights, 319);
        Arrays.fill(tops, stone);
        return new GroundSurface(
                1, heights, tops, new VoxelColumns(-64, 320, offsets, ends, states));
    }

    @Test
    void evictedVolumeKeepsCoarseColumnsAndInvalidatesItsMesh() {
        var invalidated = new HashSet<Long>();
        var cache = new GroundSurfaceCache(invalidated::add);
        var image = large();
        for (long key = 0; key < 200; key++) cache.put(key, image);
        assertTrue(cache.voxelBytes() <= GroundSurfaceCache.VOXEL_BYTES);
        assertTrue(
                cache.voxelBytes() > GroundSurfaceCache.VOXEL_BYTES - image.voxels().memoryBytes());
        assertEquals(200, cache.size());
        assertFalse(invalidated.isEmpty());
        for (long key : invalidated) {
            assertNull(cache.get(key).voxels());
            assertArrayEquals(image.heights(), cache.get(key).heights());
            assertArrayEquals(image.states(), cache.get(key).states());
        }
        assertNotNull(cache.get(199L).voxels());
        long before = cache.voxelBytes();
        cache.remove(199L);
        assertEquals(before - image.voxels().memoryBytes(), cache.voxelBytes());
        cache.put(198L, new GroundSurface(2, image.heights(), image.states()));
        assertEquals(before - 2L * image.voxels().memoryBytes(), cache.voxelBytes());
        cache.clear();
        assertEquals(0, cache.voxelBytes());
        assertTrue(cache.isEmpty());
    }

    @Test
    void plantGroundSurvivesActualVolumeEviction() {
        var base = large();
        var volume = base.voxels();
        var states = volume.states();
        int bush = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DEAD_BUSH.defaultBlockState());
        for (int i = 63; i < states.length; i += 64) states[i] = bush;
        int[] tops = new int[256];
        Arrays.fill(tops, bush);
        var image =
                new GroundSurface(
                        1,
                        base.heights(),
                        tops,
                        new VoxelColumns(
                                volume.floor(),
                                volume.ceiling(),
                                volume.offsets(),
                                volume.ends(),
                                states));
        var cache = new GroundSurfaceCache(k -> {}, true);
        var serverCache = new GroundSurfaceCache();
        for (long k = 0; k < 200; k++) {
            cache.put(k, image);
            serverCache.put(k, image);
        }
        assertNull(serverCache.get(0L).voxels());
        assertEquals(319, serverCache.get(0L).height(3, 7));
        assertTrue(serverCache.get(0L).state(3, 7).is(Blocks.DEAD_BUSH));
        var evicted = cache.get(0L);
        assertNull(evicted.voxels());
        assertEquals(307, evicted.height(3, 7));
        assertTrue(evicted.state(3, 7).is(Blocks.STONE));
    }

    @Test
    void deepDimensionColumnsRetainTheirActualFloor() {
        var capture = new VoxelColumns.Capture(-528, 320);
        while (!capture.done())
            capture.step(
                    (x, y, z) ->
                            y <= -400
                                    ? Blocks.STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState(),
                    8192);
        var voxels = capture.result().orElseThrow();
        int[] heights = new int[256], states = new int[256];
        Arrays.fill(heights, -400);
        Arrays.fill(states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()));
        var surface = new GroundSurface(1, heights, states, voxels);
        var saved = GroundSurfaceCodec.unpack(1, GroundSurfaceCodec.pack(surface));
        assertEquals(-400, saved.height(0, 0));
        assertTrue(saved.state(0, 0).is(Blocks.STONE));
        assertEquals(-400, surface.voxels().top(0, 0));
        assertEquals(Blocks.STONE.defaultBlockState(), surface.voxels().state(0, -528, 0));
    }

    @Test
    void topColumnsCannotDisagreeWithCapturedRevision() {
        var image = large();
        var heights = image.heights();
        heights[0]--;
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundSurface(1, heights, image.states(), image.voxels()));
        var states = image.states();
        states[0] = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundSurface(1, image.heights(), states, image.voxels()));
    }
}
