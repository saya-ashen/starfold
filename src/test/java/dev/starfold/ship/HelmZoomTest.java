package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

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
}
