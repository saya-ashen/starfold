package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeCloakMotionTest {
    @Test void standingInMovingHullHasNoLag(){for(int tick=0;tick<200;tick++)assertEquals(2,NativeCloakMotion.follow(2,2));}
    @Test void walkingRetainsVanillaRelaxation(){assertEquals(.025,NativeCloakMotion.follow(0,.1),1e-12);assertEquals(.0625,NativeCloakMotion.follow(.05,.1),1e-12);assertEquals(-.025,NativeCloakMotion.follow(0,-.1),1e-12);}
    @Test void teleportsResetInsteadOfWhipping(){assertEquals(12,NativeCloakMotion.follow(0,12));assertEquals(-12,NativeCloakMotion.follow(0,-12));}
}
