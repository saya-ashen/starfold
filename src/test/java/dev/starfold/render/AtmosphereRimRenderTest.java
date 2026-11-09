package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** The pure rim profile must reach all three draw paths and the real alpha blend boundary. */
class AtmosphereRimRenderTest {
    private MethodNode method(String owner, String name) throws Exception {
        try (var in = TestArchiveResources.open("/dev/starfold/render/" + owner + ".class")) {
            assertNotNull(in);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        }
    }

    private List<MethodInsnNode> calls(MethodNode method) {
        var out = new ArrayList<MethodInsnNode>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode c) out.add(c);
        return out;
    }

    @Test
    void sharedRimReachesCurrentExteriorChartBodiesAndRegionBodies() throws Exception {
        for (var route :
                new String[][] {
                    {"GroundExterior", "draw"},
                    {"PlanetRemoteRenderer", "drawBody"},
                    {"PlanetRemoteRenderer", "drawGroundImpostors"},
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
        var rim = calls(method("GroundAtmosphere", "rim"));
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
        var source =
                Files.readString(Path.of("src/main/java/dev/starfold/render/GroundAtmosphere.java"))
                        .replaceAll("\\s+", "");
        assertTrue(source.contains("colors[index],(float)(passWeight*AtmosphereRim.opacity("));
        assertTrue(
                source.contains(
                        ".setColor((float)color.x(),(float)color.y(),(float)color.z(),alpha)"));
        assertTrue(
                source.contains(
                        "if(background)RenderSystem.disableBlend();else{RenderSystem.enableBlend();"));
        assertTrue(source.contains("RenderSystem.defaultBlendFunc();"));
        assertFalse(source.contains("RenderSystem.blendFunc(GL11.GL_ONE,GL11.GL_ONE)"));
        assertTrue(source.contains("newRenderPassState(true,true,false)"));
        assertTrue(source.contains("pass.close()"));
        assertTrue(
                calls(method("GroundAtmosphere", "draw")).stream()
                        .anyMatch(c -> c.name.equals("defaultBlendFunc")));
    }

    @Test
    void realSkyPathFadesBroadBackgroundThenDrawsThinOwnRim() throws Exception {
        var sky = calls(method("GroundAtmosphere", "drawSky"));
        var names = sky.stream().map(c -> c.name).toList();
        assertEquals(1, Collections.frequency(names, "highAltitudeWeight"));
        assertEquals(1, Collections.frequency(names, "rim"));
        assertTrue(names.indexOf("highAltitudeWeight") < names.indexOf("sky"));
        assertTrue(names.indexOf("draw") < names.indexOf("rim"));
        var source =
                Files.readString(Path.of("src/main/java/dev/starfold/render/GroundAtmosphere.java"))
                        .replaceAll("\\s+", "");
        assertTrue(source.contains(".scale(1-highAltitude)"));
        assertTrue(source.contains("if(highAltitude>0){"));
        assertTrue(
                source.contains(
                        "home.local(GroundClient.universeEye()),skyMatrix(c),eye.scale(-1)"));
        assertTrue(source.contains("home.inverseVector(newPoint(1,0,0))"));
        assertTrue(source.contains("home.inverseVector(newPoint(0,1,0))"));
        assertTrue(source.contains("home.inverseVector(newPoint(0,0,1))"));
    }

    @Test
    void ownHighLimbDoesNotDependOnExteriorReadinessOrDoubleDrawLater() throws Exception {
        var source =
                Files.readString(Path.of("src/main/java/dev/starfold/render/GroundAtmosphere.java"))
                        .replaceAll("\\s+", "");
        assertTrue(source.contains("if(!SpaceClient.active()&&planet==GroundClient.planet()){"));
        assertTrue(source.contains("passWeight=AtmosphereRim.currentPassWeight(distance-radius,"));
        assertTrue(
                source.contains("RenderLevelStageEvent.Stage.AFTER_SKY);if(passWeight==0)return;"));
        var sky = calls(method("GroundAtmosphere", "drawSky"));
        assertFalse(
                sky.stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("GroundExterior")
                                                || c.name.equals("selectionReady")
                                                || c.name.equals("replacesNative")));
        var sourceRim = source.substring(source.indexOf("publicstaticvoidrim("));
        assertTrue(sourceRim.indexOf("if(passWeight==0)return;") < sourceRim.indexOf("varpoints="));
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
