package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundMotion;
import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;

import net.minecraft.nbt.CompoundTag;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class GroundSpinNavigationTest {
    final GroundEphemeris.Model model = GroundEphemeris.spinningDefaults();

    GroundMotion.State body(Face face, double h, Quaterniond q) {
        return new GroundMotion.State(
                new GroundSpace.Pose(face, new Point(1024, h, 1024)),
                0,
                false,
                true,
                false,
                false,
                q);
    }

    void close(Point a, Point b) {
        assertTrue(a.sub(b).length() < 1e-6, a + " != " + b);
    }

    void same(Quaterniond a, Quaterniond b) {
        assertEquals(1, Math.abs(a.dot(b)), 1e-9);
    }

    @Test
    void slowFixedAxisMatchesAnalyticSurfaceDerivative() {
        for (int id = 0; id < 2; id++)
            for (double t : new double[] {100, 1234.5, 6000, 24000}) {
                var p = new Point(2000, 700, -900);
                var numeric =
                        model.at(id, t + .05)
                                .world(p)
                                .sub(model.at(id, t - .05).world(p))
                                .scale(10);
                close(model.at(id, t).worldVelocity(p, GroundEphemeris.ZERO), numeric);
                close(p, model.at(id, t).local(model.at(id, t).world(p)));
                var v = new Point(3, 4, -2);
                close(
                        v,
                        model.at(id, t)
                                .localVelocity(
                                        model.at(id, t).world(p),
                                        model.at(id, t).worldVelocity(p, v)));
            }
        assertEquals(48000, model.a().period());
        assertEquals(72000, model.b().period());
    }

    @Test
    void arbitraryReferenceRotatesAngularAxisAndDefensiveFrozenPose() {
        var ref = new Quaterniond().rotationXYZ(.4, .8, -.2);
        var s = new GroundEphemeris.Spin(true, GroundEphemeris.Y, ref, .3, 48000, 50);
        close(
                GroundEphemeris.rotate(ref, GroundEphemeris.Y).scale(2 * Math.PI / 48000),
                s.angular());
        var paused = s.toggle(false, 100, 48000);
        var q = paused.at(100);
        same(q, paused.at(999));
        paused.frozen().rotateX(.7);
        same(q, paused.at(100));
    }

    @Test
    void enablePauseResumeIsPoseContinuousAndSurvivesSave() {
        var clock = new GroundEphemerisSave(model, 6789);
        var before = model.at(1, 6789).rotation();
        clock.spin(false);
        same(before, clock.model.at(1, 6789).rotation());
        same(before, clock.model.at(1, 9000).rotation());
        var loaded = GroundEphemerisSave.load(clock.save(new CompoundTag(), null), null);
        assertEquals(clock.model, loaded.model);
        loaded.advance();
        loaded.spin(true);
        same(before, loaded.model.at(1, 6790).rotation());
        assertTrue(loaded.model.at(1, 6800).angular().length() > 0);
    }

    @Test
    void legacyDormantMetadataRemainsInactiveAndFormatTwoRequiresFrozenPose() {
        var clock = new GroundEphemerisSave(GroundEphemeris.defaults(), 123);
        var old = clock.save(new CompoundTag(), null);
        old.putInt("format", 1);
        for (String n : List.of("spin_a", "spin_b"))
            old.getCompound("motion").getCompound(n).remove("frozen_orientation");
        var loaded = GroundEphemerisSave.load(old, null);
        assertFalse(loaded.model.a().enabled());
        assertEquals(new Quaterniond(), loaded.model.at(0, 200).rotation());
        old.putInt("format", 2);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.load(old, null));
        assertTrue(GroundEphemerisSave.fresh(false).model.a().enabled());
    }

    @Test
    void rotatingRemoteBasisUsesSharedRenderTime() {
        for (double t : new double[] {100, 6000, 20000}) {
            var a = model.at(0, t);
            var b = model.at(1, t);
            var p = new Point(900, 800, -700);
            close(
                    a.local(b.world(p)),
                    a.inverseVector(b.center().sub(a.center())).add(a.inverseVector(b.vector(p))));
        }
    }
}
