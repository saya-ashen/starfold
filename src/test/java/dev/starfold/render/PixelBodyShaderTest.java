package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.SourceText;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;

/** Source/Veil contracts; real GPU and spyglass acceptance is recorded by pixel-bodies probes. */
class PixelBodyShaderTest {
    private static SourceText shader(String name) throws Exception {
        return SourceText.of(Files.readString(GasRenderTest.SHADERS.resolve(name + ".fsh")));
    }

    @Test
    void bothBodiesUseCubeTexelCentresAndContinuousPreQuantizationDerivatives() throws Exception {
        for (String name : List.of("native_ground_sun", "native_gas_giant")) {
            var source = shader(name);
            assertTrue(source.contains("cube=n/max(a.x,max(a.y,a.z))"));
            assertTrue(source.contains("vec3 footprint=fwidth(texel)"));
            assertTrue(source.indexOf("fwidth(texel)") < source.indexOf("floor(texel)"));
            assertTrue(source.contains("if(a.x>=a.y&&a.x>=a.z)q.x=sign(n.x)"));
            assertTrue(source.contains("else if(a.y>=a.z)q.y=sign(n.y)"));
            assertTrue(source.contains("else q.z=sign(n.z)"));
            assertTrue(source.contains("q=normalize(q)"));
            assertTrue(source.contains("1.0-smoothstep(0.5,1.5,max(footprint.x,max(footprint.y,footprint.z)))"));
            assertFalse(source.contains("atan("));
            assertFalse(source.contains("asin("));
            String text = Files.readString(GasRenderTest.SHADERS.resolve(name + ".fsh"));
            var parsed = io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(text, new HashMap<>());
            assertNotNull(io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(parsed.toSourceString(), new HashMap<>()));
        }
    }

    @Test
    void sunStepsTimePaletteAndLimbWhileGasKeepsLightingAndFog() throws Exception {
        var sun = shader("native_ground_sun");
        assertTrue(sun.contains("floor(SunTime*0.25+hash(cell)*4.0)"));
        assertTrue(sun.contains("floor(grain*4.0)/3.0"));
        assertTrue(sun.contains("view=normalize(EyeBody-SunBody-q*SunRadius)"));
        assertTrue(sun.contains("floor(clamp(dot(q,view),0.0,1.0)*3.0+0.5)/3.0"));
        assertTrue(sun.contains("mix(mix(SurfaceLow,SurfaceHigh,0.5)*0.94,color,detail)"));
        assertFalse(sun.contains("sin(n.x*48.0"));
        var gas = shader("native_gas_giant");
        assertTrue(gas.contains("bands=mix(0.5,bands,detail)"));
        assertTrue(gas.contains("*5.0)/4.0"));
        assertTrue(gas.contains("dot(n,toSun/max(0.000001,length(toSun)))"));
        assertTrue(gas.contains("Ambient+(1.0-Ambient)*daylight*gasLight"));
        assertTrue(gas.contains("*AtmosphereGrade"));
        assertTrue(gas.contains("mix(SkyColor,color,BodyFade)"));
        assertTrue(gas.contains("if(Inside>0.5){fragColor=vec4(GasFogColor,1.0);return;}"));
    }
}
