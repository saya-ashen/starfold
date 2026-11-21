package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.surface.SurfaceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

class GroundMeshCacheTest {
    @TempDir Path root;
    @BeforeAll static void registry() { PlanetTerrainTest.registry(); }

    private GroundQuadtreeMesher.Built mesh() {
        return GroundQuadtreeMesher.build(Face.TOP, 0, 0, 16, 1, 118,
                (x, z) -> new VoxelColumn(List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState()))));
    }
    private GroundMeshCache.Key key() {
        return new GroundMeshCache.Key(root.resolve("mesh/v1"), GroundQuadtree.nodeId(Face.TOP.ordinal(), 4, 0, 0), 1,
                GroundMeshCache.fingerprint(Map.of()));
    }
    private static void same(GroundQuadtreeMesher.Built a, GroundQuadtreeMesher.Built b) {
        assertEquals(a.face(), b.face()); assertEquals(a.bounds(), b.bounds());
        assertEquals(a.materials(), b.materials()); assertEquals(a.vertices(), b.vertices());
        assertTrue(b.vertices().isReadOnly());
    }
    @Test void gpuEvictionUsesRamAndNewProcessUsesDiskWithoutCallingMesher() throws Exception {
        var cache = new GroundMeshCache(1 << 20);
        var key = key(); var built = mesh(); var builds = new AtomicInteger();
        same(built, cache.get(key, () -> { builds.incrementAndGet(); return built; }));
        same(built, cache.get(key, () -> { fail("RAM mesh must survive GPU eviction"); return null; }));
        assertEquals(1, builds.get()); assertEquals(1, cache.statistics()[0]);
        assertTrue(Files.size(key.file()) > 0);
        var reopened = new GroundMeshCache(0);
        same(built, reopened.get(key, () -> { fail("Relog must use persisted geometry"); return null; }));
        assertEquals(1, reopened.statistics()[1]); assertEquals(0, reopened.statistics()[2]);
    }
    @Test void ramBudgetDoesNotDeleteDiskAndZeroBudgetStillReusesDisk() {
        var cache = new GroundMeshCache(1);
        cache.get(key(), this::mesh);
        assertEquals(0, cache.statistics()[3]);
        cache.get(key(), () -> { fail("RAM eviction must leave disk geometry"); return null; });
        assertEquals(1, cache.statistics()[1]);
    }
    @Test void damagedAndTruncatedFilesAreRebuiltAndReplaced() throws Exception {
        var key = key(); var original = mesh();
        new GroundMeshCache(0).get(key, () -> original);
        byte[] valid = Files.readAllBytes(key.file());
        for (int length : new int[] {0, 8, valid.length - 4, valid.length}) {
            var bytes = Arrays.copyOf(valid, length);
            if (length == valid.length) bytes[bytes.length / 2] ^= 42;
            Files.write(key.file(), bytes);
            var cache = new GroundMeshCache(0); var called = new AtomicInteger();
            same(original, cache.get(key, () -> { called.incrementAndGet(); return original; }));
            assertEquals(1, called.get());
            same(original, new GroundMeshCache(0).get(key, () -> { fail("Repaired file must reopen"); return null; }));
        }
    }
    @Test void terrainRegistryFormatAndOverlayContentsInvalidateButRevisionAloneDoesNot() {
        var a = new SurfaceKey(1, "a", 256, "v1");
        assertNotEquals(GroundMeshCache.directory(root, a, "registry1"), GroundMeshCache.directory(root, a, "registry2"));
        assertNotEquals(GroundMeshCache.directory(root, a, "r"), GroundMeshCache.directory(root, new SurfaceKey(2, "a", 256, "v1"), "r"));
        assertNotEquals(GroundMeshCache.directory(root, a, "r"), GroundMeshCache.directory(root, new SurfaceKey(1, "a", 256, "v2"), "r"));
        int[] heights = new int[256], states = new int[256];
        Arrays.fill(heights, 129); Arrays.fill(states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()));
        var first = new GroundSurface(1, heights, states); var newer = new GroundSurface(2, heights, states);
        assertEquals(GroundMeshCache.fingerprint(Map.of(1L, first)), GroundMeshCache.fingerprint(Map.of(1L, newer)));
        heights[0]++;
        var edit = new GroundSurface(3, heights, states);
        assertNotEquals(GroundMeshCache.fingerprint(Map.of(1L, first)), GroundMeshCache.fingerprint(Map.of(1L, edit)));
        assertNotEquals(GroundMeshCache.fingerprint(Map.of()), GroundMeshCache.fingerprint(Map.of(1L, first)));
        var ordered = new LinkedHashMap<Long, GroundSurface>(); ordered.put(2L, edit); ordered.put(1L, first);
        assertEquals(GroundMeshCache.fingerprint(ordered), GroundMeshCache.fingerprint(Map.of(1L, first, 2L, edit)));
    }
    @Test void ioFailureFallsBackAndDoesNotTurnIntoJobFailure() throws Exception {
        Files.writeString(root.resolve("blocked"), "not a directory");
        var key = new GroundMeshCache.Key(root.resolve("blocked/mesh"), key().node(), 1, "none");
        var cache = new GroundMeshCache(0); var built = mesh();
        same(built, cache.get(key, () -> built)); assertEquals(1, cache.statistics()[4]);
    }
    @Test void diskHeaderRejectsWrongNodeStepAndInputFingerprint() throws Exception {
        var bytes = new ByteArrayOutputStream();
        GroundMeshCache.write(new DataOutputStream(bytes), key(), mesh());
        for (var wrong : List.of(new GroundMeshCache.Key(key().directory(), key().node() + 1, 1, key().inputs()),
                new GroundMeshCache.Key(key().directory(), key().node(), 2, key().inputs()),
                new GroundMeshCache.Key(key().directory(), key().node(), 1, "changed")))
            assertThrows(IOException.class, () -> GroundMeshCache.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), wrong));
    }
    @Test void adaptiveDiskBudgetAllowsHundredsOfGiBAndProtectsFreeSpace() {
        long g = GroundDiskBudget.GiB;
        assertEquals(475 * g, GroundDiskBudget.allowance(1000 * g, 1000 * g, 0));
        assertEquals(512 * g, GroundDiskBudget.allowance(4000 * g, 3000 * g, 0));
        assertEquals(0, GroundDiskBudget.allowance(1000 * g, 40 * g, 0));
        assertEquals(100 * g, GroundDiskBudget.allowance(1000 * g, 150 * g, 100 * g));
    }
}
