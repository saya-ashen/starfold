package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.util.Random;

class RemoteOcclusionTest {
    private static final Point ORIGIN = new Point(0, 0, 0);

    @Test
    void reusedPairsMatchOriginalAcrossRandomCandidatesAndTranslations() {
        var random = new Random(2026111301L);
        int hidden = 0, visible = 0;
        for (int pair = 0; pair < 128; pair++) {
            double scale = Math.scalb(1.0, random.nextInt(401) - 200);
            double size = (1 + random.nextDouble() * 10) * scale;
            var occluder = pair % 2 == 0 ? ORIGIN : unit(random).scale(size * 100);
            var eye = occluder.add(unit(random).scale(size * (1.01 + random.nextDouble() * 20)));
            var reused = new RemoteOcclusion(eye, occluder, size);
            for (int candidate = 0; candidate < 256; candidate++) {
                var center = occluder.add(unit(random).scale(random.nextDouble() * size * 3));
                double radius = random.nextDouble() * size;
                boolean expected =
                        original(center.x(), center.y(), center.z(), radius, eye, occluder, size);
                assertMatches(reused, eye, occluder, size, center, radius);
                if (expected) hidden++;
                else visible++;
            }
        }
        assertTrue(hidden > 1000, "too few hidden candidates: " + hidden);
        assertTrue(visible > 1000, "too few visible candidates: " + visible);
    }

    @Test
    void distanceAtAndAdjacentToOccluderSizeMatchesOriginal() {
        for (double distance : neighbors(1)) {
            var occluder = new Point(distance, 0, 0);
            var reused = new RemoteOcclusion(ORIGIN, occluder, 1);
            assertMatches(reused, ORIGIN, occluder, 1, new Point(4, 0, 0), 0);
            assertEquals(distance > 1, reused.hidden(4, 0, 0, 0));
            // Repeat after unrelated candidate queries on the same eye/occluder pair.
            assertMatches(reused, ORIGIN, occluder, 1, ORIGIN, 10);
            assertMatches(reused, ORIGIN, occluder, 1, new Point(4, 0, 0), 0);
        }
        for (double size : neighbors(1)) {
            var occluder = new Point(1, 0, 0);
            var reused = new RemoteOcclusion(ORIGIN, occluder, size);
            assertMatches(reused, ORIGIN, occluder, size, new Point(4, 0, 0), 0);
            assertEquals(size < 1, reused.hidden(4, 0, 0, 0));
        }
    }

    @Test
    void alongMinusRadiusAtAndAdjacentToOccluderPlaneMatchesOriginal() {
        var occluder = new Point(1, 0, 0);
        var reused = new RemoteOcclusion(ORIGIN, occluder, 0.5);
        for (double radius : new double[] {0, 0.25}) {
            double boundary = 1 + radius;
            for (double x : neighbors(boundary)) {
                assertMatches(reused, ORIGIN, occluder, 0.5, new Point(x, 0, 0), radius);
                assertEquals(x >= boundary, reused.hidden(x, 0, 0, radius));
            }
        }
    }

    @Test
    void exactAndAdjacentAngularTangenciesMatchOriginal() {
        var occluder = new Point(5, 0, 0);
        var reused = new RemoteOcclusion(ORIGIN, occluder, 3);
        // Both asin arguments are exactly 0.6; the candidate also clears the centre plane.
        for (double radius : neighbors(12)) {
            assertMatches(reused, ORIGIN, occluder, 3, new Point(20, 0, 0), radius);
            assertEquals(radius <= 12, reused.hidden(20, 0, 0, radius));
        }
        // Exercise the acos contribution too. The frozen floating-point formula decides
        // each neighboring result, rather than an idealized real-number tangent rule.
        double tangent = Math.asin(3.0 / 5) - Math.asin(1.0 / 20);
        for (double angle : neighbors(tangent)) {
            var center = new Point(20 * Math.cos(angle), 20 * Math.sin(angle), 0);
            assertMatches(reused, ORIGIN, occluder, 3, center, 1);
        }
        for (double y : neighbors(6)) {
            assertMatches(reused, ORIGIN, occluder, 3, new Point(8, y, 0), 0);
        }
        assertTrue(reused.hidden(20, 0, 0, 11));
        assertFalse(reused.hidden(20, 0, 0, 13));
    }

