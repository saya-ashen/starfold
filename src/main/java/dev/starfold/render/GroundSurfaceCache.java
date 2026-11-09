package dev.starfold.render;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/** Coarse columns survive eviction of optional 3-D detail. Mutation stays on the owner thread. */
final class GroundSurfaceCache extends LinkedHashMap<Long, GroundSurface> {
    static final long VOXEL_BYTES = 16L * 1024 * 1024;
    private final LongConsumer invalidated;
    private final boolean filterPlantsOnEviction;
    private long bytes;

    GroundSurfaceCache() {
        this(k -> {});
    }

    GroundSurfaceCache(LongConsumer invalidated) {
        this(invalidated, false);
    }

    GroundSurfaceCache(LongConsumer invalidated, boolean filterPlantsOnEviction) {
        this.invalidated = invalidated;
        this.filterPlantsOnEviction = filterPlantsOnEviction;
    }

    static int bytes(GroundSurface surface) {
        return surface == null || surface.voxels() == null ? 0 : surface.voxels().memoryBytes();
    }

    @Override
    public GroundSurface put(Long key, GroundSurface value) {
        var previous = super.put(key, value);
        bytes += bytes(value) - bytes(previous);
        for (var entry : entrySet()) {
            if (bytes <= VOXEL_BYTES) break;
            var image = entry.getValue();
            if (image.voxels() == null) continue;
            bytes -= bytes(image);
            entry.setValue(
                    filterPlantsOnEviction
                            ? image.surfaceOnly()
                            : new GroundSurface(
                                    image.revision(), image.heights(), image.states()));
            invalidated.accept(entry.getKey());
        }
        return previous;
    }

    @Override
    public void putAll(Map<? extends Long, ? extends GroundSurface> images) {
        images.forEach(this::put);
    }

    @Override
    public GroundSurface remove(Object key) {
        var previous = super.remove(key);
        bytes -= bytes(previous);
        return previous;
    }

    @Override
    public void clear() {
        super.clear();
        bytes = 0;
    }

    long voxelBytes() {
        return bytes;
    }
}
