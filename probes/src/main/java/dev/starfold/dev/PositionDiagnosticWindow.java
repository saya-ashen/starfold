package dev.starfold.dev;

import java.util.UUID;

/** One process-wide, non-renewable budget: reconnecting cannot extend the observation. */
final class PositionDiagnosticWindow {
    static final long DURATION_NANOS = 120_000_000_000L;
    static final int MAX_EVENTS = 8192;
    static final int MAX_STACKS = 32;
    static final int MAX_FRAMES = 20;

    private final UUID target;
    private long start;
    private int events;
    private int stacks;
    private Object connection;
    private int connectionId;
    private Cause current;

    record Cause(long id, long parent, int connection, Cause previous) {}

    PositionDiagnosticWindow(boolean enabled, String uuid) {
        UUID parsed = null;
        if (enabled && uuid != null) {
            try {
                var value = UUID.fromString(uuid);
                if (value.toString().equals(uuid)) parsed = value;
            } catch (IllegalArgumentException invalid) {
                // Fail closed; a malformed diagnostic option must not prevent server startup.
            }
        }
        target = parsed;
    }

    boolean enabled() {
        return target != null;
    }

    boolean matches(UUID uuid) {
        return target != null && target.equals(uuid);
    }

    long reserve(UUID uuid, long now) {
        if (!matches(uuid) || events == MAX_EVENTS) return 0;
        if (events == 0) start = now;
        if (now - start >= DURATION_NANOS) return 0;
        return ++events;
    }

    Cause begin(UUID uuid, long now, Object owner) {
        long id = reserve(uuid, now);
        if (id == 0) return null;
        if (connectionId == 0 || connection != owner) {
            connection = owner;
            connectionId++;
        }
        current = new Cause(id, current == null ? 0 : current.id(), connectionId, current);
        return current;
    }

    long end(UUID uuid, long now, Cause cause) {
        current = cause.previous();
        return reserve(uuid, now);
    }

    boolean stack(long event, boolean meaningful) {
        if (!enabled() || event == 0 || event != events || !meaningful || stacks == MAX_STACKS) return false;
        stacks++;
        return true;
    }
}
