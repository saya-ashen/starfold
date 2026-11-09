package dev.starfold.render;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundBaseCacheTest {
    @Test void failedBuildRetriesOnlyToTheBoundAndPublishesItsFinalState() {
        PlanetTerrainTest.registry();
        var entry = new GroundBaseCache.Entry(new PlanetTerrain(42));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        entry.build(() -> {
            if (calls.incrementAndGet() < 2) throw new IllegalStateException("transient");
            return null;
        });
        assertEquals(2, entry.attempts);
        assertEquals(GroundBaseCache.Entry.State.READY, entry.state);
        assertNull(entry.failure);
        entry.build(() -> { throw new IllegalStateException("permanent"); });
        assertEquals(GroundBaseCache.Entry.MAX_ATTEMPTS, entry.attempts);
        assertEquals(GroundBaseCache.Entry.State.FAILED, entry.state);
        assertEquals("permanent", entry.failure.getMessage());
        entry.close();
        entry.build(() -> fail("closed entries cannot restart"));
        assertEquals(GroundBaseCache.Entry.State.CLOSED, entry.state);
    }
    @Test void chartResetKeepsDeterministicBaseUntilDisconnect() throws Exception {
        PlanetTerrainTest.registry();
        var terrain = new PlanetTerrain(42);
        var entry = new GroundBaseCache.Entry(terrain);
        var field = GroundBaseCache.class.getDeclaredField("planets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var planets = (java.util.Map<dev.starfold.worldgen.surface.SurfaceKey, GroundBaseCache.Entry>) field.get(null);
        planets.put(dev.starfold.worldgen.surface.SurfaceKey.of(terrain), entry);
        try {
            GroundViewClient.clear();
            assertSame(entry, GroundBaseCache.get(terrain, null));
            assertNotNull(entry.cache);
            GroundBaseCache.clear();
            assertTrue(planets.isEmpty());
            assertNull(entry.cache);
        } finally { GroundBaseCache.clear(); }
    }

    @Test void revisitingFineColumnsReusesBoundedPackedData() {
        PlanetTerrainTest.registry();
        var entry = new GroundBaseCache.Entry(new PlanetTerrain(42));
        var first = entry.voxel(Face.TOP,100,100,1);
        var data = entry.fine.values().iterator().next();
        assertEquals(first,entry.voxel(Face.TOP,100,100,1));
        assertSame(data,entry.fine.values().iterator().next());
        assertEquals(80+8L*data.length,entry.fineBytes());
        entry.finePayload = GroundBaseCache.Entry.FINE_LIMIT;
        entry.voxel(Face.TOP,101,100,1);
        assertFalse(entry.fine.containsValue(data));
        assertTrue(entry.fineBytes() <= GroundBaseCache.Entry.FINE_LIMIT);
        entry.close();
    }
}
