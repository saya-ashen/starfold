package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Immutable current-time pressure field. No world access, damage permission or collision guarantee. */
public final class GasPressure {
    public record Layer(int bodyIndex, Point center, double radius, GasGiant policy) {
        public Layer {
            if (bodyIndex < 0 || policy == null)
                throw new IllegalArgumentException("Gas pressure identity");
            GroundEphemeris.point(center);
            policy.validateRadius(radius);
        }

        public Reading read(Point anchor, double padding) {
            GroundEphemeris.point(anchor);
            if (!Double.isFinite(padding) || padding < 0)
                throw new IllegalArgumentException("Gas hull padding");
            double depth = radius - Math.max(0, anchor.sub(center).length() - padding);
            return new Reading(bodyIndex, depth, policy.pressure(depth));
        }
    }

    public record Reading(int bodyIndex, double depth, double pressure) {
        public Reading {
            if (bodyIndex < 0
                    || !Double.isFinite(depth)
                    || !Double.isFinite(pressure)
                    || pressure < 0
                    || pressure > 1) throw new IllegalArgumentException("Gas pressure reading");
        }

        public int damageInterval() {
            return GasGiant.damageInterval(pressure);
        }
    }

    public static final class Field {
        private final List<Layer> layers;

        public Field(List<Layer> layers) {
            if (layers == null || layers.size() > GasGiant.MAX_COUNT)
                throw new IllegalArgumentException("Gas pressure capacity");
            this.layers = List.copyOf(layers);
            var identities = new HashSet<Integer>();
            for (var layer : this.layers)
                if (!identities.add(layer.bodyIndex()))
                    throw new IllegalArgumentException("Duplicate gas pressure identity");
        }

        public boolean empty() {
            return layers.isEmpty();
        }

        /** Empty means no gas in this field, not unknown hull geometry. Unknown is a caller status. */
        public Optional<Reading> worst(Point anchor, double padding) {
            GroundEphemeris.point(anchor);
            if (!Double.isFinite(padding) || padding < 0)
                throw new IllegalArgumentException("Gas hull padding");
            Reading worst = null;
            for (var layer : layers) {
                var next = layer.read(anchor, padding);
                if (worst == null
                        || next.pressure() > worst.pressure()
                        || next.pressure() == worst.pressure()
                                && next.bodyIndex() < worst.bodyIndex()) worst = next;
            }
            return Optional.ofNullable(worst);
        }

        public Optional<Reading> selected(int bodyIndex, Point anchor, double padding) {
            for (var layer : layers)
                if (layer.bodyIndex() == bodyIndex) return Optional.of(layer.read(anchor, padding));
            return Optional.empty();
        }
    }

    /** Catalog-only snapshot in universe coordinates; callers transform their actual hull into it. */
    public static Field snapshot(GroundEphemeris.Model model, double tick) {
        var layers = new ArrayList<Layer>();
        for (int index : model.catalogIndices()) {
            var body = model.catalogBody(index);
            if (body.gas().isPresent())
                layers.add(
                        new Layer(
                                index,
                                model.frame(index, tick).center(),
                                body.radius(),
                                body.gas().orElseThrow()));
        }
        return new Field(layers);
    }

    private GasPressure() {}
}
