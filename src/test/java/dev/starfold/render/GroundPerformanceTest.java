package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.Test;

/** Offline work bounds and cache regressions, not a GPU/FPS benchmark. */
class GroundPerformanceTest {
    @Test
    void unchangedSnapshotsReuseUntilLightRefreshAndEditsInvalidateImmediately() {
        assertFalse(GroundViewPolicy.captureNeeded(true, 7, 7, 99, 0));
        assertTrue(GroundViewPolicy.captureNeeded(true, 7, 7, 100, 0));
        assertTrue(GroundViewPolicy.captureNeeded(true, 8, 7, 1, 0));
        assertTrue(GroundViewPolicy.captureNeeded(false, 7, 7, 1, 0));
        assertTrue(GroundViewPolicy.captureNeeded(true, 7, 7, 0, 1));
    }

    @Test
    void dirtyTilesCoalesceIntoLocalPatchesAndDoNotRebuildAnEntireFace() {
        var work = new GroundViewPolicy.DirtyPatches();
        while (work.poll() >= 0) {}
        work.tile(0);
        work.tile(1);
        assertEquals(1, work.count());
        assertEquals(0, work.poll());
        assertEquals(-1, work.poll());
        for (int face = 0; face < 6; face++)
            assertEquals(face * 64 + 63, GroundViewPolicy.patch(face * 16384L + 16383));
    }

    @Test
    void generatorHeightsMemoizeWithoutGrowingAcrossTheWholePlanet() {
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);
        var cache = new PlanetHeightCache(terrain);
        assertEquals(terrain.surfaceY(Face.TOP, 8, 9), cache.ground(Face.TOP, 8, 9));
        cache.ground(Face.TOP, 8, 9);
        assertEquals(1, cache.samples());
        cache.node(Face.TOP, 0, 0);
        cache.node(Face.TOP, 0, 0);
        assertEquals(2, cache.samples());
        for (int u = 0; u < 2048; u += 16)
            for (int v = 0; v < 2048; v += 16) cache.ground(Face.TOP, u, v);
        assertEquals(128, cache.columns());
        for (int u = 0; u < 200; u++) for (int v = 0; v < 100; v++) cache.node(Face.TOP, u, v);
        assertEquals(16384, cache.nodes());
    }

}
