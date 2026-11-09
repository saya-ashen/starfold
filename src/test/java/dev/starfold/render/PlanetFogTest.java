package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlanetFogTest {
    @Test
    void includedFogHelperDoesNotImplyProgramFogInputs() {
        String helper =
                "vec4 linear_fog(vec4 c,float vertexDistance,float FogStart,float FogEnd,vec4 FogColor){return c;} ";
        String body = "out vec4 fragColor; void main(){fragColor=vec4(1);}";
        for (String inputs :
                new String[] {
                    "",
                    "uniform float FogStart,FogEnd; in float vertexDistance;",
                    "uniform vec4 FogColor; uniform float FogStart,FogEnd;"
                }) {
            String patched =
                    GroundShaderSource.wrap("rendertype_lightning", false, helper + inputs + body);
            assertFalse(patched.contains("float cpFog="), inputs);
        }
        assertTrue(
                GroundShaderSource.linearFogInterface(
                        helper
                                + "uniform vec4 FogColor; uniform float FogStart; uniform float FogEnd; in float vertexDistance;"));
    }

    @Test
    void solarRelightingDoesNotShadeTheAlreadyMixedVanillaFog() {
        String source =
                "uniform vec4 FogColor; uniform float FogStart,FogEnd; in float vertexDistance; out vec4 fragColor; void main(){fragColor=linear_fog(vec4(1),vertexDistance,FogStart,FogEnd,FogColor);}";
        String patched = GroundShaderSource.wrap("rendertype_solid", false, source);
        assertTrue(patched.contains("FogColor.rgb*cpFog*(vec3(1.0)-cpLight)"));
        assertTrue(patched.contains("fragColor.rgb*=CPAtmosphereGrade"));
        assertTrue(patched.contains("FogColor.rgb*cpGradeFog*(vec3(1.0)-CPAtmosphereGrade)"));
        // Algebraic reference: relight only the material contribution, not the fog colour.
        for (double fog : new double[] {0, .25, .5, 1})
            for (double light : new double[] {0, .12, .6, 1}) {
                double material = .7, sky = .4, alreadyFogged = material * (1 - fog) + sky * fog;
                assertEquals(
                        material * light * (1 - fog) + sky * fog,
                        alreadyFogged * light + sky * fog * (1 - light),
                        1e-12);
                for (double grade : new double[] {.97, 1, 1.03})
                    assertEquals(
                            material * light * grade * (1 - fog) + sky * fog,
                            (alreadyFogged * light + sky * fog * (1 - light)) * grade
                                    + sky * fog * (1 - grade),
                            1e-12);
            }
    }
}
