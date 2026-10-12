package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Cell;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;

/** Pure geometry, no renderer, Minecraft tick, player movement or network is exercised. */
class BentStripGeometryTest {
    private final BentStripGeometry bend = new BentStripGeometry(16, 2);
    private static final double EPS = 1e-8;

    private static void close(Point a, Point b) {
        assertEquals(0, a.sub(b).length(), EPS);
    }

    @Test
    void sampledRollIsContinuousAtTickBoundariesInBothDirections() {
        for (double step : new double[] {.22, -.22}) {
            double previousAngle = Double.NaN;
            double start = step > 0 ? 14 : 18;
            for (int tick = 0; tick < 20; tick++)
                for (int frame = 0; frame <= 8; frame++) {
                    double u = start + step * (tick + frame / 8.0);
                    var up = bend.up(u);
                    double angle = Math.atan2(up.x(), up.y());
                    if (Double.isFinite(previousAngle)) {
                        assertTrue(Math.abs(angle - previousAngle) <= Math.abs(step) / 16 + EPS);
                        if (frame == 0)
                            assertEquals(previousAngle, angle, EPS, "No new-tick roll jump");
                    }
                    previousAngle = angle;
                }
        }
        // Reproduce the old mismatch: moving .22 blocks/tick advances roll by .11 radians.
        var old = bend.up(15.5);
        var next = bend.up(15.72);
        assertEquals(.11, Math.atan2(next.x(), next.y()) - Math.atan2(old.x(), old.y()), EPS);
    }

    @Test
    void maskedFluidMeshUsesActualHeightAndOriginalFace() {
        for (int u : new int[] {10, 15, 16, 20}) {
            var cell = new Cell(u, 0, 4);
            var mesh = bend.mesh(cell, 16, 5.0 / 9, 1 << BentStripGeometry.Face.UP.ordinal());
            assertFalse(mesh.isEmpty());
            for (var t : mesh) {
                assertEquals(BentStripGeometry.Face.UP, t.face());
                assertEquals(cell, t.owner());
                for (var p : new Point[] {t.a(), t.b(), t.c()})
                    assertEquals(5.0 / 9, bend.inverse(p).y(), EPS);
            }
            var hit =
                    BentStripGeometry.pick(
                            mesh,
                            bend.project(new Point(u + .5, 3, 4.5)),
                            bend.up(u + .5).scale(-1),
                            5);
            assertNotNull(hit);
            assertEquals(BentStripGeometry.Face.UP, hit.face());
            assertEquals(3 - 5.0 / 9, hit.distance(), EPS);
        }
    }

    @Test
    void facetedFeetLieOnRenderedFloorIncludingPieceBoundaries() {
        for (int i = 0; i < 512; i++) {
            double u = i / 16.0 + .017;
            var p = bend.facetedProject(new Point(u, 0, 1.5), 16);
            var up = bend.up(u);
            var mesh = bend.mesh(new Cell((int) Math.floor(u), -1, 1), 16, 1, 2);
            var hit = BentStripGeometry.pick(mesh, p.add(up.scale(.5)), up.scale(-1), 1);
            assertNotNull(hit, "u=" + u);
            close(p, hit.position());
        }
    }

    @Test
    void facetedCameraRemainsContinuousAcrossStorageSeam() {
        for (double u : new double[] {bend.start(), 16, bend.end()})
            for (double h : new double[] {0, 1.62}) {
                var a = bend.facetedProject(new Point(u - 1e-8, h, 1), 16);
                var b = bend.facetedProject(new Point(u + 1e-8, h, 1), 16);
                assertTrue(a.sub(b).length() < 1e-6);
            }
    }

