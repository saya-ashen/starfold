package dev.starfold.atlas;

import org.junit.jupiter.api.Test;
import static dev.starfold.atlas.AtlasScreen.Layer.*;
import static org.junit.jupiter.api.Assertions.*;

class AtlasScaleTest {
    @Test void extraScrollChangesOneLayerOnlyAfterTheSmoothedCameraReachesItsLimit() {
        assertEquals(GLOBE, GLOBE.scroll(3.5, 2.35, 1));
        assertEquals(GLOBE, GLOBE.scroll(2.35, 3.5, 1));
        assertEquals(GROUND, GLOBE.scroll(2.35, 2.35, 1));
        assertEquals(GLOBE, GLOBE.scroll(6, 12, -1));
        assertEquals(SYSTEM, GLOBE.scroll(12, 12, -1));
        assertEquals(GLOBE, GROUND.scroll(2.35, 2.35, -1));
        assertEquals(GROUND, GROUND.scroll(2.35, 2.35, 1));
        assertEquals(SYSTEM, SYSTEM.scroll(12, 12, 1));
        assertEquals(GALAXY, SYSTEM.scroll(12, 12, -1));
        assertEquals(GLOBE, GLOBE.scroll(2.35, 2.35, 0));
        assertEquals(GLOBE, GLOBE.scroll(12, 12, 0));
    }
    @Test void orreryAndGalaxyUseTheirOwnSmoothedDistanceLimits() {
        for (double far : new double[]{4, 80, 900}) {
            double near = far * .008;
            assertEquals(SYSTEM, SYSTEM.scroll(far * .8, far, -1, near, far));
            assertEquals(GALAXY, SYSTEM.scroll(far, far, -1, near, far));
            assertEquals(SYSTEM, SYSTEM.scroll(near * 2, near, 1, near, far));
            assertEquals(GLOBE, SYSTEM.scroll(near, near, 1, near, far));
            assertEquals(GALAXY, GALAXY.scroll(far, far, -1, near, far));
            assertEquals(GALAXY, GALAXY.scroll(near * 2, near, 1, near, far));
            assertEquals(SYSTEM, GALAXY.scroll(near, near, 1, near, far));
            for (var layer : values()) assertEquals(layer, layer.scroll(near, near, 0, near, far));
        }
    }
}
