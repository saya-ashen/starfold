package dev.starfold.render;

import dev.starfold.geometry.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundRenderFrameTest {
    @Test void resetReleasesFrameReferencesAndIsIdempotent() {
        var frame = new GroundRenderFrame();
        frame.active = true;
        frame.frameEye = new Point(1, 2, 3);
        frame.frameUniverseEye = new Point(4, 5, 6);
        frame.clear();
        frame.clear();
        assertFalse(frame.active);
        assertNull(frame.frameEye);
        assertNull(frame.frameUniverseEye);
        assertNull(frame.frameHome);
        assertNull(frame.frameStar);
        assertNull(frame.framePose);
        assertNull(frame.frameGap);
        assertNull(frame.frameOrbit);
    }
}
