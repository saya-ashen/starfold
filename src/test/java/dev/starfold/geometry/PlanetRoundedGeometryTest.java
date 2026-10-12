package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetSpace.*;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.*;

/** Production rounded geometry, actual shared mesh/CCD; historical sphere tests stay pinned. */
class PlanetRoundedGeometryTest {
    private final PlanetSpace s = PlanetSpace.STANDARD;

    private record Quality(
            double aspect, double p95, double within10, double angle, double black, double width) {}

    private Quality quality(PlanetRoundedGeometry g) {
        var aspects = new ArrayList<Double>();
        double angle = 0, black = 0, width = 10;
        for (int u = 0; u < 64; u++)
            for (int v = 0; v < 64; v++) {
                if ((u == 0 || u == 63) && (v == 0 || v == 63)) continue;
                Point[] p = {p(g, u, v), p(g, u + 1, v), p(g, u + 1, v + 1), p(g, u, v + 1)};
                double min = 10, max = 0;
                for (int i = 0; i < 4; i++) {
                    var a = p[(i + 1) % 4].sub(p[i]);
                    var b = p[(i + 3) % 4].sub(p[i]);
                    double cos = Math.clamp(a.dot(b) / (a.length() * b.length()), -1, 1),
                            err = Math.abs(Math.toDegrees(Math.acos(cos)) - 90);
                    min = Math.min(min, a.length());
                    max = Math.max(max, a.length());
                    angle = Math.max(angle, err);
                    if (u == 0 || u == 63 || v == 0 || v == 63) black = Math.max(black, err);
                    width =
                            Math.min(
                                    width,
                                    Math.min(a.length(), b.length()) * Math.sqrt(1 - cos * cos));
                }
                aspects.add(max / min);
            }
        aspects.sort(Double::compare);
        return new Quality(
                aspects.getLast(),
                aspects.get((int) (.95 * (aspects.size() - 1))),
                aspects.stream().filter(x -> x <= 1.1).count() / (double) aspects.size(),
                angle,
                black,
                width);
    }

    private Point p(PlanetRoundedGeometry g, double u, double v) {
        return g.point(new Point((u - 32) / 32, 1, (v - 32) / 32), 0);
    }

    private Quaterniond facing(Point p, Point toward) {
        var up = s.up(p);
        var f = PlanetSpace.unit(toward.sub(up.scale(toward.dot(up))));
        var l = up.cross(f);
        return new Quaterniond()
                .setFromNormalized(
                        new Matrix3d(
                                l.x(), l.y(), l.z(), up.x(), up.y(), up.z(), f.x(), f.y(), f.z()));
    }

    private PlanetMotion.Input input(
            long i, Quaterniond q, float forward, float rise, boolean direct, boolean fast) {
        return new PlanetMotion.Input(i, q.x, q.y, q.z, q.w, forward, 0, rise, direct, fast);
    }

    @Test
    void selectedShapeIsBetweenSphereAndCubeWithHonestAngleTradeoff() {
        assertEquals(Shape.ROUNDED, s.shape);
        assertEquals(12, s.rounded.flat);
        assertEquals(24, s.rounded.flat * 2);
        double ratio = s.onRay(new Point(1, 1, 1), 0).length() / s.radius;
        assertTrue(ratio > 1.23 && ratio < 1.24);
        var q = quality(s.rounded);
        System.out.println("ROUNDED ORDINARY " + q);
        assertEquals(1.47782303714458, q.aspect, 1e-10);
        assertTrue(q.p95 < 1.384 && q.within10 > .728);
        assertTrue(q.angle < 13.7 && q.black < 8.04 && q.width > .7339);
        assertTrue(
                q.width * (1 - 4 / s.rounded.roundRadius)
                        > 2 * (PlanetMotion.RADIUS + PlanetMotion.SKIN));
    }

    @Test
    void roundnessComparisonRejectsTooNarrowInnerBandRatherThanOnlyOptimizingLooks() {
        for (double a : new double[] {4, 8, 12, 16}) {
            var g = new PlanetRoundedGeometry(64, a / 32);
            var q = quality(g);
            System.out.println("ROUNDNESS flat=" + a + " " + q);
            double inner = q.width * (1 - 4 / g.roundRadius);
            if (a == 16) assertTrue(inner < 2 * (PlanetMotion.RADIUS + PlanetMotion.SKIN));
            else assertTrue(inner > .604);
        }
    }