    @Test
    void eyeInsideCandidateOrOccluderAndZeroSizesMatchOriginal() {
        var occluder = new Point(5, 0, 0);
        var reused = new RemoteOcclusion(ORIGIN, occluder, 3);
        assertFalse(reused.hidden(0, 0, 0, 0));
        assertFalse(reused.hidden(0, 0, 0, 1));
        for (double radius : neighbors(2)) {
            assertMatches(reused, ORIGIN, occluder, 3, new Point(2, 0, 0), radius);
        }
        assertTrue(reused.hidden(20, 0, 0, 1));
        for (Point eye : new Point[] {ORIGIN, new Point(1, 0, 0), new Point(3, 0, 0)}) {
            var inside = new RemoteOcclusion(eye, ORIGIN, 3);
            assertMatches(inside, eye, ORIGIN, 3, new Point(-20, 0, 0), 1);
            assertFalse(inside.hidden(-20, 0, 0, 1));
        }
        for (double size : new double[] {-1, -0.0, 0.0, Double.MIN_VALUE}) {
            var zeroSize = new RemoteOcclusion(ORIGIN, occluder, size);
            assertMatches(zeroSize, ORIGIN, occluder, size, new Point(20, 0, 0), 0);
            assertMatches(zeroSize, ORIGIN, occluder, size, new Point(20, 0, 0), 1);
            assertEquals(size > 0, zeroSize.hidden(20, 0, 0, 0));
        }
    }

    @Test
    void veryLargeFiniteInputsPreserveOriginalOverflowBehavior() {
        for (double scale :
                new double[] {1e100, 1e150, 1e153, 1e154, 1e200, Double.MAX_VALUE / 32}) {
            for (Point occluder : new Point[] {ORIGIN, new Point(scale, -scale, scale)}) {
                var eye = occluder.add(new Point(-5 * scale, 0, 0));
                double size = 3 * scale;
                var reused = new RemoteOcclusion(eye, occluder, size);
                for (Point offset :
                        new Point[] {
                            ORIGIN,
                            new Point(15 * scale, 0, 0),
                            new Point(15 * scale, 10 * scale, -scale)
                        }) {
                    for (double radius : new double[] {0, scale, 12 * scale}) {
                        assertMatches(reused, eye, occluder, size, occluder.add(offset), radius);
                    }
                }
            }
        }
    }

    @Test
    void nanInfinitiesAndDegenerateInputsKeepLegacyBooleans() {
        double[] values = {
            Double.NEGATIVE_INFINITY,
            -Double.MAX_VALUE,
            -1,
            -0.0,
            0.0,
            Double.MIN_VALUE,
            1,
            Double.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NaN
        };
        var ordinaryOccluder = new Point(5, 0, 0);
        // Sweep every constructor argument independently, then every candidate argument.
        for (int parameter = 0; parameter < 7; parameter++) {
            for (double value : values) {
                double[] pair = {0, 0, 0, 5, 0, 0, 3};
                pair[parameter] = value;
                var eye = new Point(pair[0], pair[1], pair[2]);
                var occluder = new Point(pair[3], pair[4], pair[5]);
                double size = pair[6];
                var reused = new RemoteOcclusion(eye, occluder, size);
                assertMatches(reused, eye, occluder, size, new Point(20, 0, 0), 1);
                for (int component = 0; component < 4; component++) {
                    for (double candidateValue : values) {
                        double[] candidate = {20, 0, 0, 1};
                        candidate[component] = candidateValue;
                        assertMatches(
                                reused,
                                eye,
                                occluder,
                                size,
                                new Point(candidate[0], candidate[1], candidate[2]),
                                candidate[3]);
                        assertMatches(reused, eye, occluder, size, new Point(20, 0, 0), 1);
                    }
                }
            }
        }
        // Several exceptional components together must also retain Java's comparison results.
        var reused = new RemoteOcclusion(ORIGIN, ordinaryOccluder, 3);
        for (double x : values) {
            for (double y : values) {
                for (double z : values) {
                    for (double radius : values) {
                        assertMatches(
                                reused, ORIGIN, ordinaryOccluder, 3, new Point(x, y, z), radius);
                    }
                }
            }
        }
    }

