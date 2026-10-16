package dev.starfold.ship;

import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import static org.junit.jupiter.api.Assertions.*;

class GroundGyroTest {
    @Test void usesExactlyLiftContactAndSettledBoundary() {
        assertTrue(Thrusters.groundRest(true, LiftControl.CONTACT, .8, 0, false));
        assertTrue(Thrusters.groundRest(true, -.003, -.8, HelmCommand.DOWN, false));
        assertFalse(Thrusters.groundRest(true, Math.nextUp(LiftControl.CONTACT), 0, 0, false));
        assertFalse(Thrusters.groundRest(true, 0, Math.nextUp(.8), 0, false));
        assertFalse(Thrusters.groundRest(true, Double.NaN, 0, 0, false));
        assertFalse(Thrusters.groundRest(true, Double.NEGATIVE_INFINITY, 0, 0, false));
    }
    @Test void departureAndEveryDirectionalInputRestoreLevelingImmediately() {
        for (int bit : new int[]{HelmCommand.UP, HelmCommand.FORWARD, HelmCommand.BACK,
                HelmCommand.LEFT, HelmCommand.RIGHT, HelmCommand.UP | HelmCommand.DOWN})
            assertFalse(Thrusters.groundRest(true, -.003, 0, bit, false));
        assertTrue(Thrusters.groundRest(true, 0, 0, HelmCommand.DOWN | HelmCommand.SPRINT, false));
        assertFalse(Thrusters.groundRest(false, 0, 0, 0, false));
        assertFalse(Thrusters.groundRest(true, 0, 0, 0, true));
    }
    @Test void measuredMoonContactReleasesGyroButHoverDoesNot() {
        assertTrue(Thrusters.groundRest(true, -.002838138656159117, -.04660388454794884, 0, false));
        assertFalse(Thrusters.groundRest(true, .783, .0364, 0, false));
        // The flight leveling law itself remains unchanged.
        var tilt = new Quaterniond().rotateX(.16);
        assertEquals(-.12, Thrusters.level(tilt, new Vector3d(), .025).x, 1e-12);
    }
}
