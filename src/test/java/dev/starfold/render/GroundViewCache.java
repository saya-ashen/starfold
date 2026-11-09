package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.storage.ChunkKeys;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.GroundTopology;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.LongConsumer;

/** Client derived views only. Detail eviction never erases a changed tile's coarse coverage. */
public final class GroundViewCache {
    public static final int DETAIL_LIMIT = 512;
    public static final long BYTE_LIMIT = 192L * 1024 * 1024;

    private final LinkedHashMap<Long, GroundChunkSnapshot> detail =
            new LinkedHashMap<>(16, .75f, true);
    private final Map<Long, Long> versions = new HashMap<>();
    private final Map<Long, String> fingerprints = new HashMap<>();
    private final Map<Long, GroundSurface> coarse = new HashMap<>();
    private final LongConsumer invalidate;
    private long bytes;
    private final java.util.function.Supplier<PlanetShape> shape;

    public GroundViewCache(LongConsumer invalidate) {
        this(() -> PlanetShape.STANDARD, invalidate);
    }

    /**
     * The view of whichever planet is being observed: its chunks are addressed by that planet's
     * shape.
     */
    public GroundViewCache(
            java.util.function.Supplier<PlanetShape> shape, LongConsumer invalidate) {
        this.shape = Objects.requireNonNull(shape);
        this.invalidate = Objects.requireNonNull(invalidate);
    }

    public PlanetShape shape() {
        return shape.get();
    }

    public boolean accept(GroundChunkSnapshot image) {
        long key = image.key();
        var revision = versions.get(key);
        if (revision != null && image.revision() <= revision) return false;
        boolean changed =
                !detail.containsKey(key) || !image.fingerprint().equals(fingerprints.get(key));
        var surface = surface(image);
        if (surface == null) coarse.remove(key);
        else coarse.put(key, surface);
        versions.put(key, image.revision());
        fingerprints.put(key, image.fingerprint());
        var old = detail.put(key, image);
        if (old != null) bytes -= old.memoryBytes();
        bytes += image.memoryBytes();
        trim();
        if (changed) invalidate.accept(key);
        return true;
    }

    /** Requesting an evicted same-revision detail is valid; accepting stale changes is not. */
    public boolean refill(GroundChunkSnapshot image) {
        var revision = versions.get(image.key());
        if (revision == null
                || image.revision() != revision
                || !image.fingerprint().equals(fingerprints.get(image.key()))
                || detail.containsKey(image.key())) return false;
        detail.put(image.key(), image);
        bytes += image.memoryBytes();
        trim();
        invalidate.accept(image.key());
        return true;
    }

    private boolean overLimit() {
        return detail.size() > DETAIL_LIMIT || bytes > BYTE_LIMIT;
    }

    private void trim() {
        var entries = detail.entrySet().iterator();
        while (overLimit() && entries.hasNext()) {
            var entry = entries.next();
            bytes -= entry.getValue().memoryBytes();
            entries.remove();
        }
    }

    public BlockState canonical(BlockPos canonical) {
        var c = GroundTopology.cell(canonical);
        if (!shape().topology().owned(c))
            throw new IllegalArgumentException("View query not canonical");
        long key = ChunkKeys.faceLocal(c.face().ordinal(), c.u() / 16, c.v() / 16);
        var chunk = detail.get(key);
        return chunk == null ? null : chunk.state(canonical);
    }

    public GroundChunkSnapshot image(long key) {
        return detail.get(key);
    }

    public GroundSurface surface(long key) {
        return coarse.get(key);
    }

    public long version(long key) {
        return versions.getOrDefault(key, -1L);
    }

    public boolean detailed(long key) {
        return detail.containsKey(key);
    }

    public void clear() {
        detail.clear();
        versions.clear();
        fingerprints.clear();
        coarse.clear();
        bytes = 0;
    }

    private static GroundSurface surface(GroundChunkSnapshot image) {
        var result = surfaceImage(image);
        int grass = Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        for (int i = 0; i < 256; i++)
            if (result.height(i & 15, i >> 4) != GroundCoordinates.FLOOR - 1
                    || result.stateId(i) != grass) return result;
        return null;
    }

    static GroundSurface surfaceImage(GroundChunkSnapshot image) { return GroundSurface.from(image); }
}
