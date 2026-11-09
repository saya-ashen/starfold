package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.*;

class GroundPatchFadeTest {
    private int calls(String type, String method, String target) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/render/" + type + ".class")) {
            new ClassReader(in).accept(node, 0);
        }
        int count = 0;
        for (var m : node.methods)
            if (m.name.equals(method))
                for (var i : m.instructions)
                    if (i instanceof MethodInsnNode c && c.name.equals(target)) count++;
        return count;
    }

    @Test
    void bothPatchPathsDrawOnlyOncePerPatchIncludingFades() throws Exception {
        assertEquals(1, calls("GroundRenderer", "renderPatch", "render"));
        assertEquals(1, calls("GroundExterior", "draw", "drawPatch"));
        var ground = Sources.text("GroundRenderer");
        assertTrue(ground.contains("previous=patchQuads.getOrDefault(patch,List.of())"));
        assertTrue(ground.contains("for(varq:previous)emit(q,true)"));
        assertTrue(ground.contains("stagedQuads.put(patch,coverJob.quads)"));
        assertFalse(ground.contains("previousPatches"));
        assertFalse(Sources.text("GroundExterior").contains("previous[i]"));
    }

    @Test
    void wallTagsPreserveEmittingTileAndFadeGenerationTogether() throws Exception {
        var tags = new java.util.HashSet<Integer>();
        for (boolean old : new boolean[] {false, true})
            for (int side = -1; side < 4; side++) {
                int tag = GroundShaderSource.patchTag(old, side);
                assertTrue(tags.add(tag));
                assertNotEquals(GroundShaderSource.VOXEL_TAG, tag);
                if (side >= 0) {
                    assertEquals(side + 1, (tag - 236) % 4 + 1);
                    assertEquals(old, tag < 240);
                    // The fragment lookup is biased into the wall's emitter, not the resident
                    // across an exact 16-block boundary. Dither must not change this ownership.
                    double point = side % 2 == 0 ? 48.02 : 63.98;
                    assertEquals(3, (int) Math.floor(point / 16));
                }
            }
        String shader = CoreShaderSource.patch("rendertype_solid", true,
                VoxelFaceShaderTest.resource("core/rendertype_solid.vsh"));
        assertTrue(shader.contains("tag>235.5&&tag<243.5"));
        assertTrue(shader.contains("cpNativeSide=mod(tag-236.0,4.0)+1.0"));
        assertTrue(shader.contains("if(tag<239.5)cpNativePatchOld=1.0"));
    }

    @Test
    void shadersDistinguishGenerationsWithinTheSameBuffer() throws Exception {
        var source = Sources.text("GroundShaderSource");
        assertTrue(source.contains("if(tag<249.5)cpNativePatchOld=1.0"));
        assertTrue(source.contains("if(cpNativePatchOld>0.5)patchFade=-1.0-patchFade"));
        var v =
                Files.readString(
                        Path.of(
                                "src/main/resources/assets/starfold/shaders/core/native_ground_exterior.vsh"));
        var f =
                Files.readString(
                        Path.of(
                                "src/main/resources/assets/starfold/shaders/core/native_ground_exterior.fsh"));
        assertTrue(v.contains("patchOld=mod(tag-128.0,2.0);cpSurfaceClass=floor((tag-128.0)/2.0)"));
        for(int c=0;c<=4;c++) for(boolean old:new boolean[]{false,true}) {
            int tag=GroundExteriorVertices.surfaceTag(c,old);
            assertTrue(tag/255.0>.5);
            assertEquals(c,(tag-128)/2);
            assertEquals(old ? 1 : 0,(tag-128)%2);
        }
        assertTrue(f.contains("if(patchOld>0.5)fade=-1.0-fade"));
        assertTrue(
                f.contains("surfaceColor.a>0.5&&cpGroundHidden(fade)"),
                "anomaly glow remains solid during the swap");
    }
}
