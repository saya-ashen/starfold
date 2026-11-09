package dev.starfold.chart;

import java.util.Map;

/** Player-owned persistence values only; not catalog/save-format or world-clock state. */
public record GasPlayerExposureValues(int graceTicks, int safeTicks, int attemptTicks) {
    public static final int GRACE_TICKS = 100, RECOVERY_TICKS = 100, ATTEMPT_TICKS = 20;
    public static final String GRACE_KEY = "grace_ticks";
    public static final String SAFE_KEY = "safe_ticks";
    public static final String ATTEMPT_KEY = "attempt_ticks";

    public GasPlayerExposureValues {
        if (graceTicks < 0 || graceTicks > GRACE_TICKS
                || safeTicks < 0 || safeTicks > RECOVERY_TICKS
                || attemptTicks < 0 || attemptTicks > ATTEMPT_TICKS
                || graceTicks > 0 && attemptTicks != 0
                || safeTicks == RECOVERY_TICKS && (graceTicks != GRACE_TICKS || attemptTicks != 0))
            throw new IllegalArgumentException("Personal gas timers");
    }

    public static GasPlayerExposureValues initial() {
        return new GasPlayerExposureValues(GRACE_TICKS, 0, 0);
    }

    /**
     * Absent compound/empty fields initialize a new player with grace. A present malformed or
     * partial record fails closed without throwing or granting new grace. Adapters must retain
     * wrong types as invalid values (not silently coerce them to integers or drop the whole record).
     */
    public static GasPlayerExposureValues fromFields(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) return initial();
        if (fields.get(GRACE_KEY) instanceof Integer grace
                && fields.get(SAFE_KEY) instanceof Integer safe
                && fields.get(ATTEMPT_KEY) instanceof Integer attempt) {
            try {
                return new GasPlayerExposureValues(grace, safe, attempt);
            } catch (IllegalArgumentException invalidTimers) {
                // Corrupt persistence cannot become an infinite source of fresh grace.
            }
        }
        return new GasPlayerExposureValues(0, 0, 0);
    }

    public Map<String, Integer> toFields() {
        return Map.of(GRACE_KEY, graceTicks, SAFE_KEY, safeTicks, ATTEMPT_KEY, attemptTicks);
    }
}
