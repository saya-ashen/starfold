package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/** The shared rim profile must reach native sky and remote bodies with bounded alpha blending. */
class AtmosphereRimRenderTest {
    private MethodNode method(String owner, String name) throws Exception {
        return method(owner, name, null);
    }

    private MethodNode method(String owner, String name, String descriptorSuffix) throws Exception {
        try (var in = TestArchiveResources.open("/dev/starfold/render/" + owner + ".class")) {
            assertNotNull(in);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node.methods.stream()
                    .filter(m -> m.name.equals(name))
                    .filter(m -> descriptorSuffix == null || m.desc.endsWith(descriptorSuffix))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private List<MethodInsnNode> calls(MethodNode method) {
        var out = new ArrayList<MethodInsnNode>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode c) out.add(c);
        return out;
    }

    @Test
    void sharedRimReachesNativeSkyChartBodiesAndRegionBodies() throws Exception {
        for (var route :
                new String[][] {
                    {"GroundAtmosphere", "drawSky"},
                    {"PlanetRemoteRenderer", "drawBody"},
                    {"PlanetRemoteRenderer", "drawGroundImpostor"},
                    {"PlanetRemoteRenderer", "drawRegion"}
                })
            assertEquals(
                    route[1].equals("drawRegion") ? 2 : 1,
                    calls(method(route[0], route[1])).stream()
                            .filter(
                                    c ->
                                            c.owner.endsWith("GroundAtmosphere")
                                                    && c.name.equals("rim"))
                            .count());
        var forward = calls(method("GroundAtmosphere", "rim", "D)V"));
        assertEquals(1, forward.stream().filter(c -> c.name.equals("rim") && c.desc.endsWith("DZ)V")).count());
        var rim = calls(method("GroundAtmosphere", "rim", "DZ)V"));
        for (String name : List.of("height", "section", "opacity"))
            assertTrue(
                    rim.stream()
                            .anyMatch(
                                    c -> c.owner.endsWith("AtmosphereRim") && c.name.equals(name)),
                    name);
        assertTrue(
                rim.stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("AtmosphereScattering")
                                                && c.name.equals("sky")));
        assertEquals(1, rim.stream().filter(c -> c.name.equals("display")).count());
    }

    @Test
    void rimCoverageFeedsVertexAlphaAndUsesBoundedBlendOnlyForOverlay() throws Exception {
        var rim = calls(method("GroundAtmosphere", "rim", "DZ)V"));
        var names = rim.stream().map(c -> c.name).toList();
        assertTrue(names.contains("opacity"));
        assertTrue(names.indexOf("opacity") < names.indexOf("vertex"));
        assertTrue(names.indexOf("vertex") < names.indexOf("drawData"));
        var color = calls(method("GroundAtmosphere", "vertex")).stream()
                .filter(c -> c.name.equals("setColor"))
                .findFirst().orElseThrow();
        assertTrue(color.desc.startsWith("(FFFF)"));
        var alpha = assertInstanceOf(VarInsnNode.class, previousCode(color));
        assertEquals(Opcodes.FLOAD, alpha.getOpcode());
        assertEquals(3, alpha.var, "rim alpha is passed through to the vertex color");

        var draw = calls(method("GroundAtmosphere", "drawData"));
        var disable = draw.stream().filter(c -> c.name.equals("disableBlend")).findFirst().orElseThrow();
        var branch = assertInstanceOf(JumpInsnNode.class, previousCode(disable));
        assertEquals(Opcodes.IFEQ, branch.getOpcode());
        var background = assertInstanceOf(VarInsnNode.class, previousCode(branch));
        assertEquals(Opcodes.ILOAD, background.getOpcode());
        assertEquals(2, background.var);
        var overlay = assertInstanceOf(MethodInsnNode.class, nextCode(branch.label));
        assertEquals("enableBlend", overlay.name);
        var blend = assertInstanceOf(MethodInsnNode.class, nextCode(overlay));
        assertEquals("defaultBlendFunc", blend.name);
        assertEquals("com/mojang/blaze3d/systems/RenderSystem", blend.owner);
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do instruction = instruction.getNext();
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do instruction = instruction.getPrevious();
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    @Test
    void realSkyPathFadesBroadBackgroundThenDrawsThinOwnRim() throws Exception {
        var sky = calls(method("GroundAtmosphere", "drawSky"));
        var names = sky.stream().map(c -> c.name).toList();
        assertEquals(1, Collections.frequency(names, "highAltitudeWeight"));
        assertEquals(1, Collections.frequency(names, "rim"));
        assertTrue(names.indexOf("highAltitudeWeight") < names.indexOf("sky"));
        assertTrue(names.indexOf("draw") < names.indexOf("rim"));
    }

    @Test
    void ownHighLimbDoesNotDependOnExteriorReadinessOrDoubleDrawLater() throws Exception {
        var sky = calls(method("GroundAtmosphere", "drawSky"));
        assertFalse(
                sky.stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("GroundExterior")
                                                || c.name.equals("selectionReady")
                                                || c.name.equals("replacesNative")));
    }

    @Test
    void vacuumSmallBodyAndCloseObserverProduceNoRimAndVisibleRimsHaveBoundedSegments() {
        assertEquals(0, GroundAtmosphere.rimSegments(0, 2048, 1024), "vacuum");
        assertEquals(0, GroundAtmosphere.rimSegments(1, 1072, 1024), "inside scale height");
        assertEquals(96, GroundAtmosphere.rimSegments(1, Math.nextUp(1072.0), 1024));
        assertEquals(0, GroundAtmosphere.rimSegments(1, Math.nextUp(512000.0), 1024), "sub-pixel");
        assertEquals(12, GroundAtmosphere.rimSegments(1, 512000, 1024), "visible threshold");
        assertEquals(39, GroundAtmosphere.rimSegments(1, 10240, 1024), "ceil angular resolution");
        assertEquals(96, GroundAtmosphere.rimSegments(.01, 2048, 1024), "maximum resolution");
    }
}
