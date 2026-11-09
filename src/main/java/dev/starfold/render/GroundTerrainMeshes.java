package dev.starfold.render;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Resident terrain buffers, cohort slots, LOD arenas and their derived draw cache. */
final class GroundTerrainMeshes {
    static final class Mesh {
        final long revision, bytes;
        final Map<RenderType, VertexBuffer> layers;
        final Map<RenderType, GroundLodArena.Range> lodLayers;
        final GroundSurface surface;
        final GroundCoverSnapshot cover;
        final boolean compact;
        final AABB bounds;

        Mesh(long revision, Map<RenderType, VertexBuffer> layers,
             Map<RenderType, GroundLodArena.Range> lodLayers, long bytes,
             GroundSurface surface, GroundCoverSnapshot cover, boolean compact, AABB bounds) {
            this.revision = revision;
            this.layers = layers;
            this.lodLayers = lodLayers;
            this.bytes = bytes;
            this.surface = surface;
            this.cover = cover;
            this.compact = compact;
            this.bounds = bounds;
        }
        void close() {
            for (var buffer : layers.values()) buffer.close();
            for (var range : lodLayers.values()) range.close();
        }
    }

    static final class LodTile {
        Mesh current, previous;
        final int requested, mipPatch;
        long committed, mipVersion;
        boolean retiring, fadeQueued, dirty;
        GroundSurface observed;

        LodTile(Mesh mesh, Mesh previous, int requested, long now, long key, long mipVersion) {
            this.current = mesh;
            mipPatch = GroundViewPolicy.patch(key);
            this.mipVersion = mipVersion;
            observed = mesh.surface;
            this.previous = previous;
            this.requested = requested;
            committed = now;
        }
    }

    static final List<Map<RenderType, GroundLodArena>> lodArenas =
            List.of(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    record LodDraw(LodTile tile, GroundLodArena.Range range) {}

    private record LodPart(AABB bounds, Map<RenderType, LodDraw> layers) {}
    /** {@code bounds} encloses every part, so a hidden group skips its tiles; null when it cannot be culled. */
    private record LodParts(AABB bounds, List<LodPart> parts) {}
    // Invalidated with mesh/fade topology, never with the camera.
    static final class LodDrawCache {
        final Map<GroundLodGroup<LodTile>, LodParts> parts = new IdentityHashMap<>();
        final List<Map<RenderType, List<LodDraw>>> faces =
                List.of(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                        new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        boolean dirty = true;
        long epoch;
        int visited, groups, compact;

        void invalidate(GroundLodGroup<LodTile> group) {
            parts.remove(group);
            dirty = true;
        }

        void clear() {
            parts.clear();
            faces.forEach(Map::clear);
            dirty = true;
        }

        boolean prepare(Map<Integer, GroundLodGroup<LodTile>> source, boolean culling,
                boolean fading, java.util.function.BiPredicate<Integer, AABB> visible) {
            if (!culling && !dirty && !fading) return false;
            dirty = culling;
            epoch++;
            visited = groups = compact = 0;
            for (var face : faces) for (var draws : face.values()) draws.clear();
            for (var e : source.entrySet()) {
                visited++;
                groups++;
                int face = e.getKey() / 1024;
                var group = e.getValue();
                var layers = faces.get(face);
                if (group.compact) compact++;
                var cached = parts.computeIfAbsent(group, LodDrawCache::buildLodParts);
                if (cached.bounds != null && !visible.test(face, cached.bounds)) {
                    visited += cached.parts.size();
                    continue;
                }
                for (var part : cached.parts) {
                    visited++;
                    if (!visible.test(face, part.bounds)) continue;
                    for (var layer : part.layers.entrySet())
                        layers.computeIfAbsent(layer.getKey(), l -> new ArrayList<>()).add(layer.getValue());
                }
            }
            return true;
        }

        private static LodParts buildLodParts(GroundLodGroup<LodTile> group) {
            var parts = new ArrayList<LodPart>();
            if (group.compact) {
                var layers = new LinkedHashMap<RenderType, LodDraw>();
                group.layers.forEach((layer, range) -> layers.put(layer, new LodDraw(null, range)));
                parts.add(new LodPart(group.bounds, layers));
                return new LodParts(null, parts);
            }
            AABB union = null;
            boolean unbounded = false;
            for (var tile : group.members.values()) {
                var bounds = tile.current.bounds;
                if (tile.previous != null && tile.previous.bounds != null)
                    bounds = bounds == null ? tile.previous.bounds : bounds.minmax(tile.previous.bounds);
                var layers = new LinkedHashMap<RenderType, LodDraw>();
                tile.current.lodLayers.forEach((layer, range) -> layers.put(layer, new LodDraw(tile, range)));
                if (tile.previous != null)
                    for (var layer : tile.previous.lodLayers.keySet())
                        layers.putIfAbsent(layer, new LodDraw(tile, null));
                parts.add(new LodPart(bounds, layers));
                if (bounds == null) unbounded = true;
                else union = union == null ? bounds : union.minmax(bounds);
            }
            return new LodParts(unbounded || parts.size() < 2 ? null : union, parts);
        }
    }
    static final LodDrawCache lodDrawCache = new LodDrawCache();

    // Current-camera visibility references the cached ranges.
    static final Map<Long, LodTile> lodTiles = new LinkedHashMap<>();
    static final Map<Integer, GroundLodGroup<LodTile>> lodGroups = new LinkedHashMap<>();
    static final LinkedHashSet<GroundLodGroup<LodTile>> lodCompactions = new LinkedHashSet<>();

    static final Mesh[] masks = new Mesh[12],
            stitches = new Mesh[6],
            parents = new Mesh[GroundViewPolicy.PATCH_COUNT],
            patches = new Mesh[GroundViewPolicy.PATCH_COUNT],
            staging = new Mesh[GroundViewPolicy.PATCH_COUNT];


    static void closeSlots(Mesh[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) slots[i].close();
            slots[i] = null;
        }
    }

    static void clearLodBuffers() {
        for (var tile : lodTiles.values()) {
            tile.current.close();
            if (tile.previous != null) tile.previous.close();
        }
        lodTiles.clear();
        lodGroups.clear();
        lodDrawCache.clear();
        lodCompactions.clear();
        for (var arenas : lodArenas) {
            for (var arena : arenas.values()) arena.close();
            arenas.clear();
        }
    }

    private GroundTerrainMeshes() {}
}
