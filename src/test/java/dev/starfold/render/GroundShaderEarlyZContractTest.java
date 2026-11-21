package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.PlanetSurfaceRender;
import dev.starfold.TestArchiveResources;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Contracts at the actual resource -> CoreShaderSource -> packed final-source boundary. */
class GroundShaderEarlyZContractTest {
    private static final Path REPORT = Path.of("build/reports/quadtree/shader-final");

    private static String resource(String path) throws Exception {
        try (var in = TestArchiveResources.open("/assets/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static boolean discards(String source) {
        return Pattern.compile("\\bdiscard\\b").matcher(withoutComments(source)).find();
    }

    private static String expandIncludes(String source) throws Exception {
        var matcher = Pattern.compile("#moj_import\\s+<([^>]+)>").matcher(source);
        var result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result,
                java.util.regex.Matcher.quoteReplacement(expandIncludes(resource("minecraft/shaders/include/" + matcher.group(1)).replaceAll("(?m)^#version[^\\n]*\\n", ""))));
        return matcher.appendTail(result).toString();
    }

    @Test
    void allRegisteredVariantsHaveResourcesAndFullySpecializedFinalPrograms() throws Exception {
        Files.createDirectories(REPORT);
        for (var variant : GroundCompact.Variant.values()) {
            String name = variant.name;
            assertTrue(CoreShaderSource.supported(name), name);
            String path = name.substring("starfold:".length());
            var json = JsonParser.parseString(resource("starfold/shaders/core/" + path + ".json")).getAsJsonObject();
            assertEquals(name, json.get("vertex").getAsString());
            assertEquals(name, json.get("fragment").getAsString());
            assertEquals("PackedPosition", json.getAsJsonArray("attributes").get(0).getAsString());
            String vertex = CoreShaderSource.patch(name, true, resource("starfold/shaders/core/" + path + ".vsh"));
            String fragment = CoreShaderSource.patch(name, false, resource("starfold/shaders/core/" + path + ".fsh"));
            Files.writeString(REPORT.resolve(path + ".vsh"), expandIncludes(vertex));
            Files.writeString(REPORT.resolve(path + ".fsh"), expandIncludes(fragment));
            assertTrue(vertex.contains("void cpCapOriginalMain()"), name);
            assertTrue(vertex.contains("in vec3 PackedPosition;"), name);
            assertTrue(vertex.contains("in uvec3 Packed;"), name);
            assertFalse(vertex.contains("in vec3 Position;"), name);
            assertTrue(vertex.contains("Position=PackedPosition/16.0-vec3(0,2048,0)"), name);
            assertTrue(vertex.contains("Position.y+=cpMaterial(material+1).w"), name);
            assertTrue(vertex.contains("cpNativeLightUV2=ivec2(int(Packed.z)&255,int(Packed.z)>>8)"), name);
            assertTrue(vertex.contains("gl_ClipDistance[0]=cpNativeFacing;"), name);
            assertTrue(vertex.contains("if(CPNativeEye.y<=160.0&&CPNativeSourceNormal.y< -0.5)"), name);
            assertTrue(vertex.contains("cpNativeFacing=-1.0;gl_Position=vec4(0.0,0.0,2.0,1.0);"), name);
            assertTrue(vertex.indexOf("CPNativeSourceNormal.y< -0.5")
                    < vertex.indexOf("vec3 point=CPNativeEye+relative-CPNativeSourcePlot"), name);
            assertTrue(vertex.contains("cpNativeRepair(point.xz)"), name);
            assertTrue(vertex.contains("cpNativeOrbitRepair(point)"), name);
            assertTrue(vertex.contains("cpSurfaceClass=floor(cpSurfaceUV.y/2.0)"), name);
            assertTrue(vertex.contains("cpSurfaceUV.y-=cpSurfaceClass*2.0"), name);
            assertTrue(vertex.contains("cpNativeRepeat=1.0"), name);
            assertTrue(vertex.contains("tag>=240.0&&tag<=243.0"), name);
            assertTrue(vertex.contains("tag==234.0"), name);
            assertTrue(fragment.contains("textureGrad(source,repeated,dx,dy)"), name);
            assertTrue(fragment.contains("vec3 coverNormal=cpNativeCoverNormal()"), name);
            assertTrue(fragment.contains("dFdx(cpNativeSolarPosition)"), name);
            assertTrue(fragment.contains("cpGroundDaylight(normalize(CPNativeSunDirection)"), name);
            assertTrue(fragment.contains("CPAtmosphereGrade"), name);
            assertTrue(fragment.contains("cpNativeRangeFog*CPAtmosphereFog"), name);
            assertTrue(fragment.contains("cpNativeTextureAlpha<0.0?1.0:cpNativeTextureAlpha"), name);
            for (String obsolete : List.of("CPNativeCoverage", "cpNativeOwner", "cpNativeResident", "CPNativeLodPass", "CPNativeStitch", "CPNativeSelection", "cpNativePatchOld", "cpGroundHidden", "cpNativeDither", "CPNativeFade", "CPNativeLowFrequency")) {
                assertFalse(vertex.contains(obsolete), name + " vertex " + obsolete);
                assertFalse(fragment.contains(obsolete), name + " fragment " + obsolete);
            }
            assertEquals(variant == GroundCompact.Variant.CUTOUT || variant == GroundCompact.Variant.MASKED,
                    fragment.contains("texel.a<0.1"), name);
            assertEquals(variant == GroundCompact.Variant.MASKED, fragment.contains("uniform usampler2D CPNativeMaskTex"), name);
            assertEquals(variant == GroundCompact.Variant.CUTOUT || variant == GroundCompact.Variant.MASKED, discards(fragment), name);
            if (variant == GroundCompact.Variant.MASKED) {
                assertTrue(fragment.contains("int(CPNativeFace)*128"));
                assertTrue(fragment.contains("texelFetch(CPNativeMaskTex,at,0).r!=0u"));
                assertTrue(fragment.contains("interpolateAtSample(cpNativeSourcePoint,index)"));
                assertTrue(fragment.contains("gl_SampleMask[0]&=~(1<<index)"));
                assertFalse(fragment.contains("out int gl_SampleMask"));
                assertTrue(fragment.contains("gl_NumSamples>1"));
                assertTrue(fragment.contains("#extension GL_ARB_gpu_shader5 : enable"));
                assertTrue(fragment.contains("cpNativeCoverNormal();\n"), "preprocessor directives start on a new line");
            } else {
                assertFalse(fragment.contains("gl_SampleMask"));
            }
        }
    }

    @Test
    void everyVariantMapsBothAppearanceStagesToTheExistingCompactProviderName() throws Exception {
        var field = PlanetSurfaceRender.class.getDeclaredField("appearance");
        field.setAccessible(true);
        Object previous = field.get(null);
        var requested = new ArrayList<String>();
        field.set(null, new PlanetSurfaceRender.Appearance() {
            public PlanetSurfaceRender.Patch vertex(String name) {
                requested.add("vertex:" + name);
                return new PlanetSurfaceRender.Patch("out float phase3Snow;\n", "phase3Snow=CPNativeCover+cpSurfaceClass;\n");
            }
            public PlanetSurfaceRender.Patch near(String name) {
                requested.add("fragment:" + name);
                return new PlanetSurfaceRender.Patch("in float phase3Snow;\n", "fragColor.rgb+=vec3(phase3Snow*0.0);\n");
            }
            public PlanetSurfaceRender.Patch exterior() { return PlanetSurfaceRender.Patch.NONE; }
            public void uniforms(net.minecraft.client.renderer.ShaderInstance shader, PlanetSurfaceRender.Context context) {}
        });
        try {
            for (var variant : GroundCompact.Variant.values()) {
                String path = variant.name.substring("starfold:".length());
                String vertex = CoreShaderSource.patch(variant.name, true, resource("starfold/shaders/core/" + path + ".vsh"));
                String fragment = CoreShaderSource.patch(variant.name, false, resource("starfold/shaders/core/" + path + ".fsh"));
                assertTrue(vertex.contains("phase3Snow=CPNativeCover+cpSurfaceClass"));
                assertTrue(fragment.contains("fragColor.rgb+=vec3(phase3Snow*0.0)"));
                assertEquals(List.of("vertex:starfold:ground_compact", "fragment:starfold:ground_compact"), requested);
                requested.clear();
            }
        } finally { field.set(null, previous); }
    }

    @Test
    void optionalMonsoonProviderInjectsRealSnowAndClimateIntoEveryVariant() throws Exception {
        Class<?> seasons;
        try { seasons = Class.forName("dev.starfold.monsoon.Seasons"); }
        catch (ClassNotFoundException absent) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Optional monsoon module absent");
            return;
        }
        var field = PlanetSurfaceRender.class.getDeclaredField("appearance");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, null);
        try {
            seasons.getConstructor().newInstance();
            for (var variant : GroundCompact.Variant.values()) {
                String path = variant.name.substring("starfold:".length());
                String vertex = CoreShaderSource.patch(variant.name, true, resource("starfold/shaders/core/" + path + ".vsh"));
                String fragment = CoreShaderSource.patch(variant.name, false, resource("starfold/shaders/core/" + path + ".fsh"));
                assertTrue(vertex.contains("sfUp=max(0.0,Normal.y)"));
                assertTrue(vertex.contains("if(CPNativeCover>0.5)"));
                assertTrue(fragment.contains("float snow="));
                assertTrue(fragment.contains("sfClimate(up)"));
                assertTrue(fragment.contains("sfUp,sfSky,cpSurfaceClass)"));
                assertEquals(variant == GroundCompact.Variant.CUTOUT || variant == GroundCompact.Variant.MASKED, discards(fragment));
                Files.writeString(REPORT.resolve(path + "_monsoon.vsh"), expandIncludes(vertex));
                Files.writeString(REPORT.resolve(path + "_monsoon.fsh"), expandIncludes(fragment));
            }
        } finally { field.set(null, previous); }
    }

    @Test
    void vanillaTerrainKeepsClosureWhileAllGeneratedProgramsDropLegacyOwnership() throws Exception {
        String fragment = CoreShaderSource.patch("rendertype_solid", false, resource("minecraft/shaders/core/rendertype_solid.fsh"));
        assertTrue(fragment.contains("CPNativeClosure["));
        assertTrue(fragment.contains("CPNativeTerrain>0.5&&CPNativeDerived<0.5"));
        assertTrue(fragment.contains("if(cpNativeFacing<0.0)discard"));
        for (String obsolete : List.of("CPNativeCoverage", "CPNativeStitch", "CPNativeLodPass", "cpNativeOwner", "cpNativeResident", "cpGroundHidden", "cpNativeDither"))
            assertFalse(fragment.contains(obsolete), obsolete);
    }
}
