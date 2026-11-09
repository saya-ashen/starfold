package dev.starfold.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** One face's 4×4 tiles, in canonical z/x order for contiguous layer copies. */
final class GroundLodGroup<T> {
    final Map<Long, T> members = new TreeMap<>();
    final Map<RenderType, GroundLodArena.Range> layers = new LinkedHashMap<>();
    AABB bounds;
    boolean compact;

    static int key(long tile) {
        return (int)(tile / 16384 * 1024 + tile / 128 % 128 / 4 * 32 + tile % 128 / 4);
    }

    void update(long key, T member) {
        if (member == null) members.remove(key);
        else members.put(key, member);
        // Blocks are only draw aliases: each mesh still owns and frees its sub-ranges.
        layers.clear();
        bounds = null;
        compact = false;
    }
}
