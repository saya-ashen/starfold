package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePlanetFogTest {
    @Test void includedFogHelperDoesNotImplyProgramFogInputs(){
        String helper="vec4 linear_fog(vec4 c,float vertexDistance,float FogStart,float FogEnd,vec4 FogColor){return c;} ";
        String body="out vec4 fragColor; void main(){fragColor=vec4(1);}";
        for(String inputs:new String[]{"","uniform float FogStart,FogEnd; in float vertexDistance;","uniform vec4 FogColor; uniform float FogStart,FogEnd;"}){
            String patched=NativeGroundShaderSource.wrap("rendertype_lightning",false,helper+inputs+body);
            assertFalse(patched.contains("float cpFog="),inputs);
        }
        assertTrue(NativeGroundShaderSource.linearFogInterface(helper+"uniform vec4 FogColor; uniform float FogStart; uniform float FogEnd; in float vertexDistance;"));
    }
    @Test void solarRelightingDoesNotShadeTheAlreadyMixedVanillaFog(){
        String source="uniform vec4 FogColor; uniform float FogStart,FogEnd; in float vertexDistance; out vec4 fragColor; void main(){fragColor=linear_fog(vec4(1),vertexDistance,FogStart,FogEnd,FogColor);}";
        String patched=NativeGroundShaderSource.wrap("rendertype_solid",false,source);
        assertTrue(patched.contains("FogColor.rgb*cpFog*(vec3(1.0)-cpLight)"));
        // Algebraic reference: relight only the material contribution, not the fog colour.
        for(double fog:new double[]{0,.25,.5,1})for(double light:new double[]{0,.12,.6,1}){
            double material=.7,sky=.4,alreadyFogged=material*(1-fog)+sky*fog;
            assertEquals(material*light*(1-fog)+sky*fog,alreadyFogged*light+sky*fog*(1-light),1e-12);
        }
    }
}
