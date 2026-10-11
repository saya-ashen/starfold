package dev.starfold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import io.github.ocelot.glslprocessor.api.GlslParser;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned Veil bytecode and real GLSL parser, without Minecraft or an OpenGL context. */
class VeilRenderCompatibilityTest {
    private ClassNode type(String name) throws Exception {
        try(var in=TestArchiveResources.open("/"+name+".class")) {
            assertNotNull(in,name); var node=new ClassNode(); new ClassReader(in).accept(node,0); return node;
        }
    }
    private List<MethodInsnNode> calls(MethodNode method) {
        var out=new ArrayList<MethodInsnNode>();
        for(var i:method.instructions) if(i instanceof MethodInsnNode c) out.add(c);
        return out;
    }
    private String resource(String name) throws Exception {
        try(var in=TestArchiveResources.open(name)) { assertNotNull(in,name); return new String(in.readAllBytes()); }
    }
    private String expand(String source) throws Exception {
        var m=Pattern.compile("#moj_import\\s+[<\"]([^>\"]+)[>\"]").matcher(source); var out=new StringBuilder();
        while(m.find()) m.appendReplacement(out,Matcher.quoteReplacement(expand(resource("/assets/minecraft/shaders/include/"+m.group(1)).replaceAll("(?m)^\\s*#version[^\\r\\n]*",""))));
        m.appendTail(out); return out.toString();
    }
    @Test void veilReallyRecompilesRawResourcesOutsideOurOriginalProgramHook() throws Exception {
        var compiler=type("foundry/veil/impl/client/render/dynamicbuffer/VanillaShaderCompiler");
        var c=calls(compiler.methods.stream().filter(m->m.name.equals("compileShader")).findFirst().orElseThrow());
        assertTrue(c.stream().anyMatch(i->i.name.equals("openAsReader")));
        assertTrue(c.stream().anyMatch(i->i.owner.endsWith("VanillaShaderProcessor") && i.name.equals("modify")));
        assertFalse(c.stream().anyMatch(i->i.owner.equals("com/mojang/blaze3d/shaders/Program")));
    }
    @Test void veilOwnsAdditionalUniformValuesAndUploadsThemOnApply() throws Exception {
        var veil=type("foundry/veil/mixin/shader/client/ShaderInstanceMixin");
        var apply=calls(veil.methods.stream().filter(m->m.name.equals("apply")).findFirst().orElseThrow());
        assertTrue(apply.stream().anyMatch(i->i.owner.endsWith("Uniform") && i.name.equals("upload")));
        var locations=calls(veil.methods.stream().filter(m->m.name.equals("updateLocations")).findFirst().orElseThrow());
        assertTrue(locations.stream().anyMatch(i->i.name.equals("glGetActiveUniform")));
        assertTrue(locations.stream().anyMatch(i->i.name.equals("memSet")));
        assertTrue(locations.stream().anyMatch(i->i.owner.endsWith("Uniform") && i.name.equals("<init>")));
    }
    @Test void ourUploadsRunAfterOriginalApplyAndWriteThroughManagedValues() throws Exception {
        var wrapper=type("dev/starfold/mixin/LocalCapShaderMixin").methods.stream()
                .filter(m->m.name.equals("starfold$capUniforms")).findFirst().orElseThrow();
        var names=calls(wrapper).stream().map(i->i.name).toList();
        assertTrue(names.indexOf("call")<names.indexOf("apply"));
        var writer=type("dev/starfold/ShaderUniforms"); int setters=0;
        for(var m:writer.methods) if(m.name.equals("set")) {
            var c=calls(m); var order=c.stream().map(i->i.name).toList();
            assertTrue(order.indexOf("getUniform")<order.indexOf("set"));
            assertTrue(order.indexOf("set")<order.indexOf("upload")); setters++;
        }
        assertEquals(4,setters);
        assertTrue(type("dev/starfold/mixin/LocalCapShaderMixin").methods.stream()
                .flatMap(m->calls(m).stream()).anyMatch(i->i.owner.endsWith("ShaderUniforms") && i.name.equals("invalidate")));
    }
    @Test void sourcePatchIsIdempotentAndAcceptsNamespacedVanillaNames() throws Exception {
        for(boolean vertex:new boolean[]{true,false}) {
            var source=resource("/assets/minecraft/shaders/core/rendertype_solid"+(vertex?".vsh":".fsh"));
            var patched=LocalCapShaderSource.patch("rendertype_solid",vertex,source);
            assertEquals(patched,LocalCapShaderSource.patch("minecraft:rendertype_solid",vertex,patched));
            assertEquals(patched,LocalCapShaderSource.patch("minecraft:rendertype_solid",vertex,source));
        }
        assertFalse(LocalCapShaderSource.supported("minecraft:rendertype_gui"));
        assertFalse(LocalCapShaderSource.supported("unrelated:rendertype_solid"));
    }
    @Test void patchedNearAndGlobeStagesSurviveActualVeilGlslParserRoundtrip() throws Exception {
        for(String name:List.of("rendertype_solid","rendertype_cutout_mipped","rendertype_translucent","rendertype_entity_solid","particle"))
            for(boolean vertex:new boolean[]{true,false}) {
                String path="/assets/minecraft/shaders/core/"+name;
                String source=expand(resource(path+(vertex?".vsh":".fsh")));
                source=LocalCapShaderSource.patch(name,vertex,source);
                var tree=GlslParser.preprocessParse(source,new HashMap<>());
                String rebuilt=tree.toSourceString();
                assertTrue(rebuilt.contains("cpNativeOriginalMain"),name);
                assertTrue(rebuilt.contains("CPNativeActive"),name);
                assertEquals(rebuilt,LocalCapShaderSource.patch(name,vertex,rebuilt),name);
            }
    }
    /** The NPE in DynamicBufferProcessor came from walking a main of ours; and the serializer is lossy on our text. */
    @Test void veilSeesUpstreamsOwnMainAndOurWrappersGoAroundItsOutput() throws Exception {
        var names=new TreeSet<String>();
        for(String archive:System.getProperty("starfold.testArchives").split(Pattern.quote(java.io.File.pathSeparator)))try(var zip=new java.util.zip.ZipFile(archive)){
            for(var e:Collections.list(zip.entries()))if(e.getName().startsWith("assets/minecraft/shaders/core/")&&e.getName().endsWith(".vsh"))
                names.add(e.getName().substring("assets/minecraft/shaders/core/".length(),e.getName().length()-4));
        }
        names.removeIf(n->!LocalCapShaderSource.supported(n));assertTrue(names.size()>40,names.toString());
        for(String name:names)for(boolean vertex:new boolean[]{true,false}) {
            String source=expand(resource("/assets/minecraft/shaders/core/"+name+(vertex?".vsh":".fsh")));
            String hooked=LocalCapShaderSource.hooks(name,vertex,source);
            assertFalse(hooked.contains("OriginalMain"),name);
            var tree=GlslParser.preprocessParse(hooked,new HashMap<>());
            assertTrue(tree.mainFunction().orElseThrow().stream().count()>0,name);
            String direct=LocalCapShaderSource.wrap(name,vertex,hooked),veiled=LocalCapShaderSource.wrap(name,vertex,tree.toSourceString());
            String marker="\nvoid cpNativeOriginalMain() {cpCapOriginalMain();}";
            assertEquals(direct.substring(direct.indexOf(marker)),veiled.substring(veiled.indexOf(marker)),name+(vertex?".vsh":".fsh"));
            assertEquals(veiled,LocalCapShaderSource.patch(name,vertex,veiled),name);
        }
    }
}
