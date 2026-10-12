package dev.starfold.space;

import static dev.starfold.space.SpaceAttitudeClient.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AimDrawTest {
    @Test
    void stillMouseIsMonotoneAndTickEndpointsAgree() {
        for (var offset :
                new Pointer[] {
                    new Pointer(30, 0), new Pointer(-12, 20), new Pointer(.01, 0), new Pointer(0, 0)
                }) {
            var step = offset.step();
            var next = offset.remaining(step);
            assertEquals(offset, draw(offset, step, 0));
            assertEquals(next.x(), draw(offset, step, 1).x(), 1e-9);
            assertEquals(next.y(), draw(offset, step, 1).y(), 1e-9);
            double last = Math.hypot(offset.x(), offset.y());
            for (int i = 1; i <= 100; i++) {
                var p = draw(offset, step, i / 100.0);
                double length = Math.hypot(p.x(), p.y());
                assertTrue(length <= last + 1e-9);
                last = length;
            }
        }
    }

    @Test
    void midTickMouseIsImmediateAndDoesNotChangeThePlannedStep() {
        var offset = new Pointer(30, 4);
        var step = offset.step();
        for (double partial : new double[] {0, .25, .5, .75, 1}) {
            var a = draw(offset, step, partial);
            var b = draw(offset.move(2, -3), step, partial);
            assertEquals(2, b.x() - a.x(), 1e-9);
            assertEquals(-3, b.y() - a.y(), 1e-9);
        }
        assertEquals(new Pointer(0, 0), draw(new Pointer(0, 0), step, .7));
    }
}
