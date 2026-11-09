package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class GasPlayerExposureSamplingTest {
    private static GroundEphemeris.Model model() {
        var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
        bodies.add(GroundEphemeris.Body.gas(
                new GroundEphemeris.Fixed(new Point(40000, 5000, -7000)), 4096,
                new GasGiant(1, 31, "Amber", 0x98653d, 0xead1a2, 256, 512)));
        return new GroundEphemeris.Model(bodies);
    }

    @Test
    void movingRotatedFrameSamplesLevelBodyCenterOnceAtTheCapturedEphemerisTime() {
        var model = model();
        int gas = model.bodies().size() - 1;
        var frame = new Space.Frame(new Quaterniond().rotateY(.7), Space.ORIGIN, 0,
                new Point(30000, 20, -100), new Point(30, -10, 12), 10);
        long tick = 37;
        var universe = model.frame(gas, tick).center().add(new Point(3583.5, 0, 0));
        var level = frame.level(universe, model, tick);
        var sample = GasPlayerExposureSampling.sample(true, model, frame, tick, level);
        assertEquals(GasPlayerExposure.Condition.DANGER, sample.condition());
        assertEquals(512.5, sample.reading().orElseThrow().depth(), 1e-8);
        assertEquals(gas, sample.reading().orElseThrow().bodyIndex());
        assertNotEquals(sample, GasPlayerExposureSampling.sample(true, model, frame, tick + 200, level));
        assertNotEquals(sample, GasPlayerExposureSampling.sample(true, model, frame, tick,
                frame.universe(level, model, tick)), "a second transform is observably wrong");
        assertEquals(sample, GasPlayerExposureSampling.sample(true, model, frame, tick, level),
                "there is no hull/aboard argument: crew and free players use identical geometry");
    }

    @Test
    void noGasAndSolidDimensionsAreSafeButMissingFrameOrUnknownCenterNeverAre() {
        var valid = Space.ORIGIN;
        assertEquals(GasPlayerExposure.Condition.SAFE,
                GasPlayerExposureSampling.sample(false, null, null, 0, valid).condition());
        assertEquals(GasPlayerExposure.Condition.SAFE,
                GasPlayerExposureSampling.sample(true, GroundSolar.defaults(), Space.Frame.IDENTITY,
                        0, valid).condition());
        for (var sample : new GasPlayerExposure.Sample[] {
                GasPlayerExposureSampling.sample(true, model(), null, 10, valid),
                GasPlayerExposureSampling.sample(true, null, Space.Frame.IDENTITY, 10, valid),
                GasPlayerExposureSampling.sample(true, model(), Space.Frame.IDENTITY, -1, valid),
                GasPlayerExposureSampling.sample(false, null, null, 0, new Point(Double.NaN, 0, 0)),
                GasPlayerExposureSampling.sample(false, null, null, 0, null)})
            assertEquals(GasPlayerExposure.Condition.UNKNOWN, sample.condition());
    }
}
