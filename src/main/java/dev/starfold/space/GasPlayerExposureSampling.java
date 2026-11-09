package dev.starfold.space;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GasPressure;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import java.util.ArrayList;

/** Converts an actual Level body center exactly once; frame and GAS ephemerides share tick. */
public final class GasPlayerExposureSampling {
    public static GasPlayerExposure.Sample sample(
            boolean region, GroundEphemeris.Model model, Space.Frame frame, long tick,
            Point levelCenter) {
        try {
            GroundEphemeris.point(levelCenter);
            if (!region) return GasPlayerExposure.confirmedSafe();
            if (model == null || frame == null || tick < 0) return GasPlayerExposure.unknown();
            var center = frame.universe(levelCenter, model, tick);
            var layers = new ArrayList<GasPressure.Layer>();
            for (int index : model.catalogIndices()) {
                var body = model.catalogBody(index);
                if (body.gas().isPresent())
                    layers.add(new GasPressure.Layer(index, model.frame(index, tick).center(),
                            body.radius(), body.gas().orElseThrow()));
            }
            return GasPlayerExposure.sample(layers, center);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException unknown) {
            return GasPlayerExposure.unknown();
        }
    }

    private GasPlayerExposureSampling() {}
}
