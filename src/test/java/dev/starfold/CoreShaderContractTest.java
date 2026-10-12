package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Exact upstream bytecode targets and scope separation, not a game or GL startup test. */
class CoreShaderContractTest {
    private static ClassNode read(String name) throws Exception {
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in, name);
            var c = new ClassNode();
            new ClassReader(in).accept(c, 0);
            return c;
        }
    }

    private static List<MethodInsnNode> calls(String type, String method) throws Exception {
        var out = new ArrayList<MethodInsnNode>();
        for (var m : read(type).methods)
            if (method == null || m.name.equals(method))
                for (var i : m.instructions) if (i instanceof MethodInsnNode c) out.add(c);
        return out;
    }

    @Test
    void shaderSourceWrapMatchesActualPreprocessorCallAndEnclosingArguments() throws Exception {
        var program = read("com/mojang/blaze3d/shaders/Program");
        var method =
                program.methods.stream()
                        .filter(m -> m.name.equals("compileShaderInternal"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)I",
                method.desc);
        assertEquals(
                1,
                calls(program.name, method.name).stream()
                        .filter(
                                c ->
                                        c.owner.equals(
                                                        "com/mojang/blaze3d/preprocessor/GlslPreprocessor")
                                                && c.name.equals("process")
                                                && c.desc.equals(
                                                        "(Ljava/lang/String;)Ljava/util/List;"))
                        .count());
        var wrapper =
                read("dev/starfold/mixin/CoreShaderProgramMixin").methods.stream()
                        .filter(m -> m.name.equals("starfold$capSource"))
                        .findFirst()
                        .orElseThrow();
        assertTrue((wrapper.access & Opcodes.ACC_STATIC) != 0);
        assertEquals(
                "(Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;Ljava/lang/String;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)Ljava/util/List;",
                wrapper.desc);
    }

    @Test
    void renderingScopeClosesBeforeHandAndOnExceptions() throws Exception {
        var upstream = calls("net/minecraft/client/renderer/GameRenderer", "renderLevel");
        var render =
                upstream.stream()
                        .filter(
                                c ->
                                        c.owner.equals(
                                                        "net/minecraft/client/renderer/LevelRenderer")
                                                && c.name.equals("renderLevel"))
                        .toList();
        assertEquals(1, render.size());
        var wrapper =
                read("dev/starfold/mixin/CoreShaderRenderMixin").methods.stream()
                        .filter(m -> m.name.equals("starfold$capFrame"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "(Lnet/minecraft/client/renderer/LevelRenderer;"
                        + render.getFirst().desc.substring(1, render.getFirst().desc.length() - 2)
                        + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V",
                wrapper.desc);
        assertFalse(wrapper.tryCatchBlocks.isEmpty());
        var calls = calls("dev/starfold/mixin/CoreShaderRenderMixin", wrapper.name);
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("begin")));
        assertTrue(calls.stream().filter(c -> c.name.equals("end")).count() >= 2);
        assertTrue(
                upstream.indexOf(render.getFirst())
                        < upstream.indexOf(
                                upstream.stream()
                                        .filter(c -> c.name.equals("renderItemInHand"))
                                        .findFirst()
                                        .orElseThrow()));
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null)
            for (int i = 0; i < annotation.values.size(); i += 2)
                if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }

    private static void collectInvokeTargets(Object node, List<String> targets) {
        if (node instanceof AnnotationNode a) {
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/At;")
                    && "INVOKE".equals(value(a, "value"))) targets.add((String) value(a, "target"));
            if (a.values != null)
                for (int i = 1; i < a.values.size(); i += 2)
                    collectInvokeTargets(a.values.get(i), targets);
        } else if (node instanceof List<?> list)
            for (var entry : list) collectInvokeTargets(entry, targets);
    }

    @Test
    void everyConfiguredInvokeAnnotationHasMethodNotFieldSyntax() throws Exception {
        try (var in = getClass().getResourceAsStream("/starfold.mixins.json")) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    new String(
                                            in.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            int checked = 0;
            for (String group : List.of("mixins", "client"))
                for (var entry : json.getAsJsonArray(group)) {
                    var type = read("dev/starfold/mixin/" + entry.getAsString());
                    var targets = new ArrayList<String>();
                    for (var m : type.methods) {
                        collectInvokeTargets(m.visibleAnnotations, targets);
                        collectInvokeTargets(m.invisibleAnnotations, targets);
                    }
                    for (String target : targets) {
                        assertNotNull(target, type.name);
                        assertTrue(
                                target.matches("L[^;]+;[^:;(]+\\(.*\\).+"),
                                type.name + ": " + target);
                        checked++;
                    }
                }
            assertTrue(
                    checked > 10,
                    "Must inspect actual compiled annotation values, not just method existence");
        }
    }
}
