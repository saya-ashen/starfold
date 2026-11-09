package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class GasHullGeometryTest {
    private static Pose3d hull(double angle) {
        return new Pose3d(
                new Vector3d(100, 200, 300),
                new Quaterniond().rotationXYZ(angle, .3, -.4),
                new Vector3d(20000000, -100, 20000000),
                new Vector3d(2, 3, 4));
    }

    @Test
    void inclusiveCornersEncloseRotatingScaledHullWithLargePlotPivot() {
        var bounds = new BoundingBox3i(19999990, -105, 19999998, 20000009, -96, 20000001);
        for (double angle : new double[] {0, .1, 1, 2, Math.PI, 5}) {
            var pose = hull(angle);
            var before = new Pose3d(pose);
            assertEquals(Math.sqrt(689), GasHullGeometry.radius(pose, bounds, null), 1e-8);
            same(before, pose);
            assertEquals(Math.sqrt(29) / 2, GasHullGeometry.blockPadding(pose), 1e-12);
        }
    }

    @Test
    void mountedChildTransformsItsMassPivotIntoPlotBeforeHullPoseExactlyOnce() {
        var plot = new BoundingBox3i(19999990, -105, 19999998, 20000009, -96, 20000001);
        var child =
                new Pose3d(
                        new Vector3d(20000100, -100, 20000000),
                        new Quaterniond().rotationZ(Math.PI / 2),
                        new Vector3d(5, 7, 9),
                        new Vector3d(1));
        var bounds = new BoundingBox3i(5, 7, 9, 14, 16, 18);
        var saved = new Pose3d(child);
        for (double angle : new double[] {0, .4, 1.5, 3}) {
            var pose = hull(angle);
            double parent = GasHullGeometry.radius(pose, plot, null);
            double mounted = GasHullGeometry.radius(pose, bounds, child);
            // Child rotates [0,10]^3 about its own COM, then offsets +100 in parent X.
            // At the farthest corner, scaled parent offsets are (200,30,40).
            assertEquals(Math.sqrt(42500), mounted, 1e-8);
            assertTrue(mounted > parent * 7);
            same(saved, child);
        }
    }

    private static void same(Pose3d expected, Pose3d actual) {
        assertEquals(expected.position(), actual.position());
        assertEquals(expected.orientation(), actual.orientation());
        assertEquals(expected.rotationPoint(), actual.rotationPoint());
        assertEquals(expected.scale(), actual.scale());
    }

    @Test
    void unitCubeUsesMaxPlusOneAndUnknownPoseNeverBecomesSafeZero() {
        var pose = new Pose3d(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d(1));
        assertEquals(
                Math.sqrt(3),
                GasHullGeometry.radius(pose, new BoundingBox3i(0, 0, 0, 0, 0, 0), null),
                1e-12);
        assertEquals(0, GasHullGeometry.radius(pose, BoundingBox3i.EMPTY, null));
        assertThrows(
                IllegalArgumentException.class, () -> GasHullGeometry.radius(pose, null, null));
        var bad = new Pose3d(pose);
        bad.scale().x = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> GasHullGeometry.blockPadding(bad));
        bad.scale().set(1);
        bad.orientation().set(0, 0, 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> GasHullGeometry.radius(bad, BoundingBox3i.EMPTY, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> GasHullGeometry.radius(pose, BoundingBox3i.EMPTY, bad));
    }
}
