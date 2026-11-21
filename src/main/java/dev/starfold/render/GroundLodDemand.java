package dev.starfold.render;

import java.util.LinkedHashMap;
import java.util.TreeMap;

/** Bounded canonical demand, round-robin across spatial regions and FIFO within each region. */
final class GroundLodDemand {
    static final int KEYS = 6 * 128 * 128;
    private static final int REGIONS = 6 * 8 * 8;

    record Entry(long key, int target) {}

    // Only nonempty 16x16-tile regions allocate a bucket. Scores/targets never order entries.
    private final TreeMap<Integer, LinkedHashMap<Long, Integer>> regions = new TreeMap<>();
    private int cursor, size;

    static void checkKey(long key) {
        if (key < 0 || key >= KEYS) throw new IllegalArgumentException("Canonical tile key");
    }

    private static int region(long key) {
        int x = (int) (key % 128) / 16, z = (int) (key / 128 % 128) / 16;
        int order = 0;
        // Reverse Morton digits: visit opposite quadrants before subdivisions of a quadrant.
        for (int bit = 0; bit < 3; bit++)
            order = (order << 2) | (x >> bit & 1) | ((z >> bit & 1) << 1);
        return order * 6 + (int) (key / 16384);
    }

    /** Updating a target preserves its place; at most one entry exists per canonical key. */
    void put(long key, int target) {
        checkKey(key);
        var bucket = regions.computeIfAbsent(region(key), ignored -> new LinkedHashMap<>());
        if (bucket.put(key, target) == null) size++;
    }

    boolean contains(long key) {
        checkKey(key);
        var bucket = regions.get(region(key));
        return bucket != null && bucket.containsKey(key);
    }

    boolean remove(long key) {
        checkKey(key);
        int region = region(key);
        var bucket = regions.get(region);
        if (bucket == null || bucket.remove(key) == null) return false;
        size--;
        if (bucket.isEmpty()) regions.remove(region);
        return true;
    }

    Entry poll() {
        if (regions.isEmpty()) return null;
        var region = regions.ceilingEntry(cursor);
        if (region == null) region = regions.firstEntry();
        int rank = region.getKey();
        var bucket = region.getValue();
        var entry = bucket.pollFirstEntry();
        size--;
        if (bucket.isEmpty()) regions.remove(rank);
        cursor = (rank + 1) % REGIONS;
        return new Entry(entry.getKey(), entry.getValue());
    }

    int size() {
        return size;
    }

    void clear() {
        regions.clear();
        cursor = size = 0;
    }
}
