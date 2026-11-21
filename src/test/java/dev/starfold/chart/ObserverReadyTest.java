package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import org.junit.jupiter.api.Test;

import java.util.List;

class ObserverReadyTest {
    @Test
    void haloPredicateIsCanonicalsHalfOpenBoundaryForEveryShape() {
        for (int size : new int[] {256, 1024, 2048}) {
            var shape = new PlanetShape(size);
            var topology = shape.topology();
            for (double u :
                    new double[] {
                        Math.nextDown(-size),
                        -size,
                        -.5,
                        0,
                        size,
                        Math.nextDown(2.0 * size),
                        2.0 * size
                    })
                for (double v :
                        new double[] {
                            Math.nextDown(-size), -size, 0, Math.nextDown(2.0 * size), 2.0 * size
                        }) {
                    var cell = new Cell(Face.TOP, (int) Math.floor(u), 0, (int) Math.floor(v));
                    assertEquals(shape.inChart(cell.u(), cell.v()), topology.inHalo(u, v));
                    if (topology.inHalo(u, v)) assertDoesNotThrow(() -> topology.canonical(cell));
                    else
                        assertThrows(
                                IllegalArgumentException.class, () -> topology.canonical(cell));
                }
            for (double invalid :
                    new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
                assertFalse(topology.inHalo(invalid, 0));
        }
    }

    @Test
    void airStencilCannotCrossTheHaloEvenWhenTheEyeCentreFits() {
        var small = new PlanetShape(1024);
        var big = new PlanetShape(2048);
        var eye = new Point(26.5, -126.38, 2040.5);
        assertTrue(small.topology().inHalo(eye.x(), eye.z()));
        assertFalse(PlanetAir.canSample(small, eye));
        assertTrue(PlanetAir.canSample(big, eye));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanetAir(small, Face.TOP, eye, GroundAir.DEFAULT, true));
        for (var shape : List.of(small, big))
            for (var face : Face.values())
                for (double u :
                        new double[] {
                            -shape.size() + 32, 0, .5, shape.size(), 2 * shape.size() - 33
                        }) {
                    var point = new Point(u, 50, shape.half());
                    assertTrue(PlanetAir.canSample(shape, point));
                    assertDoesNotThrow(
                            () -> new PlanetAir(shape, face, point, GroundAir.DEFAULT, true));
                }
        assertFalse(PlanetAir.canSample(small, new Point(Double.NaN, 0, 0)));
        assertFalse(PlanetAir.canSample(small, new Point(0, Double.POSITIVE_INFINITY, 0)));
    }
}
