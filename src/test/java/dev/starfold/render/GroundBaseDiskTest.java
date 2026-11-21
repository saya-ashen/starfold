package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelSurfaceCache;
import dev.starfold.worldgen.surface.SurfaceFunction;
import dev.starfold.worldgen.surface.SurfaceKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.BitSet;

class GroundBaseDiskTest {
    @TempDir Path root;

    @Test
    void materialAndShapeRevisionInvalidatesEveryDerivedCache() throws Exception {
        var key = new SurfaceKey(42, "test", 16, "generation-1");
        String registry = "ordered-state-ids";
        var current = GroundBaseDisk.file(root, key, registry);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        String oldContent =
                "voxel-visible-reduce-1/format-"
                        + VoxelSurfaceCache.FORMAT_VERSION
                        + "/registry-"
                        + registry;
        String oldHash =
                java.util.HexFormat.of()
                        .formatHex(
                                digest.digest(
                                        oldContent.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8)))
                        .substring(0, 16);
        String terrainHash = current.getFileName().toString().split("-")[0];
        var previous = current.resolveSibling(terrainHash + "-" + oldHash + ".bin");
        assertNotEquals(previous, current);
        var fine = GroundFineDisk.directory(root, key, registry);
        var geometry = GroundMeshCache.directory(root, key, registry);
        var packed =
                new GroundPackedCache.Key(
                        new GroundMeshCache.Key(geometry, 0, 1, "base"), "appearance");
        String currentStem = current.getFileName().toString().replace(".bin", "");
        assertTrue(fine.getFileName().toString().startsWith(currentStem));
        assertTrue(geometry.getFileName().toString().startsWith(currentStem));
        assertTrue(packed.directory().getFileName().toString().startsWith(currentStem));
        assertFalse(fine.toString().contains(oldHash));
        assertFalse(geometry.toString().contains(oldHash));
        assertFalse(packed.directory().toString().contains(oldHash));
    }

    @Test
    void restartRetainsPartialProgressAndRepairsTornTailBeforeAppending() throws Exception {
        PlanetTerrainTest.registry();
        var function = new SurfaceFunction(new PlanetTerrain(42));
        var source = new VoxelSurfaceCache(16);
        source.fillPatch(function, 0, p -> {});
        source.fillPatch(function, 1024, p -> {});
        var file = root.resolve("0123456789abcdef-test.bin");
        try (var disk = GroundBaseDisk.open(file, source)) {
            assertFalse(disk.loadNext(source, p -> fail()));
            disk.savePatch(source, 0);
            disk.savePatch(source, 0); // Replayed journals may contain duplicate records.
        }
        long validLength = Files.size(file);
        // A shutdown in the middle of a length/payload must not hide the valid prefix forever.
        Files.write(file, new byte[] {0, 0, 1}, StandardOpenOption.APPEND);
        var resumed = new VoxelSurfaceCache(16);
        var published = new BitSet();
        try (var disk = GroundBaseDisk.open(file, resumed)) {
            assertTrue(disk.loadNext(resumed, published::set));
            assertEquals(1, published.cardinality());
            assertTrue(disk.loadNext(resumed, p -> fail("duplicate replay must not publish")));
            assertFalse(resumed.complete());
            assertFalse(disk.loadNext(resumed, published::set));
            assertEquals(validLength, Files.size(file));
            assertTrue(resumed.hasPatch(0));
            assertFalse(resumed.hasPatch(1024));
            resumed.fillPatch(function, 0, p -> fail("loaded patch must not be rebuilt"));
            resumed.fillPatch(function, 1024, p -> {});
            disk.savePatch(resumed, 1024);
        }
        var copy = new VoxelSurfaceCache(16);
        published.clear();
        try (var disk = GroundBaseDisk.open(file, copy)) {
            while (disk.loadNext(copy, published::set)) {}
        }
        assertEquals(2, published.cardinality());
        assertFalse(copy.complete());
        for (int key : new int[] {0, 1024})
            for (int step = 4; step <= 64; step *= 2)
                assertEquals(
                        source.column(
                                dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[key / 1024],
                                0,
                                0,
                                step),
                        copy.column(
                                dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[key / 1024],
                                0,
                                0,
                                step));
    }

    @Test
    void journalHasExclusiveOwnershipAndReleasesItOnCloseAndFailedOpen() throws Exception {
        var cache = new VoxelSurfaceCache(16);
        var file = root.resolve("0123456789abcdef-exclusive.bin");
        try (var first = GroundBaseDisk.open(file, cache)) {
            assertThrows(IOException.class, () -> GroundBaseDisk.open(file, cache));
            assertFalse(first.loadNext(cache, p -> fail()));
        }
        try (var reopened = GroundBaseDisk.open(file, cache)) {
            assertFalse(reopened.loadNext(cache, p -> fail()));
        }
        var blocked = root.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        var unavailable = blocked.resolve("0123456789abcdef-failed.bin");
        assertThrows(IOException.class, () -> GroundBaseDisk.open(unavailable, cache));
        Files.delete(blocked);
        try (var recovered = GroundBaseDisk.open(unavailable, cache)) {
            assertFalse(recovered.loadNext(cache, p -> fail()));
        }
    }

    @Test
    void identityUsesPlanetContentAndRegistryMeaningAcrossInstallLocations() {
        var key = new SurfaceKey(42, "test", 16, "generation-1");
        var first = GroundBaseDisk.file(root, key, "ordered-state-ids");
        assertEquals(
                first.getFileName(),
                GroundBaseDisk.file(root.resolve("other-install"), key, "ordered-state-ids")
                        .getFileName());
        assertNotEquals(
                first,
                GroundBaseDisk.file(
                        root,
                        new SurfaceKey(
                                key.seed(),
                                key.type(),
                                key.size(),
                                key.algorithmVersion() + "/changed-plan"),
                        "ordered-state-ids"));
        assertNotEquals(first, GroundBaseDisk.file(root, key, "reordered-state-ids"));
    }
}
