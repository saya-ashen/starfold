package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;

import java.util.List;

class CoreShaderTest {
    private String resource(String name) throws Exception {
        try (var in = TestArchiveResources.open("/assets/minecraft/shaders/core/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void veilReadsMekasuitHelperWithoutAForwardDeclaration() {
        var id =
                net.minecraft.resources.ResourceLocation.parse(
                        "mekanism:shaders/core/rendertype_mekasuit.fsh");
        String signature = "bool shouldTint(float red, float green, float blue)";
        String main = "void main() { shouldTint(1.0, 1.0, 1.0); }";
        String helper = signature + " { return red > green; }";
        String source = signature + ";\n" + main + "\n" + helper + "\n";
        String repaired = CoreShaderSource.veilFragment(id, true, source);
        assertFalse(repaired.contains(signature + ";"));
        assertTrue(repaired.indexOf(helper) < repaired.indexOf(main));
        assertEquals(repaired, CoreShaderSource.veilFragment(id, true, repaired));
        assertEquals(source, CoreShaderSource.veilFragment(id, false, source));
    }

    @Test
    void terrainFluidsEntitiesParticlesAndBlockEntityPathsAreWrapped() throws Exception {
        for (String name :
                List.of(
                        "rendertype_solid",
                        "rendertype_cutout",
                        "rendertype_cutout_mipped",
                        "rendertype_translucent",
                        "rendertype_translucent_moving_block",
                        "rendertype_entity_cutout",
                        "rendertype_entity_alpha",
                        "rendertype_entity_solid",
                        "rendertype_entity_translucent",
                        "rendertype_item_entity_translucent_cull",
                        "particle",
                        "rendertype_lines",
                        "rendertype_crumbling",
                        "rendertype_text",
                        "rendertype_entity_shadow")) {
            String v = CoreShaderSource.patch(name, true, resource(name + ".vsh"));
            String f = CoreShaderSource.patch(name, false, resource(name + ".fsh"));
            assertTrue(v.startsWith("#version 150"));
            assertTrue(f.startsWith("#version 150"));
            assertTrue(v.contains("void cpCapOriginalMain()"));
            assertTrue(v.contains("gl_Position.xy+=CPWorldJitter*gl_Position.w"));
            assertFalse(v.contains("cpScan_vertexColor"), "Non-scanline modes must omit geometry capture");
            assertTrue(v.contains("out float cpNativeFacing") || v.contains("out float cpScan_cpNativeFacing"));
            if (!v.contains("uniform sampler2D Sampler2;"))
                assertFalse(v.contains("texelFetch(Sampler2"), name);
            assertTrue(f.contains("in float cpNativeFacing"));
            assertTrue(f.contains("cpCapOriginalMain();"));
            boolean multisampledTerrain = name.equals("rendertype_solid")
                    || name.equals("rendertype_cutout") || name.equals("rendertype_cutout_mipped");
            assertEquals(multisampledTerrain, f.contains("interpolateAtCentroid(texCoord0)"), name);
            if (multisampledTerrain) {
                assertTrue(f.contains("cpNativeSample(Sampler0,CP_BLOCK_UV)"), name);
                assertTrue(f.contains("#else\n#define CP_BLOCK_UV texCoord0"), name);
            }
        }
    }

    @Test
    void farTexturesKeepMipmappedAtlasDetailAtEveryAltitude() {
        assertTrue(GroundShaderSource.TEXTURE.contains("textureGrad(source,repeated,dx,dy)"));
        assertTrue(
                GroundShaderSource.TEXTURE.contains(
                        "return vec4(rgb,cpNativeTextureAlpha<0.0?1.0:cpNativeTextureAlpha)"));
        assertFalse(GroundShaderSource.TEXTURE.contains("mix(rgb,cpNativeRepeatAlbedo"));
    }

    @Test
    void inactiveAndNearFieldVerticesKeepTheirOriginalClipCoordinates() throws Exception {
        String v =
                CoreShaderSource.patch("rendertype_solid", true, resource("rendertype_solid.vsh"));
        assertTrue(v.contains("if(CPNativeActive<0.5)return"));
        assertTrue(v.contains("Position + ChunkOffset"));
        assertTrue(v.contains("minecraft_sample_lightmap"));
    }

    @Test
    void skyGuiPostAndUnknownShadersAreUntouched() {
        for (String name :
                List.of(
                        "position_tex",
                        "position_color",
                        "rendertype_gui",
                        "rendertype_gui_overlay",
                        "rendertype_clouds",
                        "blit_screen",
                        "custom_machine_shader"))
            assertEquals("unchanged", CoreShaderSource.patch(name, true, "unchanged"));
    }

    @Test
    void unsupportedReplacementCoreShadersFailWithAnExplicitName() {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoreShaderSource.patch("rendertype_solid", true, "void main() {}"));
        assertTrue(e.getMessage().contains("rendertype_solid"));
    }
}
