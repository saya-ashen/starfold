package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class GroundSurfaceStreamTest {
    @Test
    void smallDirectionalMovesKeepFrontierBut512BlocksResetIt() throws Exception {
        var shape = PlanetShape.STANDARD;
        double r = shape.radius();
        var eye = new Point(r, 0, 0);
        var stream = new GroundSurfaceStream();
        var index = new GroundSurfaceIndex();
        var sent = new HashMap<Long, Long>();
        var anchor = GroundSurfaceStream.class.getDeclaredField("eye");
        anchor.setAccessible(true);
        stream.next(index, sent, shape, eye);
        double small = 2 * Math.asin(64 / (2 * r));
        stream.next(index, sent, shape, new Point(r * Math.cos(small), r * Math.sin(small), 0));
        assertSame(eye, anchor.get(stream));
        double large = 2 * Math.asin(513 / (2 * r));
        var moved = new Point(r * Math.cos(large), r * Math.sin(large), 0);
        stream.next(index, sent, shape, moved);
        assertSame(moved, anchor.get(stream));
    }
}
