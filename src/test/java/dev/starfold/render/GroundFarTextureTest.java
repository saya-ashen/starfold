package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;

class GroundFarTextureTest {
    @Test void wrappingNegativeAndLargePositionsNeverEscapesItsOwnSprite() {
        var random=new Random(40499594);
        for(int atlas:new int[]{256,1024,4096,16384}) for(int i=0;i<10000;i++) {
            int start=random.nextInt(atlas/16)*16;
            float lo=(float)start/atlas, hi=(float)(start+16)/atlas;
            int packed=(short)GroundFarTexture.upper(hi);
            double world=(random.nextDouble()-.5)*1e7;
            double uv=GroundFarTexture.wrap(world,lo,packed,atlas);
            assertTrue(uv>=lo && uv<=hi);
            assertEquals(uv,GroundFarTexture.wrap(world+64,lo,packed,atlas),1e-8);
        }
    }
    @Test void borderPhaseAndCutoutFallback() {
        int upper=GroundFarTexture.upper(.5f);
        for(double x:new double[]{-65536,-16,-1,0,16,2048,1e6})
            assertEquals(GroundFarTexture.wrap(x+.25,.25,upper,256),GroundFarTexture.wrap(.25,.25,upper,256),1e-12);
        assertEquals(0x337722,GroundFarTexture.cutout(0,0,0x337722));
        assertEquals(0x337722,GroundFarTexture.cutout(.099,0,0x337722));
        assertEquals(0x112233,GroundFarTexture.cutout(.1,0x112233,0x337722));
    }
    @Test void onlyPatchTagsReuseUv2AndLightingSeesRestoredFullbright() throws Exception {
        for(int t=0;t<256;t++) assertEquals(t>=232&&t<=243||t==249||t==250,GroundFarTexture.patch(t));
        String source="#version 150\nin vec3 Position;in vec3 Normal;in vec4 Color;in vec2 UV0;in ivec2 UV2;uniform sampler2D Sampler2;uniform mat4 ProjMat,ModelViewMat;out vec4 vertexColor;vec4 extraLighting(){return texelFetch(Sampler2,UV2/16,0);}void main(){vertexColor=Color*extraLighting();gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}";
        var vertex=GroundShaderSource.patch("rendertype_solid",true,source);
        assertTrue(vertex.contains("in ivec2 UV2;"));
        assertTrue(vertex.contains("extraLighting(){return texelFetch(Sampler2,cpNativeLightUV2/16,0);}"));
        assertTrue(vertex.contains("cpNativePackedBounds=UV2;cpNativeLightUV2=UV2;"));
        assertTrue(vertex.contains("CPNativeActive>0.5&&CPNativeCover>0.5&&cpNativeFarTag"));
        assertTrue(vertex.indexOf("cpNativeLightUV2=ivec2(240)")<vertex.indexOf("cpNativeOriginalMain();"));
        assertTrue(vertex.contains("vec2(cpNativePackedBounds & ivec2(65535))/65535.0"));
        assertTrue(vertex.contains("cpNativeBlockUv=vec2(point.z,-point.y)"));
        assertTrue(vertex.contains("cpNativeBlockUv=vec2(point.x,-point.y)"));
        var renderer=Sources.text("GroundMeshVertices");
        assertTrue(renderer.contains("boolean repeat=GroundFarTexture.patch(surfaceTag)"));
        assertTrue(renderer.contains("repeat?GroundFarTexture.upper(sprite.getU1()):240"));
        assertFalse(Sources.text("GroundFarMesh").contains("uvFrame"));
        assertTrue(GroundShaderSource.TEXTURE.indexOf("dFdx(cpNativeBlockUv)")<GroundShaderSource.TEXTURE.indexOf("fract(cpNativeBlockUv)"));
        assertFalse(GroundShaderSource.TEXTURE.contains("dFdx(fract"));
        assertTrue(GroundShaderSource.TEXTURE.contains("if(cpNativeTextureAlpha<0.0 && texel.a<0.1)discard"));
        assertTrue(GroundShaderSource.TEXTURE.indexOf("if(cpNativeRepeat>0.5)")<GroundShaderSource.TEXTURE.indexOf("if(CPNativeLowFrequency>=1.0)"));
    }
    @Test void exportAndParseActualTerrainProgramsForOfflineGlslLinking() throws Exception {
        var out=Path.of("build/lod6-textures"); Files.createDirectories(out);
        for(String program:List.of("rendertype_solid","rendertype_cutout","rendertype_cutout_mipped","rendertype_translucent"))
            for(String suffix:List.of("vsh","fsh")) {
                String src;
                try(var in=TestArchiveResources.open("/assets/minecraft/shaders/core/"+program+"."+suffix)) {src=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
                // Include expansion for glslang and Veil AST parsing.
                src=expand(src);
                var patched=GroundShaderSource.patch(program,suffix.equals("vsh"),src);
                var ext=suffix.equals("vsh")?"vert":"frag";
                Files.writeString(out.resolve(program+"."+ext),patched);
                io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(patched,new HashMap<>());
            }
    }
    static String expand(String src) throws Exception {
        var pattern=java.util.regex.Pattern.compile("#moj_import <([^>]+)>");
        var matcher=pattern.matcher(src); var result=new StringBuilder();
        while(matcher.find()) {
            String replacement;
            try(var in=TestArchiveResources.open("/assets/minecraft/shaders/include/"+matcher.group(1))) {replacement=expand(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).replaceAll("(?m)^#version[^\\n]*\\n", "");}
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result); return result.toString();
    }
}
