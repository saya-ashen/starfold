package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.surface.SurfaceFunction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

class VoxelSurfaceCacheTest {
    static SurfaceFunction function;

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
        function = new SurfaceFunction(new PlanetTerrain(42));
    }

    @Test
    void partialCopyPublishesIncrementallyAndResumesOnlyMissingPatches() throws Exception {
        var original = new VoxelSurfaceCache(16);
        int[] keys = original.patchKeys();
        original.fillPatch(function, keys[0], p -> {});
        original.fillPatch(function, keys[1], p -> {});
        var bytes = new ByteArrayOutputStream();
        original.write(new DataOutputStream(bytes));
        var remaining = new ByteArrayInputStream(bytes.toByteArray());
        var copy = new VoxelSurfaceCache(16);
        var published = new BitSet();
        assertTrue(
                copy.read(
                        new DataInputStream(remaining),
                        key -> {
                            assertNotNull(copy.column(Face.VALUES[key / 1024], 0, 0, 64));
                            if (published.isEmpty()) {
                                assertTrue(
                                        remaining.available() > 0,
                                        "first patch is visible before reading the next");
                                assertFalse(copy.hasPatch(keys[1]));
                            }
                            published.set(key);
                        }));
        assertEquals(2, published.cardinality());
        assertFalse(copy.complete());
        var resumed = new BitSet();
        copy.fill(
                function,
                2,
                Face.TOP,
                8,
                8,
                key -> {
                    synchronized (resumed) {
                        resumed.set(key);
                    }
                });
        assertTrue(copy.complete());
        assertEquals(4, resumed.cardinality());
        assertFalse(resumed.intersects(published));
        original.fill(function, 1, Face.TOP, 8, 8, p -> {});
        for (var face : Face.VALUES)
            for (int step = 4; step <= 64; step *= 2)
                for (int z = 0; z < 16; z += step)
                    for (int x = 0; x < 16; x += step)
                        assertEquals(
                                original.column(face, x, z, step), copy.column(face, x, z, step));
    }

    @Test
    void concurrentGenerationAndRepeatedReplayCountOnlyUniquePublications() throws Exception {
        var source = new VoxelSurfaceCache(16);
        source.fillPatch(function, 0, p -> {});
        var bytes = new ByteArrayOutputStream();
        source.writePatch(new DataOutputStream(bytes), 0);
        byte[] record = bytes.toByteArray();
        var copy = new VoxelSurfaceCache(16);
        assertEquals(0, copy.retainedBytes());
        var publications = new java.util.concurrent.atomic.AtomicInteger();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                final boolean replay = i % 2 == 0;
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (int repeat = 0; repeat < 4; repeat++) {
                                        if (replay)
                                            assertEquals(
                                                    0,
                                                    copy.readPatch(
                                                            new DataInputStream(
                                                                    new ByteArrayInputStream(
                                                                            record)),
                                                            p -> publications.incrementAndGet()));
                                        else
                                            copy.fillPatch(
                                                    function,
                                                    0,
                                                    p -> publications.incrementAndGet());
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, publications.get());
        assertEquals(source.retainedBytes(), copy.retainedBytes(),
                "concurrent generation and replay must count each immutable plane exactly once");
        assertTrue(copy.retainedBytes() > 0);
        assertFalse(copy.complete(), "duplicate records cannot complete a partial cache");
        var keys = copy.patchKeys();
        for (int i = 1; i < keys.length - 1; i++) {
            copy.fillPatch(function, keys[i], p -> publications.incrementAndGet());
            assertFalse(copy.complete());
        }
        // Caller failure cannot undo immutable data or lose its completion count on retry.
        int last = keys[keys.length - 1];
        assertThrows(
                IllegalStateException.class,
                () ->
                        copy.fillPatch(
                                function,
                                last,
                                p -> {
                                    assertTrue(copy.complete());
                                    publications.incrementAndGet();
                                    throw new IllegalStateException(
                                            "caller failed after publication");
                                }));
        copy.fillPatch(function, last, p -> fail("published data must survive a caller retry"));
        assertEquals(keys.length, publications.get());
        assertTrue(copy.complete());
        assertEquals(source.column(Face.VALUES[0], 0, 0, 4), copy.column(Face.VALUES[0], 0, 0, 4));
    }

    @Test
    void corruptRecordsNeverPublishAndValidPrefixSurvives() throws Exception {
        var source = new VoxelSurfaceCache(16);
        source.fillPatch(function, 0, p -> {});
        source.fillPatch(function, 1024, p -> {});
        var bytes = new ByteArrayOutputStream();
        source.write(new DataOutputStream(bytes));
        byte[] valid = bytes.toByteArray();
        int second = 12 + 8 + ByteBuffer.wrap(valid).getInt(12);
        // Exercise checksum failure, out-of-face patch keys, wrong dimensions, and bad offsets.
        for (int mutation = 0; mutation < 4; mutation++) {
            byte[] corrupt = valid.clone();
            var buffer = ByteBuffer.wrap(corrupt);
            int length = buffer.getInt(second);
            if (mutation == 0) corrupt[second + 12] ^= 1;
            else {
                int offset =
                        switch (mutation) {
                            case 1 -> 4; // payload key
                            case 2 -> 8; // plane width
                            default -> 16; // first offset must be zero
                        };
                buffer.putInt(second + offset, Integer.MAX_VALUE);
                var crc = new CRC32();
                crc.update(corrupt, second + 4, length);
                buffer.putInt(second + 4 + length, (int) crc.getValue());
            }
            var copy = new VoxelSurfaceCache(16);
            var published = new BitSet();
            assertThrows(
                    IOException.class,
                    () ->
                            copy.read(
                                    new DataInputStream(new ByteArrayInputStream(corrupt)),
                                    published::set));
            assertEquals(1, published.cardinality());
            assertTrue(copy.hasPatch(0));
            assertFalse(copy.hasPatch(1024));
        }
    }

    @Test
    void oversizedRecordIsRejectedBeforeAllocationAndCancellationCannotPublish() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var cache = new VoxelSurfaceCache(16);
        var out = new DataOutputStream(bytes);
        cache.writeHeader(out);
        out.writeInt(Integer.MAX_VALUE);
        assertThrows(
                IOException.class,
                () ->
                        cache.read(
                                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                                p -> fail()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class, () -> cache.fillPatch(function, 0, p -> fail()));
            assertFalse(cache.hasPatch(0));
        } finally {
            Thread.interrupted();
        }
        cache.fillPatch(function, 0, p -> {});
        var children = new ArrayList<VoxelColumn>();
        for (int z = 0; z < 4; z += 2)
            for (int x = 0; x < 4; x += 2) {
                var fine = new ArrayList<VoxelColumn>();
                for (int dz = 0; dz < 2; dz++)
                    for (int dx = 0; dx < 2; dx++)
                        fine.add(VoxelColumn.visible(function, Face.VALUES[0], x + dx, z + dz));
                children.add(VoxelColumn.reduce(fine, 2));
            }
        assertEquals(VoxelColumn.reduce(children, 4), cache.column(Face.VALUES[0], 0, 0, 4));
    }
}
