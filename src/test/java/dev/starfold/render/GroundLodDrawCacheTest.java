package dev.starfold.render;

import java.util.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundLodDrawCacheTest {
    private static GroundTerrainMeshes.Mesh mesh(RenderType layer, int first, int vertices) {
        return new GroundTerrainMeshes.Mesh(0, Map.of(),
                Map.of(layer, new GroundLodArena.Range(null, first, vertices)),
                vertices * 12L, null, null, true, new AABB(0, 0, 0, 16, 16, 16));
    }

    @Test void cachedDrawsEqualFreshSelectionAfterPublicationFadeAndCompaction() {
        var cache = new GroundTerrainMeshes.LodDrawCache();
        var groups = new LinkedHashMap<Integer, GroundLodGroup<GroundTerrainMeshes.LodTile>>();
        var group = new GroundLodGroup<GroundTerrainMeshes.LodTile>();
        groups.put(0, group);
        var layer = RenderType.solid();
        var mesh = mesh(layer, 0, 8);
        var tile = new GroundTerrainMeshes.LodTile(mesh, null, 4, 0, 0, 0);
        group.update(0, tile);
        cache.invalidate(group);
        assertFresh(cache, groups, false);
        long epoch = cache.epoch;
        assertFalse(cache.prepare(groups, false, false, (f, b) -> true));
        assertEquals(epoch, cache.epoch);

        var replacement = mesh(layer, 16, 12);
        var fading = new GroundTerrainMeshes.LodTile(replacement, mesh, 4, System.nanoTime(), 0, 0);
        group.update(0, fading);
        cache.invalidate(group);
        assertFresh(cache, groups, true);
        epoch = cache.epoch;
        assertTrue(cache.prepare(groups, false, true, (f, b) -> true));
        assertTrue(cache.epoch > epoch);
        assertSame(fading, cache.faces.getFirst().get(layer).getFirst().tile());

        fading.previous = null;
        group.update(0, fading);
        cache.invalidate(group);
        assertFresh(cache, groups, false);
        group.layers.put(layer, replacement.lodLayers.get(layer));
        group.compact = true;
        group.bounds = replacement.bounds;
        cache.invalidate(group);
        assertFresh(cache, groups, false);
        assertNull(cache.faces.getFirst().get(layer).getFirst().tile());
        assertEquals(replacement.lodLayers.get(layer), cache.faces.getFirst().get(layer).getFirst().range());

        // Ground-level visibility must be reevaluated even with unchanged mesh topology.
        assertTrue(cache.prepare(groups, true, false, (f, b) -> false));
        assertTrue(cache.faces.getFirst().get(layer).isEmpty());
        assertTrue(cache.prepare(groups, true, false, (f, b) -> true));
        assertEquals(1, cache.faces.getFirst().get(layer).size());
        group.update(0, null);
        groups.clear();
        cache.invalidate(group);
        assertFresh(cache, groups, false);
        assertTrue(cache.faces.getFirst().get(layer).isEmpty());
        cache.clear();
        assertTrue(cache.dirty);
        assertTrue(cache.parts.isEmpty());
    }

    private static void assertFresh(GroundTerrainMeshes.LodDrawCache cache,
            Map<Integer, GroundLodGroup<GroundTerrainMeshes.LodTile>> groups, boolean fading) {
        cache.prepare(groups, false, fading, (f, b) -> true);
        var expected = new GroundTerrainMeshes.LodDrawCache();
        expected.prepare(groups, false, fading, (f, b) -> true);
        for (int face = 0; face < 6; face++) {
            var layers = new HashSet<>(expected.faces.get(face).keySet());
            layers.addAll(cache.faces.get(face).keySet());
            for (var layer : layers)
                assertEquals(expected.faces.get(face).getOrDefault(layer, List.of()),
                        cache.faces.get(face).getOrDefault(layer, List.of()));
        }
    }
}
