package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.ObserverFrame;

import org.junit.jupiter.api.Test;

class HelmClientTest {

    @Test
    void mouseFlightTiltsOnlyTheStandBackDirection() throws Exception {
        assertEquals(8, HelmClient.CHASE_TILT);
    }

    @Test
    void displacedViewDoesNotBecomeTheChartObserver() throws Exception {
        for (int turn = 0; turn < 4; turn++) {
            var f = new ObserverFrame(0, 1, turn, 2048, -4096);
            var eye = new Point(20, 300, 40);
            var offset = new Point(36, -12, 48);
            var vertex = new Point(25, 302, 41);
            var relative = vertex.sub(eye.add(offset));
            var reconstructed = eye.add(f.vectorToStorage(f.vectorToClient(relative))).add(offset);
            assertEquals(vertex.x(), reconstructed.x(), 1e-9);
            assertEquals(vertex.y(), reconstructed.y(), 1e-9);
            assertEquals(vertex.z(), reconstructed.z(), 1e-9);
        }
    }
}
