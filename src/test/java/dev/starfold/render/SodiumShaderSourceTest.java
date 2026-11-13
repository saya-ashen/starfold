package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** The Sodium 0.8.13 chunk shader sources are the contract for this projection, not a guess. */
class SodiumShaderSourceTest {
    private static final String VERTEX =
            """
            #version 330 core

            #import <sodium:include/fog.glsl>
            #import <sodium:include/chunk_vertex.glsl>
            #import <sodium:include/chunk_matrices.glsl>

            out vec4 v_Color;
            out vec2 v_TexCoord;

            flat out uint v_Material;

            #ifdef USE_FOG
            out float v_FragDistance;
            #endif

            uniform int u_FogShape;
            uniform vec3 u_RegionOffset;
            uniform vec2 u_TexCoordShrink;

            uniform sampler2D u_LightTex; // The light map texture sampler

            uvec3 _get_relative_chunk_coord(uint pos) {
                // Packing scheme is defined by LocalSectionIndex
                return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
            }

            vec3 _get_draw_translation(uint pos) {
                return _get_relative_chunk_coord(pos) * vec3(16.0);
            }

            void main() {
                _vert_init();

                // Transform the chunk-local vertex position into world model space
                vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
                vec3 position = _vert_position + translation;

            #ifdef USE_FOG
                v_FragDistance = getFragDistance(u_FogShape, position);
            #endif

                // Transform the vertex position into model-view-projection space
                gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

                // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
                v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
                v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

                v_Material = _material_params;
            }
            """;

    private static final String FRAGMENT =
            """
            #version 330 core

            #ifndef MAX_TEXTURE_LOD_BIAS
            #error "MAX_TEXTURE_LOD_BIAS constant not specified"
            #endif

            #import <sodium:include/fog.glsl>
            #import <sodium:include/chunk_material.glsl>

            in vec4 v_Color; // The interpolated vertex color
            in vec2 v_TexCoord; // The interpolated block texture coordinates
            in float v_FragDistance; // The fragment's distance from the camera

            flat in uint v_Material;

            uniform sampler2D u_BlockTex; // The block texture

            uniform vec4 u_FogColor; // The color of the shader fog
            uniform float u_FogStart; // The starting position of the shader fog
            uniform float u_FogEnd; // The ending position of the shader fog

            out vec4 fragColor; // The output fragment for the color framebuffer

            void main() {
                float lodBias = _material_use_mips(v_Material) ? 0.0 : float(-MAX_TEXTURE_LOD_BIAS);

                vec4 color = texture(u_BlockTex, v_TexCoord, lodBias);
                color *= v_Color; // Apply per-vertex color modulator

            #ifdef USE_FRAGMENT_DISCARD
                if (color.a < _material_alpha_cutoff(v_Material)) {
                    discard;
                }
            #endif

                fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
            }
            """;

    @Test
    void vertexWarpRunsAfterSodiumAssembledTheFlatPosition() {
        String patched = SodiumShaderSource.patch(SodiumShaderSource.VERTEX, VERTEX);
        int anchor =
                patched.indexOf(
                        "gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);");
        int warp = patched.indexOf("cpNativeWarp(position);");
        assertTrue(anchor >= 0);
        assertTrue(warp > anchor, "the warp must see Sodium's finished position");
        assertTrue(patched.contains("#import <starfold:include/native_terrain_vertex.glsl>"));
        // The include reads u_ModelViewMatrix, so it has to follow chunk_matrices.glsl.
        assertTrue(
                patched.indexOf("#import <sodium:include/chunk_matrices.glsl>")
                        < patched.indexOf("<starfold:include/native_terrain_vertex.glsl>"));
        assertTrue(
                patched.indexOf("<starfold:include/native_terrain_vertex.glsl>")
                        < patched.indexOf("void main() {"));
        assertEquals(1, count(patched, "cpNativeWarp(position);"));
        assertEquals(patched, SodiumShaderSource.patch(SodiumShaderSource.VERTEX, patched));
    }

    @Test
    void fragmentFinishesSodiumsOwnFogInsteadOfReplacingIt() {
        String patched = SodiumShaderSource.patch(SodiumShaderSource.FRAGMENT, FRAGMENT);
        int fog =
                patched.indexOf(
                        "fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);");
        int finish = patched.indexOf("cpNativeFragment(fragColor);");
        assertTrue(fog >= 0);
        assertTrue(finish > fog);
        // u_FogStart/u_FogEnd/v_FragDistance are declared in Sodium's body, not in an import.
        for (String declaration :
                new String[] {
                    "uniform vec4 u_FogColor;",
                    "uniform float u_FogStart;",
                    "uniform float u_FogEnd;"
                })
            assertTrue(
                    patched.indexOf(declaration)
                            < patched.indexOf("<starfold:include/native_terrain_fragment.glsl>"),
                    declaration);
        assertTrue(
                patched.indexOf("in float v_FragDistance;")
                        < patched.indexOf("<starfold:include/native_terrain_fragment.glsl>"));
        assertTrue(
                patched.indexOf("<starfold:include/native_terrain_fragment.glsl>")
                        < patched.indexOf("void main() {"));
        assertEquals(patched, SodiumShaderSource.patch(SodiumShaderSource.FRAGMENT, patched));
    }

    @Test
    void onlyTheSodiumChunkShadersArePatchedAndUnknownLayoutsFailLoudly() {
        assertTrue(SodiumShaderSource.handles("blocks/block_layer_opaque.vsh"));
        assertTrue(SodiumShaderSource.handles("blocks/block_layer_opaque.fsh"));
        assertFalse(SodiumShaderSource.handles("include/fog.glsl"));
        assertEquals(FRAGMENT, SodiumShaderSource.patch("include/fog.glsl", FRAGMENT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SodiumShaderSource.patch(
                                SodiumShaderSource.VERTEX,
                                VERTEX.replace(
                                        "gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);",
                                        "gl_Position = vec4(position, 1.0);")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SodiumShaderSource.patch(
                                SodiumShaderSource.FRAGMENT,
                                FRAGMENT.replace("_linearFog", "fog")));
    }

    @Test
    void everyUniformTheIncludesReadIsUploaded() throws Exception {
        var names = new LinkedHashSet<String>();
        for (String file :
                new String[] {"native_terrain_vertex.glsl", "native_terrain_fragment.glsl"}) {
            String glsl =
                    Files.readString(
                            Path.of("src/main/resources/assets/starfold/shaders/include/" + file));
            var matcher = Pattern.compile("\\b(CP[A-Za-z]+)\\b").matcher(glsl);
            while (matcher.find()) names.add(matcher.group(1));
        }
        assertFalse(names.isEmpty());
        String owner = Sources.text("GroundProjectionUniforms").toString();
        for (String name : names)
            assertTrue(owner.contains("\"" + name + "\""), name + " is read but never uploaded");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
        return count;
    }
}
