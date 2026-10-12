package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.Test;

import java.util.*;

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
    void residentDeparturesDoNotStarveBackgroundCoverage() {
        var work = new GroundViewPolicy.DirtyPatches();
        work.tile(0);
        assertEquals(0, work.poll());
        work.ensureTile(0);
        assertEquals(1, work.poll()); // no urgent promotion of the stale mesh
        assertEquals(383, work.count());
    }

    @Test
    void coverageChangesInvalidateOnlyTheAffectedInProgressPatch() {
        var before = new BitSet();
        before.set(0);
        var after = (BitSet) before.clone();
        after.set(16);
        assertFalse(GroundViewPolicy.coverageChanged(before, after, 0));
        assertTrue(GroundViewPolicy.coverageChanged(before, after, 1));
        assertFalse(GroundViewPolicy.coverageChanged(after, after, 1));
        assertEquals(1, before.cardinality());
    }

    @Test
    void generatorHeightsMemoizeWithoutGrowingAcrossTheWholePlanet() {
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);
        var cache = new PlanetHeightCache(terrain);
        assertEquals(terrain.groundY(Face.TOP, 8, 9), cache.ground(Face.TOP, 8, 9));
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

    @Test
    void lowerReliefRetainsOceansAndDryLandAndGentleAdjacentColumns() {
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);
        int min = 999, max = -999;
        for (var face : Face.values())
            for (int u = 16; u < 2047; u += 32)
                for (int v = 16; v < 2047; v += 32) {
                    int h = terrain.groundY(face, u, v);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                    assertTrue(h >= 103 && h <= 175);
                    // The ground an anomaly wrings around a vertex is deliberately rough.
                    if (Anomaly.distance(PlanetTerrain.direction(face, u + .5, v + .5))
                            < Anomaly.PLATEAU_OUTER) continue;
                    assertTrue(Math.abs(h - terrain.groundY(face, u + 1, v)) <= 1);
                    assertTrue(Math.abs(h - terrain.groundY(face, u, v + 1)) <= 1);
                }
        assertTrue(min < 118);
        assertTrue(max > 148);
        assertEquals(2, PlanetTerrain.VERSION);
    }
}
