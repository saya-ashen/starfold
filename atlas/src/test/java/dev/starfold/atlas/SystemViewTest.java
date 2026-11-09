package dev.starfold.atlas;

import java.util.ArrayList;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemViewTest {
    @Test void loneStarKeepsAPositiveViewingDistance() {
        assertEquals(.2, SystemView.fit(java.util.List.of(new Vector3d()), new Vector3d(), 240, 120));
    }
    @Test void overviewFillsEightyFivePercentOfTheShortEdgeIncludingPerspectiveDepth() {
        var points = new ArrayList<Vector3d>();
        for (int i = 0; i < 128; i++) {
            double angle = Math.PI * 2 * i / 128;
            points.add(new Vector3d(3 * Math.cos(angle), .4 * Math.sin(angle), 3 * Math.sin(angle)));
        }
        // Both orbital envelopes and sparse interstellar points use the same framing rule.
        for (var scene : java.util.List.of(points, points.subList(0, 9)))
            for (int width : new int[]{240, 320, 427, 640, 854, 1280}) for (int height : new int[]{120, 284, 600})
                for (var focus : new Vector3d[]{new Vector3d(), new Vector3d(2, -.5, 1)}) {
                    var camera = new MapCamera();
                    camera.viewport(0, 52, width, height); camera.focus(focus);
                    camera.pose(.35, .65, SystemView.fit(scene, focus, width, height));
                    double radius = .85 * Math.min(width, height) / 2, largest = 0;
                    for (var point : scene) {
                        var p = camera.project(point);
                        assertTrue(p.depth() > 0);
                        double offset = Math.max(Math.abs(p.x() - width / 2.0), Math.abs(p.y() - 52 - height / 2.0));
                        assertTrue(offset <= radius + 1e-9, p.toString());
                        largest = Math.max(largest, offset);
                    }
                    assertEquals(radius, largest, 1e-9);
                }
    }
}
