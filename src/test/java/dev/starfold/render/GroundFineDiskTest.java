package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.surface.SurfaceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class GroundFineDiskTest {
    @TempDir Path root;

    @Test void restartReusesFineColumnsAndRepairsTornTailBeforeAppending() throws Exception {
        var path = root.resolve("planet");
        long[] first = {123, 456};
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 2)) {
            assertArrayEquals(first, disk.get(0, 2, 3, () -> first));
            assertArrayEquals(first, disk.get(0, 2, 3, () -> fail("cached")));
            assertEquals(1, disk.openRegions());
        }
        Path file = path.resolve("0.bin");
        long end = Files.size(file);
        Files.write(file, new byte[] {0, 2, 0}, StandardOpenOption.APPEND);
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 2)) {
            assertArrayEquals(first, disk.get(0, 2, 3, () -> fail("relog must not generate")));
            assertEquals(end, Files.size(file));
            assertArrayEquals(new long[0], disk.get(0, 4, 5, () -> new long[0]));
        }
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 2)) {
            assertArrayEquals(first, disk.get(0, 2, 3, () -> fail("valid prefix")));
            assertArrayEquals(new long[0], disk.get(0, 4, 5, () -> fail("empty columns are also cached")));
        }
    }

    @Test void checksumDamageAndOversizedRecordsCannotPublishCorruptColumns() throws Exception {
        var path = root.resolve("planet");
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 1)) {
            disk.get(0, 0, 0, () -> new long[] {11});
            disk.get(0, 1, 0, () -> new long[] {22});
        }
        Path file = path.resolve("0.bin");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 1)) {
            assertArrayEquals(new long[] {11}, disk.get(0, 0, 0, () -> fail("valid prefix")));
            assertArrayEquals(new long[] {33}, disk.get(0, 1, 0, () -> new long[] {33}));
        }
        Files.write(file, ByteBuffer.allocate(4).putShort((short) 2).putShort((short) 65535).array(),
                StandardOpenOption.APPEND);
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 1)) {
            assertArrayEquals(new long[] {33}, disk.get(0, 1, 0, () -> fail("repaired append")));
            disk.get(0, 2, 0, () -> new long[] {44});
            assertTrue(Files.size(file) < 100);
            assertThrows(IllegalArgumentException.class,
                    () -> disk.get(0, 3, 0, () -> new long[VoxelColumn.MAX_RUNS + 1]));
        }
    }

    @Test void regionLruBoundsOpenFilesAndIndexesAcrossFacesAndPatchEdges() throws Exception {
        var path = root.resolve("planet");
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 2)) {
            for (int i = 0; i < 10; i++) {
                int value = i;
                disk.get(i % 6, i * 64, 63, () -> new long[] {value});
                assertTrue(disk.openRegions() <= 2);
            }
            for (int i = 0; i < 10; i++)
                assertArrayEquals(new long[] {i}, disk.get(i % 6, i * 64, 63, () -> fail("LRU reload")));
            disk.get(5, 2047, 2047, () -> new long[] {99});
            assertThrows(IllegalArgumentException.class, () -> disk.get(6, 0, 0, () -> new long[0]));
        }
    }

    @Test void identityUsesGenerationContentAndRegistryNotInstallationOrSession() {
        var key = new SurfaceKey(42, "test", 16, "generation-1");
        var path = GroundFineDisk.directory(root, key, "states-1");
        assertEquals(path.getFileName(), GroundFineDisk.directory(root.resolve("elsewhere"), key, "states-1").getFileName());
        assertNotEquals(path, GroundFineDisk.directory(root, key, "states-2"));
        assertNotEquals(path, GroundFineDisk.directory(root, new SurfaceKey(43, "test", 16, "generation-1"), "states-1"));
        assertNotEquals(path, GroundFineDisk.directory(root, new SurfaceKey(42, "test", 16, "generation-2"), "states-1"));
    }

    @Test void ownershipAndIoFailureReleaseFilesAndFallBackToGeneration() throws Exception {
        Path path = root.resolve("planet");
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 1)) {
            assertThrows(IOException.class, () -> new GroundFineDisk(path, Long.MAX_VALUE, 1));
            Files.createDirectory(path.resolve("0.bin")); // Unwritable region, without depending on OS permissions/root.
            assertArrayEquals(new long[] {1}, disk.get(0, 0, 0, () -> new long[] {1}));
            assertEquals(0, disk.openRegions());
        }
        Files.delete(path.resolve("0.bin"));
        try (var reopened = new GroundFineDisk(path, Long.MAX_VALUE, 1)) {
            reopened.get(0, 0, 0, () -> new long[] {2});
        }
    }

    @Test void diskAllowancePrunesClosedRegionsButDoesNotDeleteOpenOnes() throws Exception {
        Path path = root.resolve("planet");
        try (var disk = new GroundFineDisk(path, 56, 1)) {
            for (int i = 0; i < 3; i++) disk.get(0, i * 64, 0, () -> new long[] {1});
        }
        try (var files = Files.list(path)) { assertEquals(2, files.count()); }
        try (var disk = new GroundFineDisk(path, 0, 1)) {
            disk.get(0, 0, 0, () -> new long[] {2});
            assertTrue(Files.exists(path.resolve("0.bin")));
        }
        try (var files = Files.list(path)) { assertEquals(0, files.count()); }
    }

    @Test void generationDoesNotSerializeDifferentMeshWorkersAndRacesPublishOneColumn() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try (var disk = new GroundFineDisk(root.resolve("planet"), Long.MAX_VALUE, 2)) {
            var barrier = new CyclicBarrier(2);
            var generated = new AtomicInteger();
            var compute = (java.util.function.Supplier<long[]>) () -> {
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                generated.incrementAndGet();
                return new long[] {7};
            };
            var a = pool.submit(() -> disk.get(0, 0, 0, compute));
            var b = pool.submit(() -> disk.get(0, 0, 0, compute));
            assertArrayEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(2, generated.get()); // RAM FineCache owns singleflight; disk still handles races safely.
            assertArrayEquals(new long[] {7}, disk.get(0, 0, 0, () -> fail("published once")));
            assertEquals(GroundFineDisk.HEADER + 16, Files.size(root.resolve("planet/0.bin")));
        } finally { pool.shutdownNow(); }
    }

    @Test void chartHaloSamplingBypassesInteriorDiskAndRamKeys() throws Exception {
        PlanetTerrainTest.registry();
        var entry = new GroundBaseCache.Entry(new PlanetTerrain(42));
        try (var disk = new GroundFineDisk(root.resolve("halo"), Long.MAX_VALUE, 2)) {
            entry.fineDisk = disk;
            int size = entry.key.size();
            for (int step : new int[] {1, 2})
                for (int[] uv : new int[][] {{-step, 0}, {0, -step}, {size, 0}, {0, size}}) {
                    var children = new java.util.ArrayList<VoxelColumn>();
                    for (int v = 0; v < step; v++) for (int u = 0; u < step; u++)
                        children.add(VoxelColumn.visible(entry.function, Face.VALUES[0], uv[0] + u, uv[1] + v));
                    long[] expected = step == 1 ? children.get(0).packed() : VoxelColumn.reduce(children, 2).packed();
                    assertArrayEquals(expected, entry.voxel(Face.VALUES[0], uv[0], uv[1], step).packed());
                }
            assertEquals(0, entry.fineBytes(), "halo coordinates must not alias interior RAM keys");
            assertArrayEquals(new long[] {0, 0, 0}, disk.statistics(), "halo samples do not open disk regions");
        } finally { entry.fine.close(); }
    }

    @Test void productionEntryReusesPersistedStepOneForStepTwoReductionAfterRamLoss() throws Exception {
        PlanetTerrainTest.registry();
        var terrain = new PlanetTerrain(42);
        long[] packed = new VoxelColumn(java.util.List.of(new VoxelColumn.Run(0, 8,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()))).packed();
        Path path = root.resolve("planet");
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 2)) {
            for (int u = 0; u < 2; u++) for (int v = 0; v < 2; v++) disk.get(0, u, v, () -> packed);
        }
        try (var disk = new GroundFineDisk(path, Long.MAX_VALUE, 2)) {
            var entry = new GroundBaseCache.Entry(terrain);
            entry.fineDisk = disk;
            entry.state = GroundBaseCache.Entry.State.READY;
            GroundBaseCache.release(entry); // Coarse work is complete, but fine mesh demand continues.
            assertSame(disk, entry.fineDisk, "coarse completion must not close the fine journal");
            try {
                assertArrayEquals(packed, entry.voxel(Face.VALUES[0], 0, 0, 1).packed());
                assertArrayEquals(packed, entry.voxel(Face.VALUES[0], 0, 0, 2).packed());
            } finally {
                entry.state = GroundBaseCache.Entry.State.CLOSED;
                GroundBaseCache.release(entry);
                assertNull(entry.fineDisk, "entry retirement must close both cache tiers");
                entry.fine.close();
            }
        }
    }
}
