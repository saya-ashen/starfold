package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class OrbitAttitudeTest {
    private static void close(Vector3d expected, Vector3d actual) {
        assertTrue(expected.distance(actual) < 1e-8, expected + " != " + actual);
    }

    @Test
    void vanillaYawPitchConvention() {
        for (double yaw : new double[] {0, 42, 180, -90})
            for (double pitch : new double[] {-89, 0, 47, 90}) {
                var actual = OrbitAttitude.direction(OrbitAttitude.upright(yaw, pitch), 1, 0, 0);
                double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                close(
                        new Vector3d(
                                -Math.sin(y) * Math.cos(p),
                                -Math.sin(p),
                                Math.cos(y) * Math.cos(p)),
                        actual);
            }
    }

    @Test
    void continuousPitchCompletesFullLoop() {
        var q = new Quaterniond();
        for (int i = 0; i < 360; i++) q = OrbitAttitude.turn(q, 0, 1);
        close(new Vector3d(0, 0, 1), OrbitAttitude.direction(q, 1, 0, 0));
        close(new Vector3d(0, 1, 0), OrbitAttitude.direction(q, 0, 0, 1));
    }

    @Test
    void invertedFlightUsesScreenAxes() {
        var q = OrbitAttitude.turn(new Quaterniond(), 0, 180);
        close(new Vector3d(0, 0, -1), OrbitAttitude.direction(q, 1, 0, 0));
        close(new Vector3d(0, -1, 0), OrbitAttitude.direction(q, 0, 0, 1));
        close(new Vector3d(1, 0, 0), OrbitAttitude.direction(q, 0, 1, 0));
        q = OrbitAttitude.turn(q, 90, 0);
        close(new Vector3d(-1, 0, 0), OrbitAttitude.direction(q, 1, 0, 0));
    }

    @Test
    void diagonalInputIsNormalized() {
        assertEquals(
                1,
                OrbitAttitude.direction(OrbitAttitude.upright(57, 121), 1, 1, 1).length(),
                1e-12);
        assertEquals(.4, OrbitAttitude.direction(new Quaterniond(), .4, 0, 0).length(), 1e-12);
    }

    @Test
    void mouseTurnsMaintainOrthonormalBasis() {
        var q = new Quaterniond();
        for (int i = 0; i < 10000; i++) q = OrbitAttitude.turn(q, .73, -1.21);
        var f = OrbitAttitude.direction(q, 1, 0, 0);
        var l = OrbitAttitude.direction(q, 0, 1, 0);
        var u = OrbitAttitude.direction(q, 0, 0, 1);
        assertEquals(0, f.dot(u), 1e-10);
        assertEquals(0, l.dot(u), 1e-10);
        close(u, new Vector3d(f).cross(l));
    }

    @Test
    void landingRemovesRollButPreservesForwardRay() {
        var q = OrbitAttitude.upright(41, 38).rotateZ(2.1);
        for (int i = 0; i <= 100; i++) {
            var landed = OrbitAttitude.landing(q, i / 100., 41);
            close(q.transform(new Vector3d(0, 0, 1)), landed.transform(new Vector3d(0, 0, 1)));
        }
        close(
                OrbitAttitude.upright(41, 38).transform(new Vector3d(0, 1, 0)),
                OrbitAttitude.landing(q, 0, 41).transform(new Vector3d(0, 1, 0)));
    }

    @Test
    void polesRemainFiniteAndUseFallbackYaw() {
        for (double pitch : new double[] {90, -90, 270}) {
            var q = OrbitAttitude.landing(OrbitAttitude.upright(20, pitch), 0, 45);
            assertTrue(Double.isFinite(q.x + q.y + q.z + q.w));
            assertEquals(45, OrbitAttitude.yaw(q, 45), 1e-8);
        }
    }

    @Test
    void cameraQuaternionMatchesNavigationAxes() {
        var q = OrbitAttitude.turn(OrbitAttitude.upright(43, 127), 52, 74);
        var camera = new Quaterniond(q).rotateY(Math.PI);
        close(q.transform(new Vector3d(0, 0, 1)), camera.transform(new Vector3d(0, 0, -1)));
        close(q.transform(new Vector3d(1, 0, 0)), camera.transform(new Vector3d(-1, 0, 0)));
    }
}
