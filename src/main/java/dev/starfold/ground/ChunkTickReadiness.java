package dev.starfold.ground;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.probe.ArrivalProbe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

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

    // Seam chunks whose generation leftovers wait for the chunks across the seam, per level.
    private static final Map<ServerLevel, Map<Long, LevelChunk>> HELD = new WeakHashMap<>();

    /**
     * Vanilla post-processes a new chunk (its generated fluids and shape updates) once its storage
     * neighbours are FULL. Those reads cross a seam into chunks vanilla does not wait for, and an
     * absent one is then generated on the server thread; a seam chunk waits instead, like its
     * block ticks. True when the chunk is held.
     */
    public static boolean holdPostProcess(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return false;
        long pos = chunk.getPos().toLong();
        if (ready(level, pos)) return false;
        HELD.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(pos, chunk);
        return true;
    }

    /** Post-processes the held chunks that became ready; drops those that were unloaded. */
    public static void postProcessHeld(ServerLevel level) {
        var held = HELD.get(level);
        if (held == null || held.isEmpty()) return;
        release(
                held,
                (pos, chunk) ->
                        level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos))
                                == chunk,
                pos -> ready(level, pos),
                chunk -> postProcessSent(level, chunk));
    }

    /**
     * Vanilla post-processes before a chunk is sent and writes shape updates without telling
     * clients; a held chunk was already sent, so its touched blocks and block entities are resent.
     */
    private static void postProcessSent(ServerLevel level, LevelChunk chunk) {
        var touched = chunk.getBlockEntitiesPos();
        var lists = chunk.getPostProcessing();
        for (int i = 0; i < lists.length; i++)
            if (lists[i] != null)
                for (short packed : lists[i])
                    touched.add(
                            ProtoChunk.unpackOffsetCoordinates(
                                    packed, chunk.getSectionYFromSectionIndex(i), chunk.getPos()));
        chunk.postProcessGeneration();
        for (var pos : touched) level.getChunkSource().blockChanged(pos);
    }

    /** Pure rule: entries no longer loaded are dropped; ready ones leave before any of them runs. */
    static <T> void release(
            Map<Long, T> held, BiPredicate<Long, T> loaded, LongPredicate ready, Consumer<T> run) {
        var due = new ArrayList<T>();
        for (var it = held.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!loaded.test(entry.getKey(), entry.getValue())) it.remove();
            else if (ready.test(entry.getKey())) {
                due.add(entry.getValue());
                it.remove();
            }
        }
        due.forEach(run);
    }

    public static void clear() {
        HELD.clear();
    }

    private ChunkTickReadiness() {}
}
