package dev.starfold.render;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongConsumer;

/** Coarse columns survive eviction of optional 3-D detail. Mutation stays on the owner thread. */
final class GroundSurfaceCache extends LinkedHashMap<Long, GroundSurface> {
    static final long VOXEL_BYTES = 16L * 1024 * 1024;
    private final LongConsumer invalidated;
    private final boolean filterPlantsOnEviction;
    // Keep an evicted key's original position when its optional detail is restored.
    private final Map<Long, Long> insertionOrder = new HashMap<>();
    private final TreeMap<Long, Long> voxelOrder = new TreeMap<>();
    private long nextOrder;
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
        var order = insertionOrder.computeIfAbsent(key, k -> nextOrder++);
        bytes += bytes(value) - bytes(previous);
        if (value != null && value.voxels() != null) voxelOrder.put(order, key);
        else voxelOrder.remove(order);
        while (bytes > VOXEL_BYTES) {
            var oldest = voxelOrder.pollFirstEntry();
            var image = super.get(oldest.getValue());
            bytes -= bytes(image);
            super.put(
                    oldest.getValue(),
                    filterPlantsOnEviction
                            ? image.surfaceOnly()
                            : new GroundSurface(image.revision(), image.heights(), image.states()));
            invalidated.accept(oldest.getValue());
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
        var order = insertionOrder.remove(key);
        if (order != null) voxelOrder.remove(order);
        return previous;
    }

    @Override
    public void clear() {
        super.clear();
        insertionOrder.clear();
        voxelOrder.clear();
        nextOrder = 0;
        bytes = 0;
    }

    long voxelBytes() {
        return bytes;
    }
}
