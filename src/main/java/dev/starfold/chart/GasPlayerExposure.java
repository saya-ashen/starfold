package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Personal, zero-padding gas sampling; no ship identity, world access or damage side effects. */
public final class GasPlayerExposure {
    public enum Condition { SAFE, DANGER, UNKNOWN }

    public enum Risk { NONE, PRESSURE, STRUCTURAL, NEAR_LIMIT, SURVIVAL_LIMIT, UNKNOWN }

    public record Sample(Condition condition, Optional<GasPressure.Reading> reading) {
        public Sample {
            Objects.requireNonNull(condition);
            Objects.requireNonNull(reading);
            if (condition == Condition.UNKNOWN && reading.isPresent()
                    || condition == Condition.DANGER && reading.isEmpty())
                throw new IllegalArgumentException("Personal gas sample");
        }

        public Risk risk() {
            if (condition == Condition.UNKNOWN) return Risk.UNKNOWN;
            if (condition == Condition.DANGER) return Risk.SURVIVAL_LIMIT;
            double pressure = reading.map(GasPressure.Reading::pressure).orElse(0.0);
            if (pressure >= .8) return Risk.NEAR_LIMIT;
            if (pressure >= .5) return Risk.STRUCTURAL;
            return pressure > 0 ? Risk.PRESSURE : Risk.NONE;
        }
    }

    /** For a positively identified non-gas environment, not a missing space frame. */
    public static Sample confirmedSafe() {
        return new Sample(Condition.SAFE, Optional.empty());
    }

    public static Sample unknown() {
        return new Sample(Condition.UNKNOWN, Optional.empty());
    }

    /**
     * Layers and the player's actual server-side body center must already share the same-time
     * universe frame. Missing/invalid geometry is unknown, never evidence of safety. An empty
     * valid field is safe. Hull padding, cabin occupancy and game mode have no sampling role.
     */
    public static Sample sample(List<GasPressure.Layer> layers, Point bodyCenter) {
        if (layers == null || bodyCenter == null) return unknown();
        try {
            // Reuse the field's capacity/identity validation, but retain policies for exact depth.
            new GasPressure.Field(layers);
            GroundEphemeris.point(bodyCenter);
            GasPressure.Reading worst = null;
            boolean danger = false;
            for (var layer : layers) {
                var next = layer.read(bodyCenter, 0);
                danger |= next.depth() >= layer.policy().maxDepth();
                if (worst == null
                        || next.pressure() > worst.pressure()
                        || next.pressure() == worst.pressure()
                                && next.bodyIndex() < worst.bodyIndex()) worst = next;
            }
            return new Sample(danger ? Condition.DANGER : Condition.SAFE, Optional.ofNullable(worst));
        } catch (IllegalArgumentException | NullPointerException invalidGeometry) {
            return unknown();
        }
    }

    private GasPlayerExposure() {}
}