    @Test
    void centralTwentyFourByTwentyFourCellsAreUnitCubesAtEveryLayer() {
        for (var f : Face.values())
            for (int u = 20; u < 44; u++)
                for (int v = 20; v < 44; v++)
                    for (int h = -4; h < 4; h++) {
                        Point a = s.project(f, u, h, v),
                                b = s.project(f, u + 1, h, v),
                                c = s.project(f, u, h, v + 1),
                                d = s.project(f, u, h + 1, v);
                        assertEquals(1, b.sub(a).length(), 1e-12);
                        assertEquals(1, c.sub(a).length(), 1e-12);
                        assertEquals(1, d.sub(a).length(), 1e-12);
                        assertEquals(0, b.sub(a).dot(c.sub(a)), 1e-12);
                        assertEquals(0, b.sub(a).dot(d.sub(a)), 1e-12);
                    }
    }

    @Test
    void canonicalPointsAndInverseAgreeOnEveryFaceAndHeight() {
        for (var f : Face.values())
            for (int u = 0; u <= 64; u++)
                for (int v = 0; v <= 64; v++)
                    for (int h : new int[] {-4, 0, 4}) {
                        var p = s.point(s.vertex(f, u, h, v));
                        assertEquals(p, s.project(f, u, h, v));
                        var c = s.coordinates(f, p);
                        assertEquals(u, c.u(), 1e-9);
                        assertEquals(v, c.v(), 1e-9);
                        assertEquals(h, c.h(), 1e-12);
                        assertEquals(1, s.project(f, u, h + 1, v).sub(p).length(), 1e-12);
                    }
    }

    @Test
    void randomInverseAndRawJacobianStayPositiveEvenAtPlaneCylinderJoins() {
        var random = new Random(4206);
        double e = 1e-5;
        for (var f : Face.values())
            for (int i = 0; i < 2000; i++) {
                double u = random.nextDouble() * 64,
                        v = random.nextDouble() * 64,
                        h = -4 + random.nextDouble() * 8;
                var p = s.project(f, u, h, v);
                var c = s.coordinates(f, p);
                assertEquals(u, c.u(), 1e-9);
                assertEquals(v, c.v(), 1e-9);
                assertEquals(h, c.h(), 1e-12);
                var du = s.project(f, u + e, h, v).sub(s.project(f, u - e, h, v)).scale(.5 / e);
                var dv = s.project(f, u, h, v + e).sub(s.project(f, u, h, v - e)).scale(.5 / e);
                var n = s.project(f, u, h + 1, v).sub(p);
                assertTrue(du.cross(dv).dot(n.scale(-1)) > .35);
            }
    }

    @Test
    void allPresentFacetsAtAllHeightsStayNondegenerateAndOutward() {
        for (var f : Face.values())
            for (int u = 0; u < 64; u++)
                for (int v = 0; v < 64; v++)
                    for (int h = -4; h < 4; h++) {
                        var c = new Cell(f, u, h, v);
                        if (!s.hasVolume(c)) continue;
                        var center = s.project(f, u + .5, h + .5, v + .5);
                        for (var side : Side.values())
                            for (var t : s.face(c, side)) {
                                assertTrue(t.b().sub(t.a()).cross(t.c().sub(t.a())).length() > .2);
                                assertTrue(
                                        t.normal().dot(t.a().sub(center)) > .15,
                                        "Inverted " + c + " " + side);
                            }
                        Point[] p = {
                            s.project(f, u, h, v),
                            s.project(f, u + 1, h, v),
                            s.project(f, u + 1, h, v + 1),
                            s.project(f, u, h, v + 1)
                        };
                        for (int i = 0; i < 4; i++)
                            assertTrue(
                                    p[(i + 1) % 4]
                                                    .sub(p[i])
                                                    .cross(p[(i + 2) % 4].sub(p[(i + 1) % 4]))
                                                    .dot(center)
                                            < 0,
                                    "Raw fold " + c);
                    }
    }

    @Test
    void allDirectedSeamsShareExactTrianglesAndStorageKeysStayUnchanged() {
        var old = new PlanetSpace(64, -4, 4);
        for (var f : Face.values())
            for (int edge = 0; edge < 4; edge++)
                for (int k = 0; k < 64; k++)
                    for (int h : new int[] {-4, 0, 3}) {
                        var c =
                                new Cell(
                                        f,
                                        edge < 2 ? (edge == 0 ? 0 : 63) : k,
                                        h,
                                        edge >= 2 ? (edge == 2 ? 0 : 63) : k);
                        var side = List.of(Side.WEST, Side.EAST, Side.NORTH, Side.SOUTH).get(edge);
                        var n = s.neighbor(c, side);
                        assertEquals(old.neighbor(c, side), n);
                        assertEquals(old.index(c), s.index(c));
                        Set<Set<Point>> a = new HashSet<>(), b = new HashSet<>();
                        for (var t : s.face(c, side)) a.add(Set.of(t.a(), t.b(), t.c()));
                        for (var t : s.face(n.target(), n.incoming()))
                            b.add(Set.of(t.a(), t.b(), t.c()));
                        assertEquals(a, b);
                    }
    }

