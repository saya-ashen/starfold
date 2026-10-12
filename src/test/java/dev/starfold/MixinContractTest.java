package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Inspect COMPILED annotation selectors against real upstream bytecode. No Minecraft startup. */
class MixinContractTest {
    private static java.io.InputStream resource(String path) throws Exception {
        String archive =
                System.getProperty(
                        path.startsWith("net/minecraft/")
                                ? "starfold.auditUpstream"
                                : path.startsWith("foundry/veil/")
                                        ? "starfold.auditVeil"
                                        : "starfold.auditJar");
        if (archive == null) return TestArchiveResources.open("/" + path);
        try (var zip = new java.util.zip.ZipFile(archive)) {
            var entry = zip.getEntry(path);
            assertNotNull(entry, archive + ": " + path);
            try (var in = zip.getInputStream(entry)) {
                return new java.io.ByteArrayInputStream(in.readAllBytes());
            }
        }
    }

    private static ClassNode read(String path) throws Exception {
        try (var in = resource(path + ".class")) {
            assertNotNull(in, path);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static Object value(AnnotationNode a, String name) {
        if (a.values != null)
            for (int i = 0; i < a.values.size(); i += 2)
                if (name.equals(a.values.get(i))) return a.values.get(i + 1);
        return null;
    }

    private static List<AnnotationNode> annotations(
            List<AnnotationNode> a, List<AnnotationNode> b) {
        var out = new ArrayList<AnnotationNode>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : o == null ? List.of() : List.of(o.toString());
    }

    private static List<String> names() throws Exception {
        try (var in = resource("starfold.mixins.json")) {
            var json =
                    com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                            .getAsJsonObject();
            return java.util.stream.Stream.concat(
                            json.getAsJsonArray("mixins").asList().stream(),
                            json.getAsJsonArray("client").asList().stream())
                    .map(e -> e.getAsString())
                    .filter(
                            s ->
                                    Set.of(
                                                            "SpriteAccess",
                                                            "SavedDataFileAccess",
                                                            "BlockEntityAccess",
                                                            "BlockEntityPacketAccess",
                                                            "ChunkPacketAccess",
                                                            "TeleportPacketAccess",
                                                            "TrackedAccess")
                                                    .contains(s)
                                            || s.startsWith("Ground")
                                            || s.equals("CoreShaderShaderMixin")
                                            || s.equals("CoreShaderVeilProcessorMixin"))
                    .toList();
        }
    }

    private static ClassNode target(ClassNode node) throws Exception {
        var a =
                annotations(node.visibleAnnotations, node.invisibleAnnotations).stream()
                        .filter(v -> v.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                        .findFirst()
                        .orElseThrow();
        var types = (List<?>) value(a, "value");
        String name =
                types != null
                        ? ((Type) types.getFirst()).getInternalName()
                        : strings(value(a, "targets")).getFirst().replace('.', '/');
        return read(name);
    }

    private static List<MethodNode> selected(ClassNode target, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        return target.methods.stream()
                .filter(
                        m ->
                                m.name.equals(name)
                                        && (paren < 0 || m.desc.equals(selector.substring(paren))))
                .toList();
    }

    private static void ats(Object node, List<AnnotationNode> out) {
        if (node instanceof AnnotationNode a) {
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/At;")) out.add(a);
            if (a.values != null)
                for (int i = 1; i < a.values.size(); i += 2) ats(a.values.get(i), out);
        } else if (node instanceof List<?> list) for (var v : list) ats(v, out);
    }

    @Test
    void everySelectedInjectionSelectsRealMethodsAndEveryInvokeMatchesExactly() throws Exception {
        int injectors = 0, invokes = 0;
        for (String name : names()) {
            var mixin = read("dev/starfold/mixin/" + name);
            var target = target(mixin);
            for (var method : mixin.methods)
                for (var a : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    var selectors = strings(value(a, "method"));
                    for (String selector : selectors) {
                        var methods = selected(target, selector);
                        assertFalse(
                                methods.isEmpty(), name + " -> " + target.name + "." + selector);
                        injectors++;
                        for (var upstream : methods) {
                            var args = Type.getArgumentTypes(method.desc);
                            var original = Type.getArgumentTypes(upstream.desc);
                            if (a.desc.endsWith("/Inject;")) {
                                assertEquals(
                                        original.length + 1,
                                        args.length,
                                        name + " callback args " + selector);
                                for (int i = 0; i < original.length; i++)
                                    assertEquals(
                                            original[i], args[i], name + " callback argument " + i);
                                assertEquals(Type.VOID_TYPE, Type.getReturnType(method.desc));
                                assertEquals(
                                        Type.getReturnType(upstream.desc).equals(Type.VOID_TYPE)
                                                ? "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"
                                                : "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable",
                                        args[args.length - 1].getInternalName(),
                                        name + " callback type");
                            }
                            if (a.desc.endsWith("/WrapMethod;")) {
                                assertEquals(
                                        original.length + 1,
                                        args.length,
                                        name + " wrapper arguments");
                                for (int i = 0; i < original.length; i++)
                                    assertEquals(original[i], args[i]);
                                assertEquals(
                                        Type.getReturnType(upstream.desc),
                                        Type.getReturnType(method.desc));
                            }
                        }
                        var at = new ArrayList<AnnotationNode>();
                        ats(a, at);
                        for (var position : at)
                            if ("INVOKE".equals(value(position, "value"))) {
                                var expected = (String) value(position, "target");
                                assertNotNull(expected);
                                boolean found = false;
                                for (var upstream : methods)
                                    for (var instruction : upstream.instructions)
                                        if (instruction instanceof MethodInsnNode call
                                                && expected.equals(
                                                        "L"
                                                                + call.owner
                                                                + ";"
                                                                + call.name
                                                                + call.desc)) found = true;
                                assertTrue(found, name + ": " + selector + " -> " + expected);
                                invokes++;
                                String descriptor = expected.substring(expected.indexOf('('));
                                if (a.desc.endsWith("/ModifyExpressionValue;")) {
                                    assertEquals(
                                            Type.getReturnType(descriptor),
                                            Type.getArgumentTypes(method.desc)[0]);
                                    assertEquals(
                                            Type.getReturnType(descriptor),
                                            Type.getReturnType(method.desc));
                                }
                                if (a.desc.endsWith("/WrapOperation;")) {
                                    var args = Type.getArgumentTypes(method.desc);
                                    assertEquals(
                                            Type.getObjectType(
                                                    expected.substring(1, expected.indexOf(';'))),
                                            args[0]);
                                    var invoked = Type.getArgumentTypes(descriptor);
                                    for (int i = 0; i < invoked.length; i++)
                                        assertEquals(invoked[i], args[i + 1]);
                                    int operation = invoked.length + 1;
                                    assertEquals(
                                            "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                            args[operation].getInternalName());
                                    assertEquals(
                                            Type.getReturnType(descriptor),
                                            Type.getReturnType(method.desc));
                                    if (args.length > operation + 1)
                                        for (var upstream : methods) {
                                            var enclosing = Type.getArgumentTypes(upstream.desc);
                                            assertEquals(
                                                    enclosing.length + operation + 1, args.length);
                                            for (int i = 0; i < enclosing.length; i++)
                                                assertEquals(enclosing[i], args[i + operation + 1]);
                                        }
                                }
                            }
                        if (a.desc.endsWith("/ModifyVariable;")) {
                            var type = Type.getArgumentTypes(method.desc)[0];
                            var ordinal = (Integer) value(a, "ordinal");
                            for (var upstream : methods) {
                                long count =
                                        Arrays.stream(Type.getArgumentTypes(upstream.desc))
                                                .filter(type::equals)
                                                .count();
                                assertTrue(
                                        count > (ordinal == null ? 0 : ordinal),
                                        name + " variable type/ordinal " + selector);
                            }
                        }
                    }
                }
        }
        assertTrue(injectors >= 30);
        assertTrue(invokes >= 2);
    }

    @Test
    void shadowFieldsAndPacketAccessorsMatchUpstreamDescriptors() throws Exception {
        int checked = 0;
        for (String name : names()) {
            var mixin = read("dev/starfold/mixin/" + name);
            var target = target(mixin);
            for (var field : mixin.fields)
                if (annotations(field.visibleAnnotations, field.invisibleAnnotations).stream()
                        .anyMatch(a -> a.desc.endsWith("/Shadow;"))) {
                    assertTrue(
                            target.fields.stream()
                                    .anyMatch(
                                            f ->
                                                    f.name.equals(field.name)
                                                            && f.desc.equals(field.desc)),
                            name + " shadow " + field.name);
                    checked++;
                }
            for (var method : mixin.methods)
                for (var a : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    if (a.desc.endsWith("/Accessor;")) {
                        var field =
                                target.fields.stream()
                                        .filter(f -> f.name.equals(value(a, "value")))
                                        .findFirst()
                                        .orElseThrow();
                        var args = Type.getArgumentTypes(method.desc);
                        var result = Type.getReturnType(method.desc);
                        assertTrue(args.length <= 1, name + " accessor arity");
                        if (args.length == 0)
                            assertEquals(field.desc, result.getDescriptor(), name + " getter");
                        else {
                            assertEquals("V", result.getDescriptor(), name + " setter return");
                            assertEquals(field.desc, args[0].getDescriptor(), name + " setter");
                        }
                        checked++;
                    }
                    if (a.desc.endsWith("/Invoker;")) {
                        String called = (String) value(a, "value");
                        String descriptor = method.desc;
                        if (called.equals("<init>"))
                            descriptor = descriptor.substring(0, descriptor.indexOf(')') + 1) + "V";
                        final String d = descriptor;
                        assertTrue(
                                target.methods.stream()
                                        .anyMatch(m -> m.name.equals(called) && m.desc.equals(d)),
                                name + " invoker " + called);
                        checked++;
                    }
                }
        }
        assertTrue(checked >= 10);
    }

    private static List<MethodInsnNode> calls(String clazz) throws Exception {
        var out = new ArrayList<MethodInsnNode>();
        for (var method : read("dev/starfold/" + clazz).methods)
            for (var i : method.instructions) if (i instanceof MethodInsnNode c) out.add(c);
        return out;
    }
}