    @Test
    void emptyMasksAndInvalidHeightsCannotCreatePhantomWater() {
        assertTrue(bend.mesh(new Cell(16, 0, 4), 16, 1, 0).isEmpty());
        for (double height : new double[] {0, -1, 1.1, Double.NaN})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> bend.mesh(new Cell(16, 0, 4), 16, height, 63));
    }

    @Test
    void roundTripAcrossAllCellsAndSupportedThickness() {
        for (int x = 0; x <= 256; x++)
            for (int y = -8; y <= 32; y++)
                for (int z = 0; z <= 9; z += 3) {
                    var local = new Point(x / 8.0, y / 8.0, z);
                    close(local, bend.inverse(bend.project(local)));
                }
    }

    @Test
    void bothOwnersHaveIdenticalSeamAtEveryHeight() {
        for (double h = -1; h <= 4; h += .125)
            for (int z = 0; z <= 9; z++) {
                var left = bend.project(new Point(16, h, z));
                var right = bend.project(new Point(StripTopology.WIDTH + 0, h, z));
                close(left, right);
                var nearLeft = bend.project(new Point(16 - 1e-7, h, z));
                var nearRight = bend.project(new Point(16 + 1e-7, h, z));
                assertTrue(nearLeft.sub(nearRight).length() < 1e-6);
            }
    }

    @Test
    void piecewiseBoundariesRemainContinuousAtAllHeights() {
        for (double h = -1; h <= 4; h += .25)
            for (double u : new double[] {bend.start(), bend.end()}) {
                assertTrue(
                        bend.project(new Point(u - 1e-8, h, 0))
                                        .sub(bend.project(new Point(u + 1e-8, h, 0)))
                                        .length()
                                < 1e-6);
            }
    }

    @Test
    void farFacesKeepUnitRigidBlocksAndProperUpDirections() {
        close(new Point(0, 1, 0), bend.up(0));
        close(new Point(1, 0, 0), bend.up(32));
        for (double u : new double[] {2, 28}) {
            var p = bend.project(new Point(u, 0, 0));
            var tangent = bend.project(new Point(u + 1, 0, 0)).sub(p);
            var up = bend.project(new Point(u, 1, 0)).sub(p);
            assertEquals(1, tangent.length(), EPS);
            assertEquals(1, up.length(), EPS);
            assertEquals(0, tangent.dot(up), EPS);
        }
    }

    @Test
    void supportedThicknessHasPositiveVolumeScale() {
        for (double u = 0; u <= 32; u += .125)
            for (double h = -1; h <= 4; h += .125)
                assertTrue(bend.jacobian(new Point(u, h, 0)) >= .5);
        assertEquals(.5, bend.jacobian(new Point(16, -1, 0)), EPS);
        assertEquals(3, bend.jacobian(new Point(16, 4, 0)), EPS);
    }

    @Test
    void singularAndDeeperCrustRejectedRatherThanOverlapped() {
        assertThrows(IllegalArgumentException.class, () -> bend.project(new Point(16, -2, 0)));
        assertThrows(IllegalArgumentException.class, () -> bend.project(new Point(16, -3, 0)));
        assertThrows(
                IllegalArgumentException.class, () -> bend.inverse(new Point(bend.start(), -2, 0)));
        assertThrows(
                IllegalArgumentException.class, () -> bend.project(new Point(Double.NaN, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new BentStripGeometry(16, 0));
    }

    @Test
    void neighbourMeshesShareExactSeamVertices() {
        var left = bend.mesh(new Cell(15, 0, 4), 16);
        var right = bend.mesh(new Cell(16, 0, 4), 16);
        var a = new HashSet<Point>();
        var b = new HashSet<Point>();
        for (var t : left)
            for (var p : new Point[] {t.a(), t.b(), t.c()})
                if (Math.abs(bend.inverse(p).x() - 16) < EPS) a.add(p);
        for (var t : right)
            for (var p : new Point[] {t.a(), t.b(), t.c()})
                if (Math.abs(bend.inverse(p).x() - 16) < EPS) b.add(p);
        assertEquals(4, a.size());
        assertEquals(a, b);
    }

    @Test
    void rayPickingReturnsRealOwnerOnBothSides() {
        var mesh = new ArrayList<BentStripGeometry.Triangle>();
        mesh.addAll(bend.mesh(new Cell(15, 0, 4), 16));
        mesh.addAll(bend.mesh(new Cell(16, 0, 4), 16));
        for (int u : new int[] {15, 16}) {
            var hit =
                    BentStripGeometry.pick(
                            mesh,
                            bend.project(new Point(u + .5, 3, 4.5)),
                            bend.up(u + .5).scale(-1),
                            5);
            assertNotNull(hit);
            assertEquals(new Cell(u, 0, 4), hit.owner());
            assertEquals(u == 16, hit.owner().rightOwner());
            assertEquals(u == 16 ? 0 : 15, hit.owner().ownerLocalX());
            assertEquals(2, hit.distance(), EPS);
        }
    }

    @Test
    void rayReachAndMissesAreRespected() {
        var mesh = bend.mesh(new Cell(15, 0, 4), 16);
        var origin = bend.project(new Point(15.5, 3, 4.5));
        var direction = bend.up(15.5).scale(-1);
        assertNull(BentStripGeometry.pick(mesh, origin, direction, 1));
        assertNull(BentStripGeometry.pick(mesh, origin, direction.scale(-1), 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> BentStripGeometry.pick(mesh, origin, new Point(0, 0, 0), 10));
    }

    @Test
    void meshTrianglesAreFiniteNondegenerateAndRetainOwner() {
        for (int u = 0; u < 32; u++) {
            var cell = new Cell(u, -1, 4);
            for (var t : bend.mesh(cell, 16)) {
                assertEquals(cell, t.owner());
                assertTrue(t.a().finite() && t.b().finite() && t.c().finite());
                assertTrue(t.b().sub(t.a()).cross(t.c().sub(t.a())).length() > 1e-8);
            }
        }
    }

    @Test
    void separatedLogicalInteriorsCannotAliasTheSameWorldPoint() {
        var points = new HashSet<Point>();
        for (int u = 0; u < 32; u++)
            for (int h = -1; h < 4; h++) {
                var local = new Point(u + .5, h + .5, 4.5);
                var world = bend.project(local);
                assertTrue(points.add(world));
                close(local, bend.inverse(world));
            }
    }
}
