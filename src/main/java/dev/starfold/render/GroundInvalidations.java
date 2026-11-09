package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Tile/edge invalidations survive coalescing until the renderer drains a ready session. */
final class GroundInvalidations {
    private final Set<Long> pending = new LinkedHashSet<>();

    void surface(PlanetShape shape, long key) {
        pending.add(key);
        for (long neighbour : GroundStreaming.neighbours(shape, key)) pending.add(neighbour);
        trim();
    }

    void edit(PlanetShape shape, long key, BlockPos pos) {
        pending.add(key);
        int x = pos.getX() & 15, z = pos.getZ() & 15;
        var neighbours = GroundStreaming.neighbours(shape, key);
        if (x == 0) pending.add(neighbours[0]);
        if (x == 15) pending.add(neighbours[1]);
        if (z == 0) pending.add(neighbours[2]);
        if (z == 15) pending.add(neighbours[3]);
        trim();
    }

    private void trim() {
        while (pending.size() > 512 * 5) pending.remove(pending.iterator().next());
    }

    List<Long> drain() {
        var result = List.copyOf(pending);
        clear();
        return result;
    }

    void clear() { pending.clear(); }
}
