package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GroundNightVisionTest {
    @Test
    void nativeAndCompactTerrainBothSupplyLightmapVisibilityAndBlockLight() {
        String source =
                "#version 150\n"
                    + "in vec3 Position;in vec3 Normal;in vec4 Color;in vec2 UV0;in ivec2"
                    + " UV2;uniform sampler2D Sampler2;uniform mat4 ProjMat,ModelViewMat;out vec4"
                    + " vertexColor;void"
                    + " main(){vertexColor=Color;gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}";
        for (String name :
                new String[] {
                    "rendertype_solid",
                    "rendertype_cutout",
                    "rendertype_translucent",
                    "starfold:ground_compact_solid"
                }) {
            String shader = GroundShaderSource.patch(name, true, source);
            assertTrue(
                    shader.contains("cpNativeSkyVisibility=clamp(float(cpNativeLightUV2.y)/240.0"),
                    name);
            assertTrue(shader.contains("cpNativeBlockLight=texture(Sampler2"), name);
            assertTrue(
                    shader.indexOf("if(CPNativeActive<0.5)return")
                            < shader.indexOf("cpNativeSkyVisibility=clamp"));
        }
    }

    @Test
    void nightVisionActsOnExtraSolarLightingBeforeMultiplication() {
        String fragment = GroundUndergroundTest.fragment();
        assertTrue(fragment.contains("uniform float CPNativeNightVision"));
        assertTrue(
                fragment.indexOf("cpLight=mix(cpLight")
                        < fragment.indexOf("fragColor.rgb*=cpLight"));
        assertTrue(
                fragment.contains("cpLight/max(max(cpLight.r,cpLight.g),max(cpLight.b,0.0001))"));
    }
}
