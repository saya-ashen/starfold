package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

class HelmZoomTest {
    @Test
    void threeInThreeOutAndBounds() {
        double factor = 1;
        assertEquals(1.15, HelmClient.ZOOM_STEP);
        for (int i = 0; i < 3; i++) factor = HelmClient.zoomed(factor, 1);
        assertEquals(Math.pow(1.15, -3), factor, 1e-12);
        for (int i = 0; i < 3; i++) factor = HelmClient.zoomed(factor, -1);
        assertEquals(1, factor, 1e-12);
        assertEquals(.4, HelmClient.zoomed(1, 1000));
        assertEquals(4, HelmClient.zoomed(1, -1000));
        assertEquals(1, HelmClient.zoomed(1, Double.NaN));
    }

    @Test
    void scrollScopeAndTerrainPullInArePreserved() throws Exception {
        var s = Sources.text("HelmClient");
        assertTrue(s.contains("mc.screen!=null||body()==null"));
        assertTrue(
                s.contains(
                        "!scopeView()&&mc.options.getCameraType()==CameraType.THIRD_PERSON_BACK"));
        assertTrue(s.contains("event.setCanceled(true)"));
        assertTrue(s.contains("distance=Math.min(distance,"));
    }
}
