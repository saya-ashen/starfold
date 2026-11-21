package dev.starfold.atlas;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.SystemMapApi.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class SystemProjectionTest {
    @Test
    void compressedRadiiPreserveThreeDimensionalBearingSeparateInnerOrbitsAndRoundTrip() {
        var origin = new Position(9e8, -7e8, -8e8);
        for (boolean moon : new boolean[] {false, true}) {
            double previous = 0;
            for (double radius :
                    new double[] {0, 30000, 50000, 80000, 180000, 330000, 458261, 3800000}) {
                var p =
                        new Position(
                                origin.x() + radius * .6,
                                origin.y() + radius * .48,
                                origin.z() - radius * .64);
                var compressed = SystemProjection.compress(p, origin, 180000, moon);
                double r = compressed.distance(new Position(0, 0, 0));
                assertTrue(r >= previous);
                previous = r;
                if (radius > 0) {
                    assertEquals(-.9375, compressed.x() / compressed.z(), 1e-9);
                    assertEquals(-.75, compressed.y() / compressed.z(), 1e-9);
                }
                assertEquals(
                        0,
                        p.distance(SystemProjection.expand(compressed, origin, 180000, moon)),
                        1e-6);
            }
        }
        var inner =
                SystemProjection.compress(
                        new Position(180000, 0, 0), new Position(0, 0, 0), 180000, false);
        var outer =
                SystemProjection.compress(
                        new Position(3800000, 0, 0), new Position(0, 0, 0), 180000, false);
        assertTrue(inner.x() / outer.x() > .2);
    }

    @Test
    void hierarchicalProjectionSharesBodyShipAndOrbitCoordinatesWithoutFlatteningInclination() {
        var root = orbit(0, -1, Kind.STAR, new Position(9e8, 2e5, -8e8), 0);
        var planet = orbit(1, 0, Kind.PLANET, new Position(9e8 + 180000, 2e5, -8e8), 180000);
        var moon =
                orbit(
                        2,
                        1,
                        Kind.MOON,
                        new Position(9e8 + 180000, 2e5 + 18000, -8e8 + 24000),
                        30000);
        var layout = new SystemProjection(List.of(moon, planet, root));
        assertEquals(layout.position(moon.body()), layout.relative(moon.position(), planet.body()));
        assertEquals(layout.position(moon.body()), layout.position(moon.position()));
        assertEquals(layout.position(planet.body()), layout.position(planet.position()));
        assertTrue(layout.position(moon.body()).y() > layout.position(planet.body()).y());
    }

    @Test
    void amberMoonRingsStayNearTheirParentInTheSystemOverview() {
        var root = orbit(0, -1, Kind.STAR, new Position(0, 0, 0), 0);
        var inner = orbit(1, 0, Kind.PLANET, new Position(180000, 0, 0), 180000);
        var amber = orbit(2, 0, Kind.GAS, new Position(1400000, 0, 0), 1400000);
        var cinder = orbit(3, 2, Kind.MOON, new Position(1370000, 0, 0), 30000);
        var flesh = orbit(4, 2, Kind.MOON, new Position(1290000, 0, 0), 110000);
        var layout = new SystemProjection(List.of(flesh, cinder, amber, inner, root));
        var parent = layout.position(amber.body());
        var primary = layout.position(root.body());
        double outer = parent.distance(layout.position(flesh.body()));
        assertEquals(Math.log1p(1400000.0 / 180000), parent.distance(primary), 1e-9);
        assertEquals(parent.distance(primary) / 3, outer, 1e-9);
        assertEquals(
                Math.sqrt(30000.0 / 110000) * outer,
                parent.distance(layout.position(cinder.body())),
                1e-9);
        var nearSide =
                SystemProjection.orbit(
                        amber.position(), flesh.axis(), flesh.radial(), flesh.radius(), Math.PI);
        var projectedFlesh = layout.position(flesh.body());
        assertEquals(0, projectedFlesh.distance(layout.relative(nearSide, amber.body())), 1e-9);
        assertEquals(0, projectedFlesh.distance(layout.position(flesh.position())), 1e-9);
    }

    @Test
    void inclinedMoonPathsRotateAboutTheirParentAndClose() {
        var parent = new Position(100000, 200, -300000);
        var axis = new Position(1, 0, 0);
        var radial = new Position(0, 0, 1);
        var at = SystemProjection.orbit(parent, axis, radial, 2000, Math.PI / 2);
        assertEquals(100000, at.x(), 1e-9);
        assertEquals(-1800, at.y(), 1e-9);
        assertEquals(-300000, at.z(), 1e-9);
        assertEquals(2000, at.distance(parent), 1e-9);
        var closed = SystemProjection.orbit(parent, axis, radial, 2000, Math.PI * 2);
        assertEquals(0, closed.distance(new Position(100000, 200, -298000)), 1e-9);
    }

    @Test
    void satelliteViewUsesOneScaleForPositionsAndPhysicalRadii() {
        var star = orbit(0, -1, Kind.STAR, new Position(0, 0, 0), 0);
        var planet = orbit(1, 0, Kind.PLANET, new Position(1000, 0, 0), 1000);
        var moon = orbit(2, 1, Kind.MOON, new Position(1100, 0, 0), 100);
        var view = new SystemProjection(List.of(star, planet, moon), planet.body());
        assertFalse(view.includes(star));
        assertTrue(view.includes(planet));
        assertTrue(view.includes(moon));
        assertEquals(0, view.position(planet.body()).x(), 1e-9);
        assertEquals(1, view.position(moon.body()).x(), 1e-9);
        assertEquals(.2, planet.bodyRadius() * view.bodyScale(), 1e-9);
    }

    private static Orbit orbit(int id, int parent, Kind kind, Position p, double radius) {
        return new Orbit(
                id,
                "id",
                "name",
                kind,
                0,
                parent,
                p,
                new Position(0, 1, 0),
                new Position(1, 0, 0),
                radius,
                20,
                100,
                0,
                0,
                0,
                kind != Kind.STAR);
    }
}
