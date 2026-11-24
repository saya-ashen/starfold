package dev.starfold.ship;

import static dev.starfold.ship.HelmCommand.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.sky.Space;
import dev.starfold.sky.SpaceTest;

import net.minecraft.core.Direction;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class HelmCommandTest {
    @Test
    void translationSelectsTheMatchingNozzlesForEveryBow() {
        for (var bow : Direction.Plane.HORIZONTAL) {
            var left = bow.getCounterClockWise();
            var right = bow.getClockWise();
            assertTrue(fires(FORWARD, bow, bow.getOpposite()));
            assertTrue(fires(BACK, bow, bow));
            assertTrue(fires(UP, bow, Direction.DOWN));
            assertTrue(fires(DOWN, bow, Direction.UP));
            assertTrue(fires(LEFT, bow, left.getOpposite()));
            assertTrue(fires(RIGHT, bow, right.getOpposite()));
            assertFalse(fires(LEFT, bow, right.getOpposite()));
            assertFalse(fires(RIGHT, bow, left.getOpposite()));
            assertFalse(fires(LEFT | RIGHT, bow, left.getOpposite()));
            assertFalse(fires(SPRINT, bow, bow));
        }
    }

    @Test
    void attitudeAxesFollowControllerPoseRatherThanPilotYaw() {
        var frame = Space.Frame.IDENTITY;
        var model = SpaceTest.tilted();
        for (double yaw : new double[] {0, 90, 180, 270}) {
            double a = Math.toRadians(yaw);
            var f = new BentStripGeometry.Point(-Math.sin(a), 0, Math.cos(a));
            var up = new BentStripGeometry.Point(0, 1, 0);
            var old =
                    dev.starfold.space.SpaceFlightMath.attitude(
                            frame, model, 0, Space.ORIGIN, false, true, yaw, .3, -.2, 1, 2);
            var current =
                    dev.starfold.space.SpaceFlightMath.attitude(
                            frame, model, 0, Space.ORIGIN, false, true, f, up, .3, -.2, 1, 2);
            assertEquals(old.x, current.x, 1e-12);
            assertEquals(old.y, current.y, 1e-12);
            assertEquals(old.z, current.z, 1e-12);
            assertEquals(old.w, current.w, 1e-12);
            var pose = new org.joml.Quaterniond().rotateXYZ(.4, .2, -.3);
            var fv = pose.transform(new Vector3d(f.x(), f.y(), f.z()));
            var uv = pose.transform(new Vector3d(0, 1, 0));
            var rotated =
                    dev.starfold.space.SpaceFlightMath.attitude(
                            frame,
                            model,
                            0,
                            Space.ORIGIN,
                            false,
                            true,
                            new BentStripGeometry.Point(fv.x, fv.y, fv.z),
                            new BentStripGeometry.Point(uv.x, uv.y, uv.z),
                            .3,
                            -.2,
                            1,
                            2);
            var expected =
                    new org.joml.Quaterniond(pose)
                            .mul(old)
                            .mul(new org.joml.Quaterniond(pose).conjugate());
            assertEquals(expected.x, rotated.x, 1e-12);
            assertEquals(expected.y, rotated.y, 1e-12);
            assertEquals(expected.z, rotated.z, 1e-12);
            assertEquals(expected.w, rotated.w, 1e-12);
        }
    }

    @Test
    void commandCombinesWithRedstoneAndBoostNeedsHardware() {
        for (int redstone = 0; redstone <= 15; redstone++) {
            assertEquals(redstone, effective(redstone, false));
            assertEquals(15, effective(redstone, true));
        }
        assertFalse(boosted(SPRINT, false));
        assertFalse(boosted(0, true));
        assertTrue(boosted(SPRINT, true));
        assertTrue(boosted(FORWARD | SPRINT, true));
    }

    @Test
    void inputLeaseRejectsFutureAndExpiredTicks() {
        assertFalse(fresh(10, 9));
        assertTrue(fresh(10, 10));
        assertTrue(fresh(10, 15));
        assertFalse(fresh(10, 16));
        assertFalse(fresh(Long.MIN_VALUE, 0));
    }

    @Test
    void ownershipIsExclusiveMovesAtomicallyAndIsNotPersistent() {
        var p = new Pilots<String, String>();
        assertTrue(p.take("helm-a", "alice"));
        assertFalse(p.take("helm-a", "bob"));
        assertEquals("alice", p.owner("helm-a"));
        assertTrue(p.take("helm-b", "bob"));
        assertFalse(p.take("helm-a", "bob"));
        assertEquals("bob", p.owner("helm-b"));
        assertTrue(p.take("helm-c", "alice"));
        assertNull(p.owner("helm-a"));
        assertEquals("alice", p.owner("helm-c"));
        p.release("helm-c");
        assertTrue(p.take("helm-c", "bob"));
        assertNull(p.owner("helm-b"));
        p.clear();
        assertNull(p.owner("helm-c"));
        assertNull(new Pilots<String, String>().owner("helm-c"));
    }
}
