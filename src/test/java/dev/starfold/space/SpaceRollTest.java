package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.ship.HelmCommand;
import dev.starfold.ship.Thrusters;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SpaceRollTest {
    @Test
    void directLeftWishRollsLeftWithoutReadingThrusterTorque() {
        assertEquals(1, HelmCommand.roll(HelmCommand.LEFT));
        assertEquals(-1, HelmCommand.roll(HelmCommand.RIGHT));
        assertEquals(0, HelmCommand.roll(HelmCommand.LEFT | HelmCommand.RIGHT));
        assertEquals(1, HelmCommand.roll(HelmCommand.LEFT | HelmCommand.SPRINT));
        var turn =
                SpaceServer.attitude(
                        Space.Frame.IDENTITY,
                        SpaceTest.tilted(),
                        0,
                        Space.ORIGIN,
                        10000,
                        0,
                        true,
                        90,
                        0,
                        HelmCommand.roll(HelmCommand.LEFT));
        var down = turn.transform(new Vector3d(0, -1, 0));
        assertTrue(down.z < 0);
        assertEquals(0, down.x, 1e-12);
    }

    @Test
    void zeroYawUsesFullBiteWithoutTheYawAllowance() {
        for (double spin : new double[] {-.4, .4, -11, 11})
            for (double step : new double[] {.01, .05}) {
                var change =
                        Thrusters.level(new Quaterniond(), new Vector3d(0, spin, 0), step, true);
                assertEquals(-spin * Math.min(1, Thrusters.BITE * step), change.y, 1e-12);
                assertEquals(0, change.x);
                assertEquals(0, change.z);
                assertNotEquals(
                        change.y,
                        Thrusters.level(new Quaterniond(), new Vector3d(0, spin, 0), step).y);
            }
        var q = new Quaterniond().rotateX(.4);
        var spin = new Vector3d(1, .7, 2);
        var old = Thrusters.level(q, spin, .01);
        var zero = Thrusters.level(q, spin, .01, true);
        assertEquals(old.x, zero.x);
        assertEquals(old.z, zero.z);
    }
}
