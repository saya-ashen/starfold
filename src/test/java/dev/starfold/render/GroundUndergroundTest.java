package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.PlanetSpace;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

/** No GPU or world: explicit underground/dirty-section failure models plus runtime contracts. */
class GroundUndergroundTest {
    static String fragment() {
        return GroundShaderSource.patch(
                "rendertype_solid",
                false,
                "#version 150\nuniform sampler2D Sampler0;in vec2 texCoord0;out vec4 fragColor;void main(){fragColor=texture(Sampler0,texCoord0);}");
    }

    static String vertex(String name) {
        return GroundShaderSource.patch(
                name,
                true,
                "#version 150\nin vec3 Position;in vec3 Normal;in vec4 Color;in ivec2 UV2;uniform sampler2D Sampler2;uniform mat4 ProjMat,ModelViewMat;out vec4 vertexColor;void main(){vertexColor=Color*texelFetch(Sampler2,UV2/16,0);gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}");
    }

    @Test
    void generatedShadersKeepLightmapSamplingWithoutExtraSkyAttenuation() {
        var fragment = fragment();
        var vertex = vertex("rendertype_solid");
        assertTrue(vertex.contains("Color*texelFetch(Sampler2,cpNativeLightUV2/16,0)"));
        assertFalse(vertex.contains("vertexColor.rgb/=max"));
        assertFalse(fragment.contains("(0.12+0.88*daylight)*cpNativeSkyVisibility"));
        assertFalse(fragment.contains("fragColor.rgb*=cpNativeSkyVisibility"));
    }

    @Test
    void ceilingNormalsMustNotBeFlippedOutwardLikeAHeightfield() {
        var a = new BentStripGeometry.Point(0, 0, 0);
        var b = new BentStripGeometry.Point(1, 0, 1);
        var c = new BentStripGeometry.Point(0, 0, 1);
        var normal = PlanetSpace.unit(b.sub(a).cross(c.sub(a)));
        var sun = new BentStripGeometry.Point(0, 1, 0);
        assertEquals(-1, normal.dot(sun));
        assertEquals(0, Math.max(0, normal.dot(sun)));
        assertTrue(
                GroundShaderSource.RELIEF.contains(
                        "if(CPNativeCoarse>0.5||CPNativeDerived>0.5){if(dot(slope,radial)<0.0)"));
        assertTrue(GroundShaderSource.RELIEF.contains("else if(!gl_FrontFacing)slope=-slope"));
        assertTrue(GroundShaderSource.RELIEF.indexOf("if(cpNativeVoxel>0.5)")
                < GroundShaderSource.RELIEF.indexOf("if(CPNativeCoarse>0.5||CPNativeDerived>0.5)"),
                "voxel ceilings preserve inward normals before the heightfield-only flip");
        assertTrue(
                GroundShaderSource.RELIEF.contains(
                        "((CPNativeCoarse>0.5||CPNativeDerived>0.5)&&cpNativeSea>0.999)"));
    }

    @Test
    void coarseSurfaceDefaultsAndSelfLitShadersDoNotAcquireVoxelSkyAttenuation() {
        assertTrue(GroundShaderSource.VERTEX.contains("cpNativeSkyVisibility=1.0"));
        assertFalse(
                vertex("rendertype_solid").contains("if(CPNativeCoarse<0.5){cpNativeBlockLight="));
        assertFalse(vertex("rendertype_eyes").contains("cpNativeSkyVisibility=sky/"));
    }

    @Test
    void ordinaryWorldAndGuiRemainOutsideNativeLightingScope() {
        assertTrue(GroundShaderSource.VERTEX.contains("if(CPNativeActive<0.5)return"));
        String gui = "#version 150\nvoid main(){}";
        assertEquals(gui, GroundShaderSource.patch("rendertype_gui", false, gui));
        assertTrue(
                fragment()
                        .contains(
                                "CPNativeActive>0.5&&(CPNativeDerived>0.5||CPNativeTerrain>0.5)"));
    }
}
