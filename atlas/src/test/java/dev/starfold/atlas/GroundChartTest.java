package dev.starfold.atlas;

import dev.starfold.api.PlanetFaces;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundChartTest {
    @Test void fiveFacesRoundTripAndEveryGluedEdgeMatchesAuthoritativeTransport() {
        for (int size : new int[]{256, 272, 2048}) for (int root = 0; root < 6; root++) {
            var chart = new GroundChart(size, root);
            assertEquals(5, chart.tiles.stream().map(GroundChart.Tile::face).distinct().count());
            for (var tile : chart.tiles) {
                for (double u : new double[]{.5, 13.25, size - .5}) for (double v : new double[]{.5, 91.75, size - .5}) {
                    var p = tile.project(size, u, v);
                    var q = chart.pick(p.x(), p.y());
                    assertNotNull(q); assertEquals(tile.face(), q.face());
                    assertEquals(u, q.u(), 1e-10); assertEquals(v, q.v(), 1e-10);
                }
                if (tile.face() == root) continue;
                for (int along : new int[]{0, 13, size / 2, size - 1}) {
                    double x = tile.x() == 0 ? along + .5 : tile.x() > 0 ? size + .5 : -.5;
                    double y = tile.y() == 0 ? along + .5 : tile.y() > 0 ? size + .5 : -.5;
                    var expected = PlanetFaces.adjacent(size, root, (int) Math.floor(x), (int) Math.floor(y));
                    var hit = chart.pick(x, y);
                    assertEquals(expected.face(), hit.face());
                    assertEquals(expected.u() + .5, hit.u(), 1e-10);
                    assertEquals(expected.v() + .5, hit.v(), 1e-10);
                    var reverse = new GroundChart(size, tile.face()).face(root);
                    var local = tile.local(size, size / 2., size / 2.);
                    var back = reverse.project(size, size / 2., size / 2.);
                    assertEquals(local, back);
                }
            }
        }
    }
    @Test void cornerQuadrantIsMissingRatherThanDuplicatedOrArbitrarilyCanonicalized() {
        for (int root = 0; root < 6; root++) {
            var chart = new GroundChart(256, root);
            for (double u : new double[]{-.5, 256.5}) for (double v : new double[]{-.5, 256.5}) {
                assertNull(chart.pick(u, v));
                assertNotNull(chart.pick(u, Math.clamp(v, .5, 255.5)));
                assertNotNull(chart.pick(Math.clamp(u, .5, 255.5), v));
            }
            assertNull(chart.pick(-256.5, 128)); assertNull(chart.pick(128, 512));
        }
    }
    @Test void transportedNorthAndCameraOffsetsDoNotQuarterTurnOnRebasing() {
        int size = 256;
        for (int root = 0; root < 6; root++) for (int north = 0; north < 4; north++) {
            var chart = new GroundChart(size, root);
            for (var tile : chart.tiles) if (tile.face() != root) {
                var next = new GroundChart(size, tile.face());
                int transported = chart.northAfter(tile.face(), north);
                assertEquals(north, next.northAfter(root, transported));
                var centre = tile.project(size, 3.5, 159.25);
                var point = tile.project(size, 17.25, 93.5);
                var oldScreen = GroundChart.rotate(point.x() - centre.x(), point.y() - centre.y(), north);
                var newScreen = GroundChart.rotate(17.25 - 3.5, 93.5 - 159.25, transported);
                assertEquals(oldScreen, newScreen);
                // North expressed in the new face and rendered back into screen coordinates is unchanged.
                var n = GroundChart.rotate(0, -1, -transported);
                assertEquals(new GroundChart.Point(0, -1), GroundChart.rotate(n.x(), n.y(), transported));
            }
        }
    }
    @Test void exactRotatedSeamsStayInsideFaceCoordinateRange() {
        var chart = new GroundChart(256, 5);
        for (var tile : chart.tiles) for (double u : new double[]{0, 128, 256}) for (double v : new double[]{0, 128, 256}) {
            var p = tile.project(256, u, v); var hit = chart.pick(p.x(), p.y());
            if (hit != null) { assertTrue(hit.u() >= 0 && hit.u() < 256); assertTrue(hit.v() >= 0 && hit.v() < 256); }
        }
    }
    @Test void navigationAndGridSegmentsClipToOffsetViewportWithoutUnboundedPixelWalks() {
        assertArrayEquals(new double[]{.25, .75}, GroundChart.clip(new GroundChart.Point(-40, 60),
                new GroundChart.Point(160, 60), 10, 20, 100, 80, 0), 1e-12);
        assertArrayEquals(new double[]{.3, .7}, GroundChart.clip(new GroundChart.Point(-40, 60),
                new GroundChart.Point(160, 60), 10, 20, 100, 80, 10), 1e-12);
        assertNull(GroundChart.clip(new GroundChart.Point(0, 0), new GroundChart.Point(0, 100), 10, 20, 100, 80, 0));
        assertNull(GroundChart.clip(new GroundChart.Point(120, 40), new GroundChart.Point(160, 70), 10, 20, 100, 80, 0));
        assertArrayEquals(new double[]{0, 1}, GroundChart.clip(new GroundChart.Point(50, 60),
                new GroundChart.Point(50, 60), 10, 20, 100, 80, 0));
    }
    @Test void oppositeFaceTargetBearingChoosesTheNearestOfFourUnfoldings() {
        for (int root = 0; root < 6; root++) {
            var chart = new GroundChart(256, root);
            int opposite = java.util.stream.IntStream.range(0, 6).filter(f -> chart.face(f) == null).findFirst().orElseThrow();
            var from = new GroundChart.Point(15.5, 92.5);
            var target = chart.destination(opposite, 37.5, 193.5, from);
            double best = Double.POSITIVE_INFINITY;
            for (var tile : chart.tiles) if (tile.face() != root) {
                var extension = tile.neighbour(256, tile.x(), tile.y());
                assertEquals(opposite, extension.face());
                var p = extension.project(256, 37.5, 193.5);
                best = Math.min(best, Math.hypot(p.x() - from.x(), p.y() - from.y()));
            }
            assertEquals(best, Math.hypot(target.x() - from.x(), target.y() - from.y()), 1e-10);
        }
    }
}
