package dev.starfold.ship;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recent successful writes only: no HP, accumulated exposure, hull references, or world access. */
final class GasDamageCooldown {
    static final int MAX_INTERVAL = 100, MAX_WRITES_PER_TICK = 2;
    static final int MAX_RECENT = MAX_INTERVAL * MAX_WRITES_PER_TICK;

    record Stamp(UUID hull, long tick) {
        Stamp {
            if (hull == null || tick < 0) throw new IllegalArgumentException("Gas damage stamp");
        }
    }

    record Snapshot(long watermark, List<Stamp> stamps) {
        Snapshot {
            if (watermark < 0 || stamps == null || stamps.size() > MAX_RECENT)
                throw new IllegalArgumentException("Gas cooldown snapshot");
            stamps = List.copyOf(stamps);
        }
    }

    private final Map<UUID, Long> successes = new HashMap<>();
    // Split pieces wait out their source's last write. Transient: never part of a snapshot, so the
    // saved per-tick write bound holds; a reload inside one interval only loses this wait.
    private final Map<UUID, Long> inherited = new HashMap<>();
    private long watermark;
    private int written;

    GasDamageCooldown() {}

    GasDamageCooldown(Snapshot snapshot) {
        watermark = snapshot.watermark();
        var perTick = new HashMap<Long, Integer>();
        for (var stamp : snapshot.stamps()) {
            if (stamp.tick() > watermark
                    || watermark - stamp.tick() >= MAX_INTERVAL
                    || successes.putIfAbsent(stamp.hull(), stamp.tick()) != null
                    || perTick.merge(stamp.tick(), 1, Integer::sum) > MAX_WRITES_PER_TICK)
                throw new IllegalArgumentException("Invalid recent gas cooldown");
        }
        written = perTick.getOrDefault(watermark, 0);
    }

    private void advance(long tick) {
        if (tick < 0) throw new IllegalArgumentException("Gas damage clock");
        // Rollback cannot rearm damage or reset this tick's global write budget.
        if (tick <= watermark) return;
        watermark = tick;
        written = 0;
        successes.values().removeIf(last -> watermark - last >= MAX_INTERVAL);
        inherited.values().removeIf(last -> watermark - last >= MAX_INTERVAL);
    }

    private Long last(UUID hull) {
        Long own = successes.get(hull), from = inherited.get(hull);
        if (own == null) return from;
        if (from == null) return own;
        return Math.max(own, from);
    }

    /** Called when Sable splits {@code piece} off {@code source}; chains through earlier splits. */
    void inherit(UUID piece, UUID source) {
        if (piece == null || source == null) throw new IllegalArgumentException("Gas damage split");
        Long last = last(source);
        if (last != null) inherited.merge(piece, last, Math::max);
    }

    boolean ready(UUID hull, long tick, int interval) {
        if (hull == null || interval < 0 || interval > MAX_INTERVAL)
            throw new IllegalArgumentException("Gas damage cadence");
        advance(tick);
        if (interval == 0 || written >= MAX_WRITES_PER_TICK) return false;
        Long last = last(hull);
        return last == null || watermark - last >= interval;
    }

    /** Call only after the normal block write actually succeeded. Failed attempts leave it due. */
    void succeeded(UUID hull, long tick, int interval) {
        if (!ready(hull, tick, interval)) throw new IllegalStateException("Gas damage not due");
        successes.put(hull, watermark);
        written++;
        if (successes.size() > MAX_RECENT) throw new IllegalStateException("Gas cooldown capacity");
    }

    Snapshot snapshot() {
        return snapshot(watermark);
    }

    Snapshot snapshot(long tick) {
        advance(tick);
        var stamps =
                successes.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new Stamp(e.getKey(), e.getValue()))
                        .toList();
        return new Snapshot(watermark, stamps);
    }
}
