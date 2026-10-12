package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

class GroundCoverageOwnerTest {
    private static MethodNode method(String name, String method) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/" + name + ".class")) {
            new ClassReader(in).accept(node, 0);
        }
        return node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
    }

    @Test
    void coverageAndInvalidationsAreNotConsumedBeforeOwnershipIsReady() throws Exception {
        for (String name : List.of("refreshCoverage", "prepareDetails")) {
            var instructions = method("GroundRenderer", name).instructions;
            MethodInsnNode first = null;
            for (var i : instructions)
                if (i instanceof MethodInsnNode call) {
                    first = call;
                    break;
                }
            assertNotNull(first);
            assertEquals("dev/starfold/render/GroundViewClient", first.owner);
            assertEquals("coverageReady", first.name);
            var branch = first.getNext();
            assertEquals(Opcodes.IFNE, branch.getOpcode());
            assertEquals(Opcodes.RETURN, branch.getNext().getOpcode());
        }
    }

    @Test
    void sameSizedPlanetsAndSameSessionShapeChangesStillResetTheirCells() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        assertTrue(renderer.contains("planet!=observedPlanet||!observed.equals(shape)){clear();"));
        for (String cleared :
                List.of(
                        "dirty.all()",
                        "priorCoverage.clear()",
                        "dirtyDetails.clear()",
                        "wanted=Set.of()",
                        "window=Set.of()",
                        "publishedWindow=Set.of()"))
            assertTrue(renderer.contains(cleared), cleared);
        var client = Sources.text("GroundViewClient");
        assertTrue(
                client.contains(
                        "level==world&&session==id&&boundPlanet==planet&&boundShape.equals(shape))return"));
        assertTrue(
                client.contains(
                        "boundPlanet==GroundClient.planet()&&boundShape.equals(GroundClient.shape())&&coverageRevision>=0"));
        assertTrue(client.contains("heights.shape().equals(boundShape)"));
        assertTrue(client.contains("session==GroundFlightClient.session()"));
        for (String cleared :
                List.of(
                        "changed.clear()",
                        "invalidated.clear()",
                        "cache.clear()",
                        "windowStamp=Long.MIN_VALUE"))
            assertTrue(client.contains(cleared), cleared);
        assertTrue(client.contains("new GroundViewCache(()->boundShape,"));
        assertFalse(client.contains("neighbours(GroundClient.shape(),key)"));
    }

    @Test
    void theLargePlanetsLastTileHaloCannotBeReinterpretedOnTheSmallPlanet() {
        var big = new PlanetShape(2048);
        var small = new PlanetShape(1024);
        var cell = new CubeSurfaceTopology.Cell(CubeSurfaceTopology.Face.TOP, 2048, 0, 1024);
        assertDoesNotThrow(() -> big.topology().canonical(cell));
        assertThrows(IllegalArgumentException.class, () -> small.topology().canonical(cell));
    }

    @Test
    void arrivalPreviewIsSeededAfterBindingAndStillRequestsAuthoritativeCoverage()
            throws Exception {
        var client = Sources.text("GroundViewClient");
        int bind = client.indexOf("public static void bindSession"),
                preview = client.indexOf("static void preview");
        var binding = client.substring(bind, preview);
        assertTrue(
                binding.indexOf("clear();")
                        < binding.indexOf("boundPlanet=planet;boundShape=shape;"));
        assertTrue(
                binding.indexOf("boundPlanet=planet;boundShape=shape;")
                        < binding.indexOf("PlanetRemoteRenderer.seedOwn(boundPlanet,id)"));
        assertTrue(client.contains("coverageRevision=0;previewed=true"));
        assertTrue(client.contains("(previewed||!coverageReady())"));
        var remote = Sources.text("PlanetRemoteRenderer");
        assertTrue(remote.contains("bodies.get(planet)"));
        assertTrue(remote.contains("GroundViewClient.preview(session,snapshot)"));
    }
}
