package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.PlanetShape;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
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

    @Test
    void readinessOwnsFrameShapeEyeAndCurrentRenderPartial() throws Exception {
        var client = Sources.text("GroundClient");
        for (String rule :
                List.of(
                        "chartFrame==null",
                        "planet()!=f.planet()",
                        "inFrame(b,f)",
                        "inFrame(storage(mc.player.getEyePosition(partial)),f)",
                        "PlanetAir.canSample(shape(),eye())",
                        "access.storagePlanet(p.x(),p.z())==f.planet()",
                        "access.face(p.x())==f.root()",
                        "shape().topology().inHalo")) assertTrue(client.contains(rule), rule);
        var begin =
                client.substring(
                        client.indexOf("public static void begin"),
                        client.indexOf("public static void end"));
        assertTrue(begin.indexOf("partial=delta") < begin.indexOf("!ready()"));
        assertTrue(client.contains("boolean projected=frame&&ready();"));
        assertTrue(client.contains("if(!projected)return;"));
        assertTrue(Sources.text("CubeSurfaceTopology").contains("if(!inHalo(query.u,query.v))"));
    }

    @Test
    void hudProbeLightFogAndSkyKeepChartQueriesBehindReadiness() throws Exception {
        var flight = Sources.text("GroundFlightClient");
        assertTrue(
                flight.contains(
                        "if(!ready()||!GroundClient.ready()||Minecraft.getInstance().options.hideGui)return"));
        assertTrue(
                flight.contains("if(!ready()||!GroundClient.ready())return \"flight not ready\""));
        assertTrue(flight.contains("probeAltitude(){return ready()&&GroundClient.ready()?"));
        assertTrue(
                Sources.text("WaypointClient")
                        .contains("!GroundClient.ready() || !GroundFlightClient.ready()"));
        var probe = Sources.text("DevProbe");
        assertTrue(probe.contains("case \"solarplan\",\"solarjump\"->{if(!GroundClient.ready())"));
        assertTrue(probe.contains("GroundClient.ready()?GroundClient.face():-1"));
        assertTrue(
                probe.contains(
                        "GroundClient.ready()?GroundRenderer.realCorners(GroundClient.face()):0"));
        assertTrue(Sources.text("GroundLightMixin").contains("&&GroundClient.ready())"));
        assertTrue(Sources.text("GroundFogMixin").contains("||!GroundClient.ready())return"));
        assertTrue(
                Sources.text("GroundFlightRenderMixin")
                        .contains(
                                "SpaceView.chartFar(GroundClient.ready()?GroundClient.eye().y():0,GroundClient.view())"));
        assertTrue(
                Sources.text("GroundCelestials")
                        .contains("!SpaceClient.active()&&!GroundClient.ready()"));
        assertTrue(Sources.text("GroundSunRenderer").contains("!GroundClient.solar()"));
        assertTrue(Sources.text("PlanetRemoteRenderer").contains("!GroundClient.ready()"));
        assertTrue(Sources.text("GroundRenderer").contains("!GroundClient.ready()"));
    }

    @Test
    void plotGuardRefusesImmutableDestinationAndRetainsSourceProtection() throws Exception {
        var guard = Sources.text("PlanetCoreChunkMixin");
        assertTrue(guard.contains("if(state.is(PlanetCoreBlock.IMMUTABLE))"));
        assertTrue(
                guard.contains(
                        "container.getPlot(chunk.getPos())!=null){ci.setReturnValue(null);return;}"));
        assertTrue(
                guard.contains(
                        "old.is(PlanetCoreBlock.IMMUTABLE)&&old!=state)ci.setReturnValue(null)"));
        assertTrue(
                guard.indexOf("state.is(PlanetCoreBlock.IMMUTABLE)")
                        < guard.indexOf("var old=chunk.getBlockState(pos)"));
    }
}
