package dev.starfold.ground;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.probe.ArrivalProbe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Extend vanilla's storage-neighbour guarantee to the owners behind a face's halo. */
public final class ChunkTickReadiness {
    @FunctionalInterface
    public interface NeighbourReady {
        boolean ready(int x, int z);
    }

    static boolean edge(long key, int width) {
        int u = (int) (key % 128), v = (int) (key / 128 % 128);
        return key >= 0 && (u == 0 || v == 0 || u == width - 1 || v == width - 1);
    }

    /** Pure rule. Interior and noncanonical chunks never invoke the neighbour lookup. */
    static boolean ready(long key, int width, int x, int z, NeighbourReady readable) {
        if (!edge(key, width)) return true;
        int u = (int) (key % 128), v = (int) (key / 128 % 128);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((u + dx < 0 || u + dx >= width || v + dz < 0 || v + dz >= width)
                        && !readable.ready(x + dx, z + dz)) return false;
        return true;
    }

    public static boolean ready(ServerLevel level, long chunk) {
        if (!PlanetLevels.is(level)) return true;
        var access = PlanetAccess.of(level);
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        long key = access.key(x, z);
        if (key < 0) return true;
        int width = PlanetShape.of((int) (key / PlanetStorage.CHUNKS_PER_PLANET)).chunks();
        // No positions, columns, closures or probe-map lookups on the interior path.
        if (!edge(key, width)) return true;
        boolean ready =
                ready(
                        key,
                        width,
                        x,
                        z,
                        (cx, cz) ->
                                ChunkViews.ready(
                                        level,
                                        ChunkViews.columns(access, cx, cz),
                                        owner ->
                                                level.getChunkSource()
                                                                .getChunkNow(
                                                                        ChunkPos.getX(owner),
                                                                        ChunkPos.getZ(owner))
                                                        != null));
        if (ArrivalProbe.ENABLED) ArrivalProbe.seamHeld(level, chunk, ready);
        return ready;
    }

    private ChunkTickReadiness() {}
}
