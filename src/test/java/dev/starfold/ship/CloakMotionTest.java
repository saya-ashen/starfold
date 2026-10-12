package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CloakMotionTest {
    @Test
    void standingInMovingHullHasNoLag() {
        for (int tick = 0; tick < 200; tick++) assertEquals(2, CloakMotion.follow(2, 2));
    }

    @Test
    void walkingRetainsVanillaRelaxation() {
        assertEquals(.025, CloakMotion.follow(0, .1), 1e-12);
        assertEquals(.0625, CloakMotion.follow(.05, .1), 1e-12);
        assertEquals(-.025, CloakMotion.follow(0, -.1), 1e-12);
    }

    @Test
    void teleportsResetInsteadOfWhipping() {
        assertEquals(12, CloakMotion.follow(0, 12));
        assertEquals(-12, CloakMotion.follow(0, -12));
    }
}
