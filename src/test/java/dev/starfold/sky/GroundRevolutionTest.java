package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundMotion;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.PlanetSpace;

import net.minecraft.nbt.CompoundTag;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** Actual moving-world kernel, not the retained fixed-planet GroundCosmos helper. */
class GroundRevolutionTest {
    GroundEphemeris.Model model = GroundEphemeris.defaults();

    GroundMotion.State ground(double h) {
        return new GroundMotion.State(
                new GroundSpace.Pose(Face.TOP, new Point(1024, h, 1024)),
                0,
                false,
                true,
                false,
                false,
                new Quaterniond());
    }

    PlanetMotion.Input input(long seq, Quaterniond q, float f, float l, float rise) {
        return new PlanetMotion.Input(seq, q.x, q.y, q.z, q.w, f, l, rise, true, true);
    }

    void close(Point a, Point b) {
        assertTrue(a.sub(b).length() < 1e-6, a + " != " + b);
    }

    @Test
    void analyticPlanetVelocityMatchesTimeDerivative() {
        for (int id = 0; id < 2; id++)
            for (double t : new double[] {1, 1234.5, 6000, 23999, 1000001}) {
                var numerical =
                        model.at(id, t + .05)
                                .center()
                                .sub(model.at(id, t - .05).center())
                                .scale(10);
                close(model.at(id, t).velocity(), numerical);
                assertEquals(8192 * 2 * Math.PI / 24000, model.at(id, t).velocity().length(), 1e-9);
            }
    }

    @Test
    void rigidPositionAndVelocityConversionsIncludeTranslationAndSpin() {
        var f =
                new GroundEphemeris.Frame(
                        new Point(10, 20, 30),
                        new Quaterniond().rotationXYZ(.2, .7, -.4),
                        new Point(3, -2, 1),
                        new Point(0, .02, 0));
        var p = new Point(100, 40, -80);
        var v = new Point(2, 3, 4);
        close(p, f.local(f.world(p)));
        close(v, f.localVelocity(f.world(p), f.worldVelocity(p, v)));
        var expected = f.velocity().add(f.angular().cross(f.vector(p)));
        close(expected, f.worldVelocity(p, GroundEphemeris.ZERO));
        assertTrue(expected.sub(f.velocity()).length() > 1);
    }

    @Test
    void quaternionMetadataIsDefensiveAndInvalidAxesOrTimeAreRejected() {
        var q = new Quaterniond();
        var spin = new GroundEphemeris.Spin(false, GroundEphemeris.Y, q, .7, 30000, 100);
        q.rotateX(.4);
        assertEquals(new Quaterniond(), spin.reference());
        spin.reference().rotateY(.5);
        assertEquals(new Quaterniond(), spin.reference());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Spin(
                                true, GroundEphemeris.Y, new Quaterniond(), 0, 23999, 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Spin(
                                false, new Point(0, 2, 0), new Quaterniond(), 0, 24000, 0));
        assertThrows(IllegalArgumentException.class, () -> model.at(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> model.at(0, -1));
        assertThrows(
                IllegalArgumentException.class, () -> model.at(0, GroundEphemeris.MAX_TICK + 1.0));
    }

    @Test
    void clockAndAllDormantSpinParametersSurviveSaveWithoutWallTime() {
        var spin =
                new GroundEphemeris.Spin(
                        false,
                        PlanetSpace.unit(new Point(1, 2, 3)),
                        new Quaterniond().rotationXYZ(.2, .4, .7),
                        .75,
                        27000,
                        321);
        var m =
                new GroundEphemeris.Model(
                        model.center(),
                        model.axis(),
                        model.radial(),
                        model.radius(),
                        model.period(),
                        .1,
                        19,
                        spin,
                        model.b());
        var clock = new GroundEphemerisSave(m, 1234);
        for (int i = 0; i < 17; i++) clock.advance();
        var tag = clock.save(new CompoundTag(), null);
        var copy = GroundEphemerisSave.load(tag, null);
        assertEquals(1251, copy.tick());
        assertEquals(m, copy.model);
        assertEquals(tag, copy.save(new CompoundTag(), null));
        close(m.at(0, clock.tick()).center(), copy.model.at(0, copy.tick()).center());
        assertFalse(copy.model.a().enabled());
        assertEquals(new Quaterniond(), copy.model.at(0, copy.tick()).rotation());
        copy.advance();
        assertEquals(1252, copy.tick());
        assertEquals(1251, clock.tick());
    }

    @Test
    void corruptEphemerisIsNeverRecreatedOrSilentlyDefaulted() {
        assertThrows(IllegalStateException.class, () -> GroundEphemerisSave.fresh(true));
        var tag = GroundEphemerisSave.fresh(false).save(new CompoundTag(), null);
        for (String k : List.of("format", "tick", "motion")) {
            var bad = tag.copy();
            bad.remove(k);
            assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.load(bad, null));
        }
        for (String k :
                List.of("axis", "radius", "period_ticks", "reference_tick", "spin_a", "spin_b")) {
            var bad = tag.copy();
            bad.getCompound("motion").remove(k);
            assertThrows(
                    IllegalArgumentException.class, () -> GroundEphemerisSave.load(bad, null), k);
        }
        var bad = tag.copy();
        bad.getCompound("motion").getCompound("spin_a").putDouble("period_ticks", 20);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.load(bad, null));
    }

    @Test
    void relativeRenderTransformMatchesUniversePositionsAtOneTime() {
        for (double t : new double[] {0, 100, 6000, 10000})
            for (int observer = 0; observer < 2; observer++) {
                var a = model.at(observer, t);
                var b = model.at(1 - observer, t);
                for (var face : Face.values()) {
                    var local = GroundOrbit.direction(face, 800, 1200).scale(GroundOrbit.RADIUS);
                    var expected = a.local(b.world(local));
                    var actual =
                            a.inverseVector(b.center().sub(a.center()))
                                    .add(a.inverseVector(b.vector(local)));
                    close(expected, actual);
                }
            }
    }

    int transitions;
    double maxStep;
}
