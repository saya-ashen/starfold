package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetMotion;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.*;

class GroundGeometryTest {
    private PlanetMotion.Input motion(long n, Quaterniond q, float f, float rise) {
        return new PlanetMotion.Input(n, q.x, q.y, q.z, q.w, f, 0, rise, false, false);
    }

    @Test
    void largeSixFaceIndicesRoundTripWithoutOverflow() {
        assertEquals(2048, GroundSpace.SIZE);
        assertTrue(GroundSpace.VOLUME > 0);
        var r = new Random(71);
        for (int i = 0; i < 10000; i++) {
            int key = r.nextInt(GroundSpace.VOLUME);
            assertEquals(key, GroundSpace.index(GroundSpace.cell(key)));
        }
        assertEquals(
                GroundSpace.VOLUME - 1, GroundSpace.index(new Cell(Face.BOTTOM, 2047, 55, 2047)));
    }

    @Test
    void allChartsAreRigidUniqueAndAllDirectedSeamsHaveMatchingOwners() {
        for (var f : Face.values()) {
            var chart = new GroundChart(f);
            assertEquals(5, chart.tiles().stream().map(GroundChart.Tile::face).distinct().count());
            for (var t : chart.tiles()) {
                var p = new Point(13.25, 1.5, 55.75);
                assertTrue(t.local(t.point(p)).sub(p).length() < 1e-10);
                assertEquals(1, t.point(p.add(new Point(1, 0, 0))).sub(t.point(p)).length(), 1e-10);
            }
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = d[0] > 0 ? 2048 : d[0] < 0 ? -1 : 1000,
                        z = d[1] > 0 ? 2048 : d[1] < 0 ? -1 : 1000;
                assertEquals(
                        GroundSpace.TOPOLOGY.canonical(new Cell(f, x, 0, z)).cell(),
                        chart.owner(x, 0, z));
            }
        }
    }
}
