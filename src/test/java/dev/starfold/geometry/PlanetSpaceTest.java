package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetSpace.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class PlanetSpaceTest {
    private final PlanetSpace space = new PlanetSpace(16, -3, 4);

    private static Set<Set<Point>> geometry(List<Triangle> ts) {
        var out = new HashSet<Set<Point>>();
        for (var t : ts) out.add(Set.of(t.a(), t.b(), t.c()));
        return out;
    }

    @Test
    void everyDirectedNeighborHasAnExactReverseAndIdenticalSharedTriangles() {
        for (var face : Face.values())
            for (int u = 0; u < space.size; u++)
                for (int v = 0; v < space.size; v++) {
                    var c = new Cell(face, u, 0, v);
                    for (var side : Side.values()) {
                        var route = space.neighbor(c, side);
                        var back = space.neighbor(route.target(), route.incoming());
                        assertEquals(c, back.target());
                        assertEquals(side, back.incoming());
                        if (space.hasVolume(c) == space.hasVolume(route.target()))
                            assertEquals(
                                    geometry(space.face(c, side)),
                                    geometry(space.face(route.target(), route.incoming())),
                                    c + " " + side);
                        else { // A core hole has a wall owned only by its ordinary neighbor.
                            assertEquals(space.hasVolume(c) ? 2 : 0, space.face(c, side).size());
                            assertEquals(
                                    space.hasVolume(route.target()) ? 2 : 0,
                                    space.face(route.target(), route.incoming()).size());
                        }
                    }
                }
    }

    @Test
    void oneCanonicalVertexAtSeamsAndExactlyEightThreeValentCorners() {
        Map<Vertex, Integer> uses = new HashMap<>();
        for (var f : Face.values())
            for (int u = 0; u <= space.size; u++)
                for (int v = 0; v <= space.size; v++)
                    uses.merge(space.vertex(f, u, 0, v), 1, Integer::sum);
        assertEquals(8, uses.values().stream().filter(n -> n == 3).count());
        assertEquals(6 * space.size * space.size + 2, uses.size());
        for (var e : uses.entrySet()) {
            assertTrue(e.getValue() <= 3);
            assertEquals(space.radius, space.point(e.getKey()).length(), 1e-12);
        }
    }

    @Test
    void canonicalIndexBijectionAndInverseAtAllSixCharts() {
        for (int i = 0; i < space.volume(); i++) {
            var c = space.cell(i);
            assertEquals(i, space.index(c));
            assertEquals(
                    c, space.locate(space.project(c.face(), c.u() + .5, c.h() + .5, c.v() + .5)));
        }
    }

    @Test
    void trianglesOutwardAndNonDegenerateAcrossShell() {
        for (var f : Face.values())
            for (int u : new int[] {0, 7, 15})
                for (int v : new int[] {0, 7, 15})
                    for (int h : new int[] {-3, 0, 3}) {
                        var c = new Cell(f, u, h, v);
                        var inside = space.project(f, u + .5, h + .5, v + .5);
                        for (var side : Side.values())
                            for (var t : space.face(c, side)) {
                                assertTrue(t.b().sub(t.a()).cross(t.c().sub(t.a())).length() > .05);
                                assertTrue(t.normal().dot(t.a().sub(inside)) > 0);
                            }
                    }
    }

    @Test
    void invalidOwnersAndDegenerateShellFailClosed() {
        assertThrows(
                IllegalArgumentException.class, () -> space.index(new Cell(Face.TOP, 16, 0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> space.face(new Cell(Face.TOP, 0, 4, 0), Side.UP));
        assertThrows(IllegalArgumentException.class, () -> new PlanetSpace(16, -11, 4));
        assertThrows(
                IllegalArgumentException.class, () -> space.locate(new Point(Double.NaN, 0, 0)));
    }

    @Test
    void historicalEquiangularBaselineHasKnownAnalyticDistortionBounds() {
        var s = PlanetSpace.equiangular(64, -4, 4);
        double eps = 1e-4;
        for (var face : Face.values())
            for (int u = 0; u <= s.size; u += 4)
                for (int v = 0; v <= s.size; v += 4)
                    for (int h : new int[] {s.minHeight, 0, s.maxHeight}) {
                        var du =
                                s.project(face, u + eps, h, v)
                                        .sub(s.project(face, u - eps, h, v))
                                        .scale(.5 / eps);
                        var dv =
                                s.project(face, u, h, v + eps)
                                        .sub(s.project(face, u, h, v - eps))
                                        .scale(.5 / eps);
                        double a = du.dot(du),
                                b = du.dot(dv),
                                d = dv.dot(dv),
                                root = Math.sqrt((a - d) * (a - d) + 4 * b * b);
                        double low = Math.sqrt((a + d - root) / 2),
                                high = Math.sqrt((a + d + root) / 2),
                                scale = 1 + h / s.radius;
                        assertTrue(low >= scale * 2 / 3 - 1e-7);
                        assertTrue(high <= scale * 2 / Math.sqrt(3) + 1e-7);
                        assertTrue(high / low <= Math.sqrt(3) + 1e-7);
                    }
    }

    @Test
    void stableGeometryHasNoObserverArgumentAndNoWorldDependency() {
        for (var method : PlanetSpace.class.getDeclaredMethods())
            for (var type : method.getParameterTypes())
                assertFalse(
                        type.getName().startsWith("net.minecraft")
                                || type.getName().contains("Camera"));
        var c = new Cell(Face.TOP, 0, 0, 1);
        assertEquals(2, space.face(c, Side.UP).size());
        assertEquals(space.face(c, Side.UP), space.face(c, Side.UP));
    }
}
