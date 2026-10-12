package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetShape;

import org.junit.jupiter.api.Test;

/** The camera projection of a planet of any shape, checked against the accepted fixed-size classes. */
class PlanetProjectionTest {
    static final PlanetShape STANDARD = PlanetShape.STANDARD, SMALL = new PlanetShape(1024);
    static final Point[] EYES = {
        new Point(1024.5, 3, 1024.5),
        new Point(40.25, 70, 2000.5),
        new Point(2040.5, 200, 12.5),
        new Point(600.5, 500, 1900.25),
        new Point(9.5, 1500, 9.5),
        new Point(1500.5, 9000, 300.5)
    };
    static final Point[] SAMPLES = {
        new Point(1000, 5, 1000),
        new Point(-300.5, 20, 700),
        new Point(2500.5, 0, 1800),
        new Point(900, 64, -800.5),
        new Point(100.25, -30, 3000),
        new Point(2047.5, 10, 2047.5)
    };

    static void same(Point a, Point b) {
        assertEquals(a, b);
    }

    @Test
    void standardShapeReproducesTheAcceptedPlanarChartRepairAndOrbit() {
        for (var f : Face.values())
            for (var eye : EYES) {
                var air = new GroundAir(f, eye, GroundAir.DEFAULT, true);
                var mine = new PlanetAir(STANDARD, f, eye, GroundAir.DEFAULT, true);
                assertEquals(air.weight, mine.weight);
                same(air.normal, mine.normal);
                same(air.basisU, mine.basisU);
                same(air.basisV, mine.basisV);
                same(air.offset, mine.offset);
                for (var p : SAMPLES) {
                    same(GroundAir.direction(p), mine.direction(p));
                    assertEquals(air.facing(p), mine.facing(p));
                    same(air.raw(p), mine.raw(p));
                    same(air.flat(p), mine.flat(p));
                }
                for (int view : new int[] {64, 256}) {
                    var gap = new GroundGap(f, eye, view, true, GroundAir.DEFAULT, true);
                    var g = new PlanetGap(STANDARD, f, eye, view, true, GroundAir.DEFAULT, true);
                    assertEquals(gap.clip, g.clip);
                    assertEquals(gap.strength, g.strength);
                    assertEquals(gap.distance, g.distance);
                    same(gap.corner, g.corner);
                    assertEquals(gap.residual().size(), g.residual().size());
                    for (var p : SAMPLES) {
                        same(gap.raw(p), g.raw(p));
                        same(gap.flat(p), g.flat(p));
                    }
                    for (var other : Face.values())
                        if (gap.chart.tile(other) != null)
                            same(
                                    gap.point(other, new Point(700.5, 4, 33.5)),
                                    g.point(other, new Point(700.5, 4, 33.5)));
                    var orbit = new GroundOrbit(new GroundSpace.Pose(f, eye), eye, view);
                    var o = new PlanetOrbit(STANDARD, new GroundTopology.Pose(f, eye), eye, view);
                    assertEquals(orbit.weight, o.weight);
                    assertEquals(orbit.clip, o.clip);
                    assertEquals(orbit.minimumFacing, o.minimumFacing);
                    same(orbit.normal, o.normal);
                    same(orbit.basisU, o.basisU);
                    same(orbit.basisV, o.basisV);
                    same(orbit.offset, o.offset);
                    assertEquals(orbit.observerFrame, o.observerFrame);
                    for (var other : Face.values())
                        for (var p :
                                new Point[] {
                                    new Point(300.5, 12, 1700.5),
                                    new Point(2047, 0, 1),
                                    new Point(1024, 100, 1024)
                                }) {
                            same(orbit.raw(other, p), o.raw(other, p));
                            same(orbit.point(other, p), o.point(other, p));
                            assertEquals(orbit.visible(other, p), o.visible(other, p));
                        }
                }
                assertEquals(
                        GroundOrbit.frame(new GroundSpace.Pose(f, eye)),
                        PlanetOrbit.frame(STANDARD, new GroundTopology.Pose(f, eye)));
            }
    }

    @Test
    void aSmallerPlanetIsTheSameProjectionAtHalfTheScale() {
        // Horizontal lengths scale with the planet; heights and the height-driven blend do not, so
        // compare at ground level.
        for (var f : Face.values())
            for (var eye :
                    new Point[] {
                        new Point(512.5, 0, 512.5),
                        new Point(20.25, 0, 1000.5),
                        new Point(300.5, 0, 900.25)
                    }) {
                var big = new Point(eye.x() * 2, 0, eye.z() * 2);
                var a = new PlanetAir(SMALL, f, eye, GroundAir.DEFAULT, true);
                var b = new PlanetAir(STANDARD, f, big, GroundAir.DEFAULT, true);
                // The frame is a finite difference over a span that is capped in blocks, hence the
                // tolerance.
                assertEquals(0, a.normal.sub(b.normal).length(), 1e-9);
                assertEquals(0, a.basisU.sub(b.basisU).length(), 5e-3);
                assertEquals(0, a.basisV.sub(b.basisV).length(), 5e-3);
                for (var p :
                        new Point[] {
                            new Point(500, 0, 500),
                            new Point(-150.5, 0, 350),
                            new Point(1250.5, 0, 900)
                        }) {
                    var q = new Point(p.x() * 2, 0, p.z() * 2);
                    assertEquals(0, a.direction(p).sub(b.direction(q)).length(), 1e-12);
                    var r = a.raw(p);
                    var s = b.raw(q);
                    assertEquals(s.x(), r.x() * 2, 4);
                    assertEquals(s.z(), r.z() * 2, 4);
                }
            }
        // Near the observer the chart is the identity: one block is one block, on any planet.
        var eye = new Point(700.5, 64, 300.5);
        var air = new PlanetAir(SMALL, Face.TOP, eye, GroundAir.DEFAULT, true);
        var near = air.raw(new Point(701.5, 0, 300.5));
        assertEquals(1, Math.hypot(near.x() - eye.x(), near.z() - eye.z()), 1e-3);
        // The repaired vertex is the planet's own, 1024 blocks along.
        var gap =
                new PlanetGap(
                        SMALL,
                        Face.TOP,
                        new Point(1000.5, 0, 1000.5),
                        128,
                        true,
                        GroundAir.DEFAULT,
                        false);
        assertEquals(new Point(1024, 0, 1024), gap.corner);
        assertTrue(gap.strength > 0);
        assertEquals(
                0,
                new PlanetGap(
                                STANDARD,
                                Face.TOP,
                                new Point(1000.5, 0, 1000.5),
                                128,
                                true,
                                GroundAir.DEFAULT,
                                false)
                        .strength);
        // From high orbit a point of the surface is drawn on a sphere of the planet's own radius
        // below the eye.
        var high = new Point(512.5, GroundOrbit.END + 500, 512.5);
        var orbit = new PlanetOrbit(SMALL, new GroundTopology.Pose(Face.TOP, high), high, 256);
        assertEquals(1, orbit.weight);
        var under = orbit.raw(Face.TOP, new Point(512.5, 0, 512.5));
        assertEquals(0, under.y(), 1e-6);
        var far = orbit.raw(Face.BOTTOM, new Point(512.5, 0, 512.5));
        assertEquals(-2 * SMALL.radius(), far.y(), 1e-2);
        var side = orbit.raw(Face.EAST, new Point(512.5, 0, 512.5));
        assertEquals(-SMALL.radius(), side.y(), 1e-2);
        assertEquals(SMALL.radius(), Math.hypot(side.x() - high.x(), side.z() - high.z()), 1e-2);
    }
}
