package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.file.*;

/** Reproduce rear-chart wrap without Minecraft; freeze every unrelated shader byte. */
class GroundAntipodeTest {
    static final String OLD =
            "if(SpaceLens.x<=0.0) return ordinary;\n    float r=max(0.000001,length(view.xyz));\n    float d=max(0.000001*r,r-view.z);";
    static final String FIX =
            "spaceFacing=-view.z;\n    if(SpaceLens.x<=0.0) return ordinary;\n    float r=max(0.000001,length(view.xyz));\n    // Bound the lens chart by its screen-corner ray, not by an arbitrary hemisphere.\n    // Rear antipodal triangles must never wrap across the finite front viewport.\n    vec2 corner=1.0/SpaceLens.yz;\n    float rho2=dot(corner,corner);\n    float cosine=(4.0-rho2)/(4.0+rho2);\n    spaceFacing=-view.z-r*cosine;\n    float d=max(r*(1.0+cosine),r-view.z);";

    double cosine(GroundSpaceOptics.Lens lens) {
        double rho2 = 1 / (lens.x() * lens.x()) + 1 / (lens.y() * lens.y());
        return (4 - rho2) / (4 + rho2);
    }

    double facing(Point p, GroundSpaceOptics.Lens lens) {
        return -p.z() - p.length() * cosine(lens);
    }

    Point bounded(Point p, GroundSpaceOptics.Lens lens) {
        double r = p.length(), d = Math.max(r * (1 + cosine(lens)), r - p.z());
        return new Point(2 * lens.x() * p.x() / d, 2 * lens.y() * p.y() / d, r);
    }

    GroundSpaceOptics.Lens lens(double fov, double aspect) {
        return GroundSpaceOptics.lens(
                new Matrix4f()
                        .perspective((float) Math.toRadians(fov), (float) aspect, .05f, 100000),
                5000);
    }

    @Test
    void oldRearTriangleSpansCentreAndScreenButNewFragmentsAreRejected() {
        var l = lens(70, 16. / 9);
        var triangle =
                new Point[] {new Point(-1, -1, 100), new Point(1, -1, 100), new Point(0, 1, 100)};
        double minX = 10000, maxX = -10000, minY = 10000, maxY = -10000;
        for (var p : triangle) {
            var old = GroundSpaceOptics.stereo(p, l.y());
            minX = Math.min(minX, old.x());
            maxX = Math.max(maxX, old.x());
            minY = Math.min(minY, old.y());
            maxY = Math.max(maxY, old.y());
            assertTrue(facing(p, l) < 0);
            assertTrue(Math.abs(bounded(p, l).x()) < 1);
            assertTrue(Math.abs(bounded(p, l).y()) < 1);
        }
        assertTrue(minX < -1 && maxX > 1 && minY < -1 && maxY > 1);
        for (int i = 0; i <= 100; i++)
            for (int j = 0; j <= 100 - i; j++) {
                double a = i / 100., b = j / 100., c = 1 - a - b;
                assertTrue(
                        a * facing(triangle[0], l)
                                        + b * facing(triangle[1], l)
                                        + c * facing(triangle[2], l)
                                < 0);
            }
    }

    @Test
    void exactAntipodeAndTinyMouseSweepRemainFiniteAndInvisible() {
        for (double fov : new double[] {30, 70, 110})
            for (double aspect : new double[] {.75, 1, 16. / 9, 3.5}) {
                var l = lens(fov, aspect);
                for (int i = -100; i <= 100; i++) {
                    var p = new Point(i * .00001, .0001, 10000);
                    var out = bounded(p, l);
                    assertTrue(out.finite());
                    assertTrue(Math.abs(out.x()) < 1 && Math.abs(out.y()) < 1);
                    assertTrue(facing(p, l) < 0);
                }
            }
    }

    @Test
    void visibleViewportRaysKeepExactlyTheOldCircularProjection() {
        for (double fov : new double[] {30, 70, 110})
            for (double aspect : new double[] {.75, 1, 16. / 9, 3.5}) {
                var l = lens(fov, aspect);
                for (int x = -9; x <= 9; x++)
                    for (int y = -9; y <= 9; y++) {
                        double u = x / 10. / l.x(), v = y / 10. / l.y(), rho2 = u * u + v * v;
                        var ray =
                                new Point(
                                        4 * u / (4 + rho2),
                                        4 * v / (4 + rho2),
                                        (rho2 - 4) / (rho2 + 4));
                        assertTrue(facing(ray, l) > 0);
                        var now = bounded(ray, l);
                        var old = GroundSpaceOptics.stereo(ray, l.y());
                        assertEquals(old.x() * l.x() / l.y(), now.x(), 1e-10);
                        assertEquals(old.y(), now.y(), 1e-10);
                    }
            }
    }

    @Test
    void ultrawideRearSideRaysInsideViewportAreNotArbitrarilyHemisphereClipped() {
        var l = lens(110, 3.5);
        var p = new Point(Math.sin(Math.toRadians(100)), 0, -Math.cos(Math.toRadians(100)));
        assertTrue(p.z() > 0);
        assertTrue(facing(p, l) > 0);
        assertTrue(Math.abs(bounded(p, l).x()) < 1);
    }
}
