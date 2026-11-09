package dev.starfold.render;

import dev.starfold.geometry.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlanetRemoteCullingTest {
    /** The shader's own discard: the sight line to the point crosses the occluder sphere. */
    private static boolean occulted(Point eye, Point target, Point center, double radius) {
        var d = target.sub(eye);
        double distance = d.length();
        d = d.scale(1 / distance);
        var o = eye.sub(center);
        double b = o.dot(d), c = o.dot(o) - radius * radius, disc = b * b - c;
        if (disc < 0) return false;
        double root = Math.sqrt(disc);
        return -b + root > 0.0001 && -b - root < distance - 0.0001;
    }

    @Test
    void everyPointOfAHiddenSphereIsBehindTheOccluder() {
        var random = new Random(20261029);
        int hidden = 0;
        for (int run = 0; run < 20000; run++) {
            double size = 200 + random.nextDouble() * 3000;
            var occluder = new Point(random.nextGaussian() * 500, random.nextGaussian() * 500, random.nextGaussian() * 500);
            var eye = occluder.add(unit(random).scale(size * (1.01 + random.nextDouble() * 20)));
            var center = occluder.add(unit(random).scale(random.nextDouble() * size * 3));
            double radius = random.nextDouble() * size;
            if (!PlanetRemoteRenderer.hidden(center.x(), center.y(), center.z(), radius, eye, occluder, size)) continue;
            hidden++;
            for (int i = 0; i < 64; i++) {
                var point = center.add(unit(random).scale(radius * Math.cbrt(random.nextDouble())));
                assertTrue(occulted(eye, point, occluder, size), "visible point " + point);
            }
        }
        assertTrue(hidden > 1000, "too few hidden cases: " + hidden);
    }

    @Test
    void nothingIsHiddenFromInsideOrInFrontOfTheOccluder() {
        var origin = new Point(0, 0, 0);
        assertFalse(PlanetRemoteRenderer.hidden(0, 0, -900, 10, new Point(0, 0, 500), origin, 1000));
        assertFalse(PlanetRemoteRenderer.hidden(0, 0, 1500, 10, new Point(0, 0, 3000), origin, 1000));
        assertTrue(PlanetRemoteRenderer.hidden(0, 0, -1500, 10, new Point(0, 0, 3000), origin, 1000));
        assertFalse(PlanetRemoteRenderer.hidden(0, 0, -1500, 10, new Point(0, 0, 3000), origin, 0));
    }

    private static Point unit(Random random) {
        var p = new Point(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return p.scale(1 / p.length());
    }
}
