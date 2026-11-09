package dev.starfold.chart;

import java.util.Objects;

/** Immutable online-tick machine. Persist only values; never subtract an absolute clock delta. */
public record GasPlayerExposureState(GasPlayerExposureValues values, long lastTick) {
    public static final float DAMAGE = 4;

    /** ACTIVE means online, alive survival/adventure; EXEMPT means creative/spectator. */
    public enum Participation { ACTIVE, EXEMPT, DEAD }

    /** Commit state before attempting ordinary hurt; cancellation still consumes the attempt. */
    public record Transition(
            GasPlayerExposureState state, GasPlayerExposure.Sample sample, boolean damageAttempt) {}

    public GasPlayerExposureState {
        Objects.requireNonNull(values);
        if (lastTick < -1) throw new IllegalArgumentException("Personal gas tick");
    }

    /** New login/server session: restore values unchanged, rebase only the transient tick guard. */
    public static GasPlayerExposureState restored(GasPlayerExposureValues values) {
        return new GasPlayerExposureState(values, -1);
    }

    /**
     * Call once per online player tick with a nonnegative, server-session monotonic tick token.
     * Duplicate/backward tokens do nothing, including no second hurt request. A forward jump
     * processes ONE observation, never skipped/offline time. Do not rebase on mode/respawn/region
     * changes: carry the whole state through those changes and copy values through player saves.
     *
     * The first 100 dangerous observations consume grace without hurt. Observation 101 attempts
     * hurt and sets attemptTicks=20; the next 19 dangerous ticks only decrement, the 20th attempts
     * again. Safe/unknown/dead/exempt ticks never advance this dangerous-tick countdown. Exit stops
     * hurt immediately; 100 consecutive confirmed-safe ACTIVE observations alone rearm grace.
     * Unknown/dead/exempt interrupt recovery but freeze grace and attempt timers. Offline means
     * no call (all values freeze). Sleep is ACTIVE. Caller must not call for offline players.
     */
    public Transition tick(
            long tick, GasPlayerExposure.Sample sample, Participation participation) {
        Objects.requireNonNull(sample);
        Objects.requireNonNull(participation);
        if (tick < 0) throw new IllegalArgumentException("Personal gas tick");
        if (tick <= lastTick) return new Transition(this, sample, false);
        int grace = values.graceTicks();
        int safe = 0;
        int attempt = values.attemptTicks();
        boolean hurt = false;
        if (participation == Participation.ACTIVE) {
            switch (sample.condition()) {
                case SAFE -> {
                    safe = Math.min(GasPlayerExposureValues.RECOVERY_TICKS, values.safeTicks() + 1);
                    if (safe == GasPlayerExposureValues.RECOVERY_TICKS) {
                        grace = GasPlayerExposureValues.GRACE_TICKS;
                        attempt = 0;
                    }
                }
                case DANGER -> {
                    if (grace > 0) grace--;
                    else if (attempt > 1) attempt--;
                    else {
                        hurt = true;
                        attempt = GasPlayerExposureValues.ATTEMPT_TICKS;
                    }
                }
                case UNKNOWN -> { /* No damage and no evidence for recovery. */ }
            }
        }
        var next = new GasPlayerExposureState(new GasPlayerExposureValues(grace, safe, attempt), tick);
        return new Transition(next, sample, hurt);
    }
}
