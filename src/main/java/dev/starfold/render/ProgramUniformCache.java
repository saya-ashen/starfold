package dev.starfold.render;

import java.util.*;
import java.util.function.IntSupplier;

/** Locations belong to a linked GL program, not to the long-lived Java shader wrapper. */
public final class ProgramUniformCache<K> {
    private record Entry(int program, Map<String, Integer> locations) {}

    private final Map<K, Entry> entries = new WeakHashMap<>();

    public int location(K shader, int program, String name, IntSupplier lookup) {
        var entry = entries.get(shader);
        if (entry == null || entry.program() != program) {
            entry = new Entry(program, new HashMap<>());
            entries.put(shader, entry);
        }
        return entry.locations().computeIfAbsent(name, n -> lookup.getAsInt());
    }

    /** Required even when a relink reuses the same numeric program ID. */
    public void invalidate(K shader) {
        entries.remove(shader);
    }
}
