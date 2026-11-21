package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

class GroundPackedCacheTest {
    @TempDir Path root;
    @BeforeAll static void registry() { PlanetTerrainTest.registry(); }
    private GroundPackedCache.Key key() {
        return new GroundPackedCache.Key(new GroundMeshCache.Key(root.resolve("mesh/terrain"),
                GroundQuadtree.nodeId(Face.TOP.ordinal(), 4, 0, 0), 1, "overlays"), "assets-biomes-tags-layout");
    }
    private GroundLodPacking task(AtomicLong owner) {
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 32, 1, 118,
                (x, z) -> new VoxelColumn(List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState()))));
        var task = new GroundLodPacking(GroundLodPacking.prepare(built, () -> true), owner);
        for (int q = 0; q < task.appearances.length; q++) {
            task.appearances[q] = new GroundLodPacking.Appearance(.125f, .75f, 0xaabbccdd,
                    0x448866, 0x667788, 0x8899aa, q % 4, q % 4 == 3);
            for (int c = 0; c < 4; c++) task.lights[q * 4 + c] = 0x2070;
        }
        return task;
    }
    private GroundPackedCache.Entry entry() {
        var owner = new AtomicLong();
        try (var task = task(owner);
             var result = GroundLodPacking.pack(task.geometry, task.appearances, task.lights, () -> true, owner)) {
            var cached = GroundPackedCache.capture(result, task.geometry, task.appearances,
                    new GroundMeshContext.Sky(null, Map.of()));
            assertEquals(4, cached.layers().size());
            // Persist fallback skylight, not this session's artificial loaded light.
            for (var l : cached.layers()) {
                assertTrue(l.vertices().isReadOnly());
                for (int v = 0; v < l.lights().length; v++)
                    assertEquals(0xf000, Short.toUnsignedInt(l.vertices().getShort(v * 12 + 10)));
                for (int v = 0; v < result.layers.get(l.index()).remaining() / 12; v++)
                    assertEquals(0x2070, Short.toUnsignedInt(result.layers.get(l.index()).getShort(v * 12 + 10)),
                            "snapshot must not rewrite the live upload");
            }
            return cached;
        } finally { assertEquals(0, owner.get()); }
    }
    private static void same(GroundPackedCache.Entry a, GroundPackedCache.Entry b) {
        assertEquals(a.built().vertices(), b.built().vertices());
        assertEquals(a.built().materials(), b.built().materials());
        assertEquals(a.built().bounds(), b.built().bounds());
        assertEquals(a.materials(), b.materials());
        assertEquals(a.layers().size(), b.layers().size());
        for (int l = 0; l < a.layers().size(); l++) {
            var x = a.layers().get(l); var y = b.layers().get(l);
            assertEquals(x.index(), y.index()); assertEquals(x.vertices(), y.vertices());
            assertArrayEquals(x.lights(), y.lights());
        }
    }
    @Test void lz4VersionAbandonsOldGzipFilesWithoutReadingOrMigratingThem() throws Exception {
        var key = key();
        Path legacy = key.directory().resolveSibling(key.directory().getFileName().toString()
                .replace(GroundPackedCache.VERSION, "packed-lod-1")).resolve(key.file().getFileName());
        Files.createDirectories(legacy.getParent());
        var original = entry();
        try (var gzip = new DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(legacy)))) {
            GroundPackedCache.write(gzip, key, original);
        }
        byte[] oldBytes = Files.readAllBytes(legacy);
        var cache = new GroundPackedCache(0);
        assertNull(cache.get(key));
        assertEquals(0, cache.statistics()[5], "legacy data is not even decoded");
        assertArrayEquals(oldBytes, Files.readAllBytes(legacy));
        cache.put(key, original);
        byte[] packed = Files.readAllBytes(key.file());
        assertEquals("LZ4Block", new String(packed, 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
        same(original, new GroundPackedCache(0).get(key));
        assertArrayEquals(oldBytes, Files.readAllBytes(legacy));
        Files.write(key.file(), oldBytes);
        assertNull(new GroundPackedCache(0).get(key), "GZIP is rejected even in the new namespace");
    }

    @Test void finishedLayersSurviveRamEvictionAndRelogWithoutGeometryAppearanceOrPacking() throws Exception {
        var cache = new GroundPackedCache(1 << 20); var original = entry();
        cache.put(key(), original);
        assertSame(original, cache.get(key())); assertEquals(1, cache.statistics()[0]);
        var reopened = new GroundPackedCache(0);
        same(original, reopened.get(key()));
        assertEquals(1, reopened.statistics()[1]); assertEquals(0, reopened.statistics()[2]);
        assertEquals(0, reopened.statistics()[4]);
        assertTrue(Files.size(key().file()) > 0);
    }
    @Test void workerStageTotalsCoverDiskDecodeRestoreAndRamLookupWithoutOwningPayloads() {
        var writer = new GroundPackedCache(0); var original = entry(); writer.put(key(), original);
        var reader = new GroundPackedCache(1 << 20);
        var restoredEntry = reader.get(key());
        long[] disk = reader.stages();
        assertEquals(1, disk[0]);
        assertTrue(disk[1] >= disk[2] + disk[3]);
        assertTrue(disk[2] > 0);
        assertEquals(0, disk[4]);
        var owner = new AtomicLong();
        try (var restored = reader.restore(restoredEntry, owner)) {
            assertTrue(owner.get() > 0);
            assertNotNull(restored);
        }
        assertEquals(0, owner.get());
        assertSame(restoredEntry, reader.get(key()));
        long[] ram = reader.stages();
        assertEquals(2, ram[0]);
        assertTrue(ram[1] >= disk[1]);
        assertEquals(disk[2], ram[2]);
        assertEquals(disk[3], ram[3]);
        assertTrue(ram[4] > 0);
    }

    @Test void remappingIndependentRestoresNeverMutatesRamOrDiskSnapshot() {
        var original = entry(); var cache = new GroundPackedCache(1 << 20);
        cache.put(key(), original); var owner = new AtomicLong();
        for (int base : new int[] {0x123400, 0x456700}) {
            try (var restored = cache.get(key()).restore(owner)) {
                assertTrue(restored.remap(new GroundUploadBudget(Long.MAX_VALUE), m -> base + original.materials().indexOf(m)));
                for (var l : original.layers())
                    for (int v = 0; v < l.lights().length; v++) {
                        int local = GroundFaceArenas.materialId(l.vertices(), v * 12);
                        assertEquals(base + local, GroundFaceArenas.materialId(restored.layers.get(l.index()), v * 12));
                    }
            }
            assertEquals(0, owner.get());
            same(original, new GroundPackedCache(0).get(key()));
        }
    }
    @Test void unloadedColumnsDoNotResampleLightAndNearbyColumnsRefreshOnlyTheirVertices() {
        var e = entry(); var owner = new AtomicLong();
        try (var packed = e.restore(owner)) {
            assertTrue(new GroundPackedCache.LightRefresh().step(e, packed, new GroundUploadBudget(Long.MAX_VALUE),
                    c -> false, p -> fail("far cache hit must not invoke per-vertex light or terrain generation")));
            long selected = e.layers().getFirst().groups().getFirst().chunk();
            var reads = new AtomicInteger();
            assertTrue(new GroundPackedCache.LightRefresh().step(e, packed, new GroundUploadBudget(Long.MAX_VALUE),
                    c -> c == selected, p -> { reads.incrementAndGet(); return 0xf090; }));
            int expectedReads = 0;
            for (var l : e.layers()) for (var g : l.groups()) for (int v : g.vertices()) {
                boolean loaded = g.chunk() == selected;
                if (loaded) expectedReads++;
                assertEquals(loaded ? 0xf090 : 0xf000, Short.toUnsignedInt(packed.layers.get(l.index()).getShort(v * 12 + 10)));
            }
            assertEquals(expectedReads, reads.get());
            assertTrue(reads.get() < e.built().vertexCount());
        }
        assertEquals(0, owner.get());
    }
    @Test void lightRefreshResumesInBudgetAndUnavailableSectionsKeepTerrainFallback() {
        var e = entry(); var owner = new AtomicLong(); var clock = new AtomicLong();
        var cursor = new GroundPackedCache.LightRefresh(); var reads = new HashSet<BlockPos>();
        int calls = 0;
        try (var packed = e.restore(owner)) {
            boolean done = false;
            for (int i = 0; i < e.built().vertexCount() * 5 && !done; i++) {
                clock.set(0);
                done = cursor.step(e, packed, new GroundUploadBudget(9, 0, clock::incrementAndGet), c -> true,
                        p -> { reads.add(p); return -1; });
                calls++;
            }
            assertTrue(calls > 1); assertTrue(done);
            for (var l : e.layers()) assertEquals(l.vertices(), packed.layers.get(l.index()));
        }
        assertEquals(0, owner.get());
    }
    @Test void resourceLayoutBiomeOrInputChangesMissAndCorruptFilesFallBack() throws Exception {
        var key = key(); var cache = new GroundPackedCache(0); var e = entry(); cache.put(key, e);
        assertNull(cache.get(new GroundPackedCache.Key(key.geometry(), "new-resource-layout")));
        assertNull(cache.get(new GroundPackedCache.Key(new GroundMeshCache.Key(key.geometry().directory(),
                key.geometry().node(), 2, key.geometry().inputs()), key.appearance())));
        byte[] valid = Files.readAllBytes(key.file());
        for (int length : new int[] {0, 8, valid.length - 4, valid.length}) {
            var data = Arrays.copyOf(valid, length);
            if (length == valid.length) data[data.length / 2] ^= 13;
            Files.write(key.file(), data);
            assertNull(cache.get(key));
            cache.put(key, e); same(e, new GroundPackedCache(0).get(key));
        }
        assertEquals(4, cache.statistics()[5]);
    }
    @Test void headerIdentityAndMalformedBuffersAreRejected() throws Exception {
        var bytes = new ByteArrayOutputStream();
        GroundPackedCache.write(new DataOutputStream(bytes), key(), entry());
        var wrong = new GroundPackedCache.Key(key().geometry(), "wrong-appearance");
        assertThrows(IOException.class, () -> GroundPackedCache.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), wrong));
        byte[] truncated = Arrays.copyOf(bytes.toByteArray(), bytes.size() - 1);
        assertThrows(IOException.class, () -> GroundPackedCache.read(new DataInputStream(new ByteArrayInputStream(truncated)), key()));
    }
    @Test void ioFailureKeepsRamEntryAndDoesNotBreakRendering() throws Exception {
        Files.writeString(root.resolve("blocked"), "not a directory");
        var key = new GroundPackedCache.Key(new GroundMeshCache.Key(root.resolve("blocked/mesh/terrain"),
                key().geometry().node(), 1, "edits"), "appearance");
        var cache = new GroundPackedCache(1 << 20); var e = entry();
        cache.put(key, e);
        assertSame(e, cache.get(key)); assertEquals(1, cache.statistics()[5]);
    }
    @Test void snapshotCompletesBeforePackingResultIsPublishedOrInputsReleased() {
        var owner = new AtomicLong(); var prototype = task(owner);
        var taskRef = new AtomicReference<GroundLodPacking>(); var captures = new AtomicInteger();
        try (prototype; var task = new GroundLodPacking(prototype.geometry, owner, packed -> {
            assertFalse(taskRef.get().done()); assertNotNull(taskRef.get().lights);
            assertNotNull(taskRef.get().appearances[0]); captures.incrementAndGet();
        })) {
            taskRef.set(task);
            System.arraycopy(prototype.appearances, 0, task.appearances, 0, prototype.appearances.length);
            task.run(); assertTrue(task.done()); assertEquals(1, captures.get());
            try (var result = task.take()) { assertNotNull(result); }
        }
        assertEquals(0, owner.get());
    }
}
