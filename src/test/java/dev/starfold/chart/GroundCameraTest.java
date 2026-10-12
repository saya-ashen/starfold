package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.OrbitAttitude;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.file.*;

class GroundCameraTest {
    private static void upright(Quaterniondc q) {
        var left = q.transform(new Vector3d(1, 0, 0));
        var up = q.transform(new Vector3d(0, 1, 0));
        assertEquals(0, left.y, 1e-10, "Screen left must remain horizontal");
        assertTrue(up.y > 0, "Camera upside down");
        assertTrue(Math.abs(OrbitAttitude.pitch(q)) <= 89.5 + 1e-9);
    }

    @Test
    void horizontalMouseAtAPitchMustNotChangeElevation() {
        var start = OrbitAttitude.upright(0, 60);
        var after = GroundView.turn(start, 90, 0);
        assertEquals(60, OrbitAttitude.pitch(after), 1e-10);
    }

    @Test
    void pitchedYawMustNotRollTheHorizon() {
        var after = GroundView.turn(OrbitAttitude.upright(0, 80), 90, 0);
        upright(after);
    }

    @Test
    void verticalMouseCannotFlipThePlayerUpsideDown() {
        for (double delta : new double[] {100, 180, 270, -100, -180, -270}) {
            var q = GroundView.turn(new Quaterniond(), 0, delta);
            upright(q);
            assertEquals(Math.copySign(89.5, delta), OrbitAttitude.pitch(q), 1e-9);
        }
    }

    @Test
    void mixedMouseMotionCannotAccumulateRoll() {
        var q = new Quaterniond();
        for (int i = 0; i < 10000; i++) {
            q = GroundView.turn(q, Math.sin(i * .7) * 3, Math.cos(i * .3) * 2);
            upright(q);
        }
    }

    @Test
    void oldFreeFlightRuleReproducesNearSidewaysCamera() {
        var q = OrbitAttitude.turn(OrbitAttitude.upright(0, 80), 90, 0);
        double tilt = Math.toDegrees(Math.asin(Math.abs(q.transform(new Vector3d(1, 0, 0)).y)));
        assertEquals(80, tilt, 1e-9);
        assertEquals(0, OrbitAttitude.pitch(q), 1e-9);
    }

    @Test
    void existingRollIsRemovedWithoutChangingOrdinaryLookDirection() {
        for (double pitch : new double[] {-80, -35, 0, 35, 80})
            for (double roll : new double[] {-.8, Math.PI / 2, Math.PI}) {
                var before = OrbitAttitude.upright(42, pitch).rotateZ(roll);
                var after = GroundView.upright(before);
                upright(after);
                assertTrue(
                        before.transform(new Vector3d(0, 0, 1))
                                        .distance(after.transform(new Vector3d(0, 0, 1)))
                                < 1e-10);
            }
    }

    @Test
    void polesStayFiniteAndCameraBasisMatchesPicking() {
        for (double pitch : new double[] {-270, -90, 0, 90, 270}) {
            var q = GroundView.upright(OrbitAttitude.upright(17, pitch).rotateZ(.7));
            upright(q);
            var camera = GroundView.camera(q);
            assertTrue(
                    q.transform(new Vector3d(0, 0, 1))
                                    .distance(camera.transform(new Vector3d(0, 0, -1)))
                            < 1e-10);
            assertTrue(
                    q.transform(new Vector3d(1, 0, 0))
                                    .distance(camera.transform(new Vector3d(-1, 0, 0)))
                            < 1e-10);
            assertTrue(
                    q.transform(new Vector3d(0, 1, 0))
                                    .distance(camera.transform(new Vector3d(0, 1, 0)))
                            < 1e-10);
        }
    }
}