    @Test
    void resultsAreIndependentOfPreviousCallsAndOtherPairs() {
        var eye = ORIGIN;
        var occluder = new Point(5, 0, 0);
        double[][] candidates = {
            {0, 0, 0, 1}, // eye inside candidate: early return
            {-20, 0, 0, 1}, // in front of centre plane: early return
            {20, 0, 0, 1}, // hidden: final comparison
            {20, 20, 0, 1}, // visible: final comparison
            {Double.NaN, 0, 0, 1},
            {Double.POSITIVE_INFINITY, 0, 0, 1},
            {20, 0, 0, Double.NaN},
            {20, 0, 0, 12}, // exact tangent
            {20, 0, 0, Math.nextUp(12.0)},
            {20, 0, 0, Math.nextDown(12.0)}
        };
        var otherEye = new Point(100, -50, 25);
        var otherCenter = new Point(90, -50, 25);
        var other = new RemoteOcclusion(otherEye, otherCenter, 1);
        for (int start = 0; start < candidates.length; start++) {
            for (int direction : new int[] {-1, 1}) {
                var reused = new RemoteOcclusion(eye, occluder, 3);
                for (int pass = 0; pass < 3; pass++) {
                    for (int step = 0; step < candidates.length; step++) {
                        var c =
                                candidates[
                                        Math.floorMod(start + direction * step, candidates.length)];
                        var center = new Point(c[0], c[1], c[2]);
                        assertMatches(reused, eye, occluder, 3, center, c[3]);
                        assertEquals(
                                new RemoteOcclusion(eye, occluder, 3)
                                        .hidden(c[0], c[1], c[2], c[3]),
                                reused.hidden(c[0], c[1], c[2], c[3]));
                        assertMatches(other, otherEye, otherCenter, 1, new Point(50, -50, 25), 0.5);
                    }
                }
            }
        }
    }

    @Test
    void everySampleOfAHiddenCandidateIsBehindTheOccluder() {
        var random = new Random(2026111302L);
        int hidden = 0;
        for (int pair = 0; pair < 128; pair++) {
            double size = 200 + random.nextDouble() * 3000;
            var occluder = pair % 2 == 0 ? ORIGIN : unit(random).scale(500);
            var eye = occluder.add(unit(random).scale(size * (1.01 + random.nextDouble() * 20)));
            var reused = new RemoteOcclusion(eye, occluder, size);
            for (int candidate = 0; candidate < 128; candidate++) {
                var center = occluder.add(unit(random).scale(random.nextDouble() * size * 3));
                double radius = random.nextDouble() * size;
                assertMatches(reused, eye, occluder, size, center, radius);
                if (!reused.hidden(center.x(), center.y(), center.z(), radius)) continue;
                hidden++;
                assertTrue(occulted(eye, center, occluder, size));
                // Check both the bounding surface and the volume it encloses.
                for (int sample = 0; sample < 64; sample++) {
                    var direction = unit(random);
                    var surface = center.add(direction.scale(radius));
                    var interior =
                            center.add(direction.scale(radius * Math.cbrt(random.nextDouble())));
                    assertTrue(
                            occulted(eye, surface, occluder, size), "visible surface " + surface);
                    assertTrue(
                            occulted(eye, interior, occluder, size),
                            "visible interior " + interior);
                }
            }
        }
        assertTrue(hidden > 1000, "too few hidden spheres to check conservatism: " + hidden);
    }

    private static void assertMatches(
            RemoteOcclusion reused,
            Point eye,
            Point occluder,
            double size,
            Point center,
            double radius) {
        assertEquals(
                original(center.x(), center.y(), center.z(), radius, eye, occluder, size),
                reused.hidden(center.x(), center.y(), center.z(), radius),
                () ->
                        "eye="
                                + eye
                                + ", occluder="
                                + occluder
                                + ", size="
                                + size
                                + ", candidate="
                                + center
                                + ", radius="
                                + radius);
    }

    /** Frozen PlanetRemoteRenderer.hidden from the pre-optimization remote-baseline snapshot. */
    private static boolean original(
            double x, double y, double z, double radius, Point eye, Point occluder, double size) {
        double ax = occluder.x() - eye.x(),
                ay = occluder.y() - eye.y(),
                az = occluder.z() - eye.z();
        double d = Math.sqrt(ax * ax + ay * ay + az * az);
        if (size <= 0 || d <= size) return false;
        double vx = x - eye.x(), vy = y - eye.y(), vz = z - eye.z();
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length <= radius) return false;
        double along = (vx * ax + vy * ay + vz * az) / d;
        if (along - radius < d) return false;
        return Math.acos(Math.min(1, along / length)) + Math.asin(radius / length)
                <= Math.asin(size / d);
    }

    /** Independent segment/sphere intersection, matching the shader's geometric discard. */
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

    private static double[] neighbors(double value) {
        return new double[] {Math.nextDown(value), value, Math.nextUp(value)};
    }

    private static Point unit(Random random) {
        var p = new Point(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return p.scale(1 / p.length());
    }
}