    @Test
    void seamGridContinuationHasAnExplicitMeasuredTradeoffNotAHiddenCrack() {
        var kinks = new ArrayList<Double>();
        for (var f : Face.values())
            for (int edge = 0; edge < 4; edge++)
                for (int k = 1; k < 64; k++) {
                    var toward = (edge < 2 ? f.u : f.v).scale(edge % 2 == 0 ? -1 : 1);
                    var along = edge < 2 ? f.v : f.u;
                    var border =
                            f.normal.scale(64).add(toward.scale(64)).add(along.scale(2 * k - 64));
                    var left = border.sub(toward.scale(2));
                    var right = border.sub(f.normal.scale(2));
                    var p =
                            s.point(
                                    new Vertex(
                                            (int) border.x(),
                                            (int) border.y(),
                                            (int) border.z(),
                                            0));
                    var a = s.point(new Vertex((int) left.x(), (int) left.y(), (int) left.z(), 0));
                    var b =
                            s.point(
                                    new Vertex(
                                            (int) right.x(), (int) right.y(), (int) right.z(), 0));
                    var n =
                            s.point(
                                            new Vertex(
                                                    (int) border.x(),
                                                    (int) border.y(),
                                                    (int) border.z(),
                                                    1))
                                    .sub(p);
                    var incoming = p.sub(a);
                    var outgoing = b.sub(p);
                    incoming = incoming.sub(n.scale(incoming.dot(n)));
                    outgoing = outgoing.sub(n.scale(outgoing.dot(n)));
                    kinks.add(
                            Math.toDegrees(
                                    Math.acos(
                                            Math.clamp(
                                                    incoming.dot(outgoing)
                                                            / (incoming.length()
                                                                    * outgoing.length()),
                                                    -1,
                                                    1))));
                }
        kinks.sort(Double::compare);
        assertEquals(1512, kinks.size());
        assertEquals(16.109044846266, kinks.getLast(), 1e-9);
        assertTrue(kinks.get((int) (.95 * (kinks.size() - 1))) < 8.6);
    }

    @Test
    void inverseStaysStableAtTipsAndBothPlaneCylinderTransitions() {
        for (var f : Face.values())
            for (double base : new double[] {0, 20, 44, 64})
                for (double e : new double[] {0, 1e-9, 1e-6, .001})
                    for (double v :
                            new double[] {0, 1e-9, 20, 20 + 1e-9, 44 - 1e-9, 44, 64 - 1e-9, 64}) {
                        double u = base == 64 ? base - e : base + e;
                        var c = s.coordinates(f, s.project(f, u, -4, v));
                        assertEquals(u, c.u(), 1e-9);
                        assertEquals(v, c.v(), 1e-9);
                        assertEquals(-4, c.h(), 1e-12);
                    }
    }

    @Test
    void surfaceHeightAndBodyUpAreConsistentAcrossTheWholeShape() {
        for (var f : Face.values())
            for (double u : new double[] {.5, 8, 19.99, 20, 20.01, 32, 43.99, 44, 44.01, 56, 63.5})
                for (double v : new double[] {.5, 20, 32, 44, 63.5})
                    for (double h : new double[] {-4, 0, 10, 80}) {
                        var p = s.project(f, u, h, v);
                        assertEquals(h, s.altitude(p), 1e-12);
                        var normal = s.project(f, u, h + 1, v).sub(p);
                        assertTrue(normal.dot(s.up(p)) > .9999);
                        assertEquals(h, s.altitude(s.onRay(p, h)), 1e-12);
                        var moved = s.rounded.onAltitude(p.add(f.u.scale(.2)), h);
                        assertEquals(h, s.altitude(moved), 1e-12);
                    }
    }

    @Test
    void upGradientAndCurvatureBoundMatchFiniteDifferencesAtTransitions() {
        double e = 1e-4;
        var n = PlanetSpace.unit(new Point(1, 2, 3));
        for (double x : new double[] {-13, -12.25, -12, -11.75, 0, 11.75, 12, 12.25, 13, 30}) {
            var p = new Point(x, s.radius + 2, 12);
            var gradient = s.rounded.upGradient(p, n);
            for (var d : List.of(new Point(1, 0, 0), new Point(0, 1, 0), new Point(0, 0, 1))) {
                double numeric =
                        s.up(p.add(d.scale(e))).sub(s.up(p.sub(d.scale(e)))).dot(n) / (2 * e);
                assertEquals(numeric, gradient.dot(d), 1e-8);
                double second =
                        s.up(p.add(d.scale(e)))
                                        .add(s.up(p.sub(d.scale(e))))
                                        .sub(s.up(p).scale(2))
                                        .length()
                                / (e * e);
                assertTrue(second < s.rounded.upCurvature(p.sub(d), d.scale(2)) / 4 + 1e-6);
            }
        }
    }
}
