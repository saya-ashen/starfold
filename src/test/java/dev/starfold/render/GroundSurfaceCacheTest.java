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
        return large(64);
    }

    private static GroundSurface large(int runs) {
        int stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int[] offsets = new int[257], ends = new int[256 * runs], states = new int[256 * runs];
        for (int i = 0; i < 256; i++) {
            offsets[i] = runs * i;
            for (int r = 0; r < runs; r++) {
                ends[i * runs + r] = -64 + 384 * (r + 1) / runs;
                states[i * runs + r] = (r & 1) == 0 ? air : stone;
            }
        }
        offsets[256] = 256 * runs;
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
        var invalidated = new ArrayList<Long>();
        var serverInvalidated = new ArrayList<Long>();
        var expectedInvalidated = new ArrayList<Long>();
        var expectedServerInvalidated = new ArrayList<Long>();
        var cache = new GroundSurfaceCache(invalidated::add, true);
        var serverCache = new GroundSurfaceCache(serverInvalidated::add);
        var expected = new ScanningCache(expectedInvalidated, true);
        var expectedServer = new ScanningCache(expectedServerInvalidated, false);
        for (long k = 0; k < 200; k++) {
            cache.put(k, image);
            serverCache.put(k, image);
            expected.put(k, image);
            expectedServer.put(k, image);
        }
        assertSameCache(expected, cache, expectedInvalidated, invalidated);
        assertSameCache(expectedServer, serverCache, expectedServerInvalidated, serverInvalidated);
        assertNull(serverCache.get(0L).voxels());
        assertEquals(319, serverCache.get(0L).height(3, 7));
        assertTrue(serverCache.get(0L).state(3, 7).is(Blocks.DEAD_BUSH));
        var evicted = cache.get(0L);
        assertNull(evicted.voxels());
        assertEquals(307, evicted.height(3, 7));
        assertTrue(evicted.state(3, 7).is(Blocks.STONE));
    }

    @Test
    void longCoarsePrefixAndRestoredOldKeysMatchScanningEviction() {
        var image = large();
        var coarse = new GroundSurface(17, image.heights(), image.states());
        int capacity = (int) (GroundSurfaceCache.VOXEL_BYTES / GroundSurfaceCache.bytes(image));
        for (boolean filterPlants : new boolean[] {false, true}) {
            var actualInvalidated = new ArrayList<Long>();
            var expectedInvalidated = new ArrayList<Long>();
            var actual = new GroundSurfaceCache(actualInvalidated::add, filterPlants);
            var expected = new ScanningCache(expectedInvalidated, filterPlants);
            var prefix = new LinkedHashMap<Long, GroundSurface>();
            for (long key = 0; key < 4096; key++) prefix.put(key, coarse);
            actual.putAll(prefix);
            expected.putAll(prefix);
            for (long key = 4096; key < 4096 + capacity + 12; key++) {
                assertSame(expected.put(key, image), actual.put(key, image));
                assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
            }
            // Restoration must not append an old coarse key to the voxel FIFO.
            actualInvalidated.clear();
            expectedInvalidated.clear();
            assertSame(coarse, actual.put(3L, image));
            expected.put(3L, image);
            assertEquals(List.of(3L), actualInvalidated);
            assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
            // The same rule applies to keys whose volume was previously evicted.
            assertTrue(expected.put(4096L, image).sameAs(actual.put(4096L, image)));
            assertEquals(List.of(3L, 4096L), actualInvalidated);
            assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);

            long newest = 4096L + capacity + 11;
            assertSame(image, actual.remove(newest));
            expected.remove(newest);
            actual.put(4L, image);
            expected.put(4L, image);
            assertNotNull(actual.get(4L).voxels());
            actual.put(10000L, image);
            expected.put(10000L, image);
            assertEquals(List.of(3L, 4096L, 4L), actualInvalidated);
            assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        }
    }

    @Test
    void replacementsRemovalsAndClearMatchScanningEviction() {
        var image = large(32);
        var larger = large();
        var revised = new GroundSurface(29, image.heights(), image.states(), image.voxels());
        var coarse = new GroundSurface(31, image.heights(), image.states());
        var actualInvalidated = new ArrayList<Long>();
        var expectedInvalidated = new ArrayList<Long>();
        var actual = new GroundSurfaceCache(actualInvalidated::add);
        var expected = new ScanningCache(expectedInvalidated, false);
        long capacity = GroundSurfaceCache.VOXEL_BYTES / GroundSurfaceCache.bytes(image);
        for (long key = 0; key < capacity + 24; key++) {
            actual.put(key, image);
            expected.put(key, image);
        }
        // Replacing live detail with coarse data must remove the eviction candidate.
        assertSame(expected.put(149L, coarse), actual.put(149L, coarse));
        assertSame(expected.put(148L, revised), actual.put(148L, revised));
        assertSame(expected.remove(147L), actual.remove(147L));
        assertNull(actual.remove(-1L));
        assertNull(expected.remove(-1L));
        assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        // A removed/reinserted key gets a new position; a replaced key retains its old one.
        actual.put(147L, larger);
        expected.put(147L, larger);
        assertEquals(147L, actual.lastEntry().getKey());
        actual.put(149L, larger);
        expected.put(149L, larger);
        assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        var random = new Random(0x5FACE);
        for (int step = 0; step < 500; step++) {
            long key = random.nextInt(180);
            if (step % 7 == 0) {
                var removed = expected.remove(key);
                var actualRemoved = actual.remove(key);
                assertTrue(removed == null ? actualRemoved == null : removed.sameAs(actualRemoved));
            } else {
                var value =
                        switch (step % 4) {
                            case 0 -> coarse;
                            case 1 -> larger;
                            case 2 -> revised;
                            default -> image;
                        };
                var previous = expected.put(key, value);
                var actualPrevious = actual.put(key, value);
                assertTrue(
                        previous == null
                                ? actualPrevious == null
                                : previous.sameAs(actualPrevious));
            }
            assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        }
        int callbacks = actualInvalidated.size();
        actual.clear();
        expected.clear();
        assertEquals(callbacks, actualInvalidated.size());
        assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        var images = new LinkedHashMap<Long, GroundSurface>();
        for (long key = 300; key >= 0; key--) images.put(key, image);
        actual.putAll(images);
        expected.putAll(images);
        assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        callbacks = actualInvalidated.size();
        actual.put(1000L, larger);
        expected.put(1000L, larger);
        assertEquals(callbacks + 2, actualInvalidated.size());
        assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
        actual.clear();
        expected.clear();
        actual.put(0L, larger);
        expected.put(0L, larger);
        assertSameCache(expected, actual, expectedInvalidated, actualInvalidated);
    }

    private static void assertSameCache(
            ScanningCache expected,
            GroundSurfaceCache actual,
            List<Long> expectedInvalidated,
            List<Long> actualInvalidated) {
        assertEquals(expected.bytes, actual.voxelBytes());
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
        assertEquals(expectedInvalidated, actualInvalidated);
        long counted = 0;
        for (var entry : expected.entrySet()) {
            var surface = actual.get(entry.getKey());
            assertTrue(entry.getValue().sameAs(surface), "surface at " + entry.getKey());
            counted += GroundSurfaceCache.bytes(surface);
        }
        assertEquals(counted, actual.voxelBytes());
    }

    /** Original scan algorithm: an independent behavioral oracle for the indexed implementation. */
    private static final class ScanningCache extends LinkedHashMap<Long, GroundSurface> {
        private final List<Long> invalidated;
        private final boolean filterPlants;
        private long bytes;

        ScanningCache(List<Long> invalidated, boolean filterPlants) {
            this.invalidated = invalidated;
            this.filterPlants = filterPlants;
        }

        @Override
        public GroundSurface put(Long key, GroundSurface value) {
            var previous = super.put(key, value);
            bytes += GroundSurfaceCache.bytes(value) - GroundSurfaceCache.bytes(previous);
            for (var entry : entrySet()) {
                if (bytes <= GroundSurfaceCache.VOXEL_BYTES) break;
                var image = entry.getValue();
                if (image.voxels() == null) continue;
                bytes -= GroundSurfaceCache.bytes(image);
                entry.setValue(
                        filterPlants
                                ? image.surfaceOnly()
                                : new GroundSurface(
                                        image.revision(), image.heights(), image.states()));
                invalidated.add(entry.getKey());
            }
            return previous;
        }

        @Override
        public void putAll(Map<? extends Long, ? extends GroundSurface> images) {
            images.forEach(this::put);
        }

        @Override
        public GroundSurface remove(Object key) {
            var previous = super.remove(key);
            bytes -= GroundSurfaceCache.bytes(previous);
            return previous;
        }

        @Override
        public void clear() {
            super.clear();
            bytes = 0;
        }
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
