package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetSystem;

import org.junit.jupiter.api.Test;

class GroundObserverReadinessTest {
    @Test
    void invalidInterpolatedFeetAreRejectedBeforeFogUsesPose() {
        PlanetSystem.reset();
        var state = new GroundObserverState();
        state.chartFrame = ObserverFrame.identity(0, Face.SOUTH.ordinal());
        var safe = GroundTopology.feet(new PlanetShape.Pose(Face.SOUTH, new Point(64.5, 0, 64.5)));
        var eye = safe.add(new Point(0, 1.62, 0));
        assertTrue(state.accepts(safe, eye, false));
        var outsideShell = new Point(safe.x(), -900.23, safe.z());
        assertFalse(state.accepts(outsideShell, eye, false));
        assertTrue(state.accepts(outsideShell, eye, true), "flight owns its body pose");
        var otherFace = PlanetAccess.SHARED.feet(0,
                new PlanetShape.Pose(Face.TOP, new Point(64.5, 0, 64.5)));
        var otherPlanet = PlanetAccess.SHARED.feet(1,
                new PlanetShape.Pose(Face.SOUTH, new Point(64.5, 0, 64.5)));
        for (var invalid : new Point[] {otherFace, otherPlanet, new Point(Double.NaN, 128, 64),
                new Point(safe.x(), safe.y(), -10000)}) {
            assertFalse(state.accepts(invalid, eye, false), "feet must match the chart");
            assertFalse(state.accepts(safe, invalid, false), "eye must match the chart");
            assertFalse(state.accepts(safe, invalid, true), "flight must also match the chart");
        }
    }

    @Test
    void smallerPlanetRejectsEyeOutsideItsOwnHaloEvenInsideTheStorageEnvelope() {
        PlanetShape.system(new int[] {1024, 2048}, new int[] {191, 191});
        try {
            var state = new GroundObserverState();
            state.chartFrame = ObserverFrame.identity(0, Face.SOUTH.ordinal());
            var feet = PlanetAccess.SHARED.feet(0,
                    new PlanetShape.Pose(Face.SOUTH, new Point(64.5, 0, 64.5)));
            var origin = PlanetAccess.SHARED.origin(0, Face.SOUTH.ordinal());
            assertTrue(state.accepts(feet, origin.add(new Point(64.5, 128, 2047.5)), true));
            assertFalse(state.accepts(feet, origin.add(new Point(64.5, 128, 2048)), true));
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void packProbeFencesServerCompletionWithExistingPositiveChatReadback() throws Exception {
        var source = Sources.textPaths("probes/src/main/java/dev/starfold/dev/ProbeSession.java", "probes/src/main/java/dev/starfold/dev/ProbeSamples.java");
        int start = source.indexOf("staticbooleancommandPack(");
        var method =
                source.substring(start, source.indexOf("staticbooleancommandCmd(", start));
        assertTrue(method.contains("if(!cursor.strict||!Boolean.getBoolean(\"starfold.packProbe\"))"));
        assertTrue(
                method.indexOf("sendCommand(\"packprobe")
                        < method.indexOf("sendCommand(\"tellraw"));
        assertTrue(method.contains("guardLeft=6000"));
        assertTrue(method.contains("returnfalse"));
        assertTrue(
                source.contains(
                        "case\"ready\"->GroundClient.ready()&&GroundClient.planet()==(int)untilValue"));
    }
}
