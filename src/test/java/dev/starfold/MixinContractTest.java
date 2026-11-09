package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Inspect COMPILED annotation selectors against real upstream bytecode. No Minecraft startup. */
class MixinContractTest {
    private static java.io.InputStream resource(String path) throws Exception {
        if (path.startsWith("dev/starfold/mixin/")) {
            String name = path.substring(path.lastIndexOf('/') + 1).replace(".class", "");
            for (var config : CONFIGS.entrySet()) {
                try (var in = resource(config.getKey(), config.getValue())) {
                    var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
                    for (String side : List.of("mixins", "client")) {
                        if (!json.has(side)) continue;
                        for (var entry : json.getAsJsonArray(side)) {
                            if (name.equals(entry.getAsString()))
                                return resource(json.get("package").getAsString().replace('.', '/') + "/" + name + ".class", config.getValue());
                        }
                    }
                }
            }
            throw new IllegalArgumentException("Mixin not declared in a configuration: " + name);
        }
        return resource(path, CONFIGS.getOrDefault(path,
                path.startsWith("net/minecraft/") ? "starfold.auditUpstream"
                : path.startsWith("dev/ryanhcode/sable/") ? "starfold.auditSable"
                : path.startsWith("foundry/veil/") ? "starfold.auditVeil" : "starfold.auditJar"));
    }

    private static final Map<String, String> CONFIGS = Map.of(
            "starfold.mixins.json", "starfold.auditJar",
            "starfold_probes.mixins.json", "starfold.auditProbes",
            "starfold_worlds.mixins.json", "starfold.auditPack");

    private static java.io.InputStream resource(String path, String archiveProperty) throws Exception {
        String archive = System.getProperty(archiveProperty);
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
        var result = new ArrayList<String>();
        for (String config : List.of("starfold.mixins.json", "starfold_probes.mixins.json", "starfold_worlds.mixins.json")) {
        try (var in = resource(config)) {
            var json =
                    com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                            .getAsJsonObject();
            result.addAll(java.util.stream.Stream.concat(
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
                                            || s.equals("ChunkPerfSaveMixin")
                                            || s.equals("HullBottomCacheMixin")
                                            || s.equals("SableShipWriteMixin")
                                            || s.equals("SableShipStorageMixin")
                                            || s.equals("SableShipSerializationMixin")
                                            || s.equals("SableShipSplitMixin")
                                            || s.equals("SableFuelMassMixin")
                                            || s.equals("SableShipReadinessMixin")
                                            || s.startsWith("Mesh")
                                            || s.equals("ThrusterOcclusionMixin")
                                            || s.equals("WaterAppearanceMixin")
                                            || s.startsWith("Ground")
                                            || s.startsWith("Planet")
                                            || s.equals("CoreShaderShaderMixin")
                                            || s.equals("CoreShaderVeilProcessorMixin"))
                    .toList());
        }
        }
        return result;
    }

    @Test
    void waterAppearanceLocalsAreBeforeTheFirstShadeInsideTheVisibleBranch() throws Exception {
        var node = read("net/minecraft/client/renderer/block/LiquidBlockRenderer");
        var method = selected(node, "tesselate").getFirst();
        var shade = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(i -> i instanceof MethodInsnNode m && m.name.equals("getShade")).findFirst().orElseThrow();
        int at = method.instructions.indexOf(shade);
        var locals = method.localVariables.stream().filter(v -> method.instructions.indexOf(v.start) <= at
                && method.instructions.indexOf(v.end) > at).toList();
        assertEquals(List.of("alpha", "f", "f1", "f2"), locals.stream().filter(v -> v.desc.equals("F")).map(v -> v.name).toList());
        assertEquals(1, locals.stream().filter(v -> v.desc.equals("[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;")).count());
        assertEquals(9, java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .limit(at).filter(i -> i instanceof JumpInsnNode j && (j.getOpcode() == Opcodes.IFNE || j.getOpcode() == Opcodes.IFEQ)).count());
    }

    private static ClassNode target(ClassNode node) throws Exception {
        return targets(node).getFirst();
    }

    private static List<ClassNode> targets(ClassNode node) throws Exception {
        var a =
                annotations(node.visibleAnnotations, node.invisibleAnnotations).stream()
                        .filter(v -> v.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                        .findFirst()
                        .orElseThrow();
        var types = (List<?>) value(a, "value");
        var names =
                types != null
                        ? types.stream().map(t -> ((Type) t).getInternalName()).toList()
                        : strings(value(a, "targets")).stream()
                                .map(t -> t.replace('.', '/'))
                                .toList();
        var result = new ArrayList<ClassNode>();
        for (String name : names) result.add(read(name));
        return result;
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

    /** A type in the mixin package that the configuration does not list cannot be loaded at all. */
    @Test
    void everyTypeInTheMixinPackageIsAListedMixin() throws Exception {
        for (String module : List.of("", "probes/", "worlds/")) {
        Set<String> listed;
        try (var in = resource(module.isEmpty() ? "starfold.mixins.json" : "starfold_" + module.substring(0, module.length() - 1) + ".mixins.json")) {
            var json =
                    com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                            .getAsJsonObject();
            listed = new TreeSet<>();
            for (var key : List.of("mixins", "client", "server"))
                if (json.has(key))
                    json.getAsJsonArray(key).forEach(e -> listed.add(e.getAsString()));
        }
        var files = new TreeSet<String>();
        try (var paths =
                java.nio.file.Files.list(
                        java.nio.file.Path.of(module + "src/main/java/dev/starfold/" + (module.isEmpty() ? "mixin" : module + "mixin")))) {
            paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .forEach(name -> files.add(name.substring(0, name.length() - 5)));
        }
        assertEquals(listed, files);
        }
    }

    @Test
    void everySelectedInjectionSelectsRealMethodsAndEveryInvokeMatchesExactly() throws Exception {
        int injectors = 0, invokes = 0;
        for (String name : names()) {
            var mixin = read("dev/starfold/mixin/" + name);
            for (var target : targets(mixin))
                for (var method : mixin.methods)
                    for (var a :
                            annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                        var selectors = strings(value(a, "method"));
                        for (String selector : selectors) {
                            var methods = selected(target, selector);
                            assertFalse(
                                    methods.isEmpty(),
                                    name + " -> " + target.name + "." + selector);
                            injectors++;
                            for (var upstream : methods) {
                                var args = Type.getArgumentTypes(method.desc);
                                var original = Type.getArgumentTypes(upstream.desc);
                                if (a.desc.endsWith("/Inject;")) {
                                    assertEquals(
                                            original.length + (name.equals("ThrusterOcclusionMixin") ? 2 : name.equals("WaterAppearanceMixin") ? 6 : 1),
                                            args.length,
                                            name + " callback args " + selector);
                                    for (int i = 0; i < original.length; i++) {
                                        boolean coerced = method.invisibleParameterAnnotations != null
                                                && method.invisibleParameterAnnotations[i] != null
                                                && method.invisibleParameterAnnotations[i].stream().anyMatch(p -> p.desc.endsWith("/Coerce;"));
                                        if (coerced) {
                                            assertEquals(Type.OBJECT, original[i].getSort());
                                            assertEquals(Type.getType(Object.class), args[i]);
                                        } else assertEquals(original[i], args[i], name + " callback argument " + i);
                                    }
                                    assertEquals(Type.VOID_TYPE, Type.getReturnType(method.desc));
                                    assertEquals(
                                            Type.getReturnType(upstream.desc).equals(Type.VOID_TYPE)
                                                    ? "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"
                                                    : "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable",
                                            args[original.length].getInternalName(),
                                            name + " callback type");
                                }
                                if (name.equals("ThrusterOcclusionMixin") && a.desc.endsWith("/Inject;")) {
                                    var local = args[Type.getArgumentTypes(upstream.desc).length + 1];
                                    assertEquals(Type.getObjectType("net/minecraft/world/level/block/state/BlockState"), local);
                                    var call = java.util.stream.StreamSupport.stream(upstream.instructions.spliterator(), false)
                                            .filter(i -> i instanceof MethodInsnNode m && m.name.equals("skipRendering")).findFirst().orElseThrow();
                                    int at = upstream.instructions.indexOf(call);
                                    assertEquals(1, upstream.localVariables.stream().filter(v -> v.index == Type.getArgumentTypes(upstream.desc).length
                                            && v.desc.equals(local.getDescriptor())
                                            && upstream.instructions.indexOf(v.start) <= at && upstream.instructions.indexOf(v.end) > at).count());
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
                            for (var position : at) {
                                if ("FIELD".equals(value(position, "value"))
                                        && name.equals("GroundPositionDiagnosticsMixin")) {
                                    var expected = (String) value(position, "target");
                                    var opcode = (Integer) value(position, "opcode");
                                    assertEquals(Opcodes.PUTFIELD, opcode);
                                    for (var upstream : methods) {
                                        var writes = java.util.stream.StreamSupport.stream(
                                                        upstream.instructions.spliterator(), false)
                                                .filter(i -> i instanceof FieldInsnNode f
                                                        && f.getOpcode() == opcode
                                                        && expected.equals("L" + f.owner + ";" + f.name + ":" + f.desc))
                                                .map(i -> (FieldInsnNode) i).toList();
                                        assertEquals(1, writes.size(), name + " exact field write " + selector);
                                        var args = Type.getArgumentTypes(method.desc);
                                        assertEquals(3, args.length);
                                        assertEquals(Type.getObjectType(writes.getFirst().owner), args[0]);
                                        assertEquals(Type.getType(writes.getFirst().desc), args[1]);
                                        assertEquals("com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                                args[2].getInternalName());
                                        assertEquals(Type.VOID_TYPE, Type.getReturnType(method.desc));
                                    }
                                }
                                if ("INVOKE".equals(value(position, "value"))) {
                                    var expected = (String) value(position, "target");
                                    assertNotNull(expected);
                                    boolean found = false, staticInvoke = false;
                                    for (var upstream : methods)
                                        for (var instruction : upstream.instructions)
                                            if (instruction instanceof MethodInsnNode call
                                                    && expected.equals(
                                                            "L"
                                                                    + call.owner
                                                                    + ";"
                                                                    + call.name
                                                                    + call.desc)) {
                                                found = true;
                                                staticInvoke =
                                                        call.getOpcode() == Opcodes.INVOKESTATIC;
                                            }
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
                                        int receiver = staticInvoke ? 0 : 1;
                                        if (!staticInvoke)
                                            assertEquals(
                                                    Type.getObjectType(
                                                            expected.substring(
                                                                    1, expected.indexOf(';'))),
                                                    args[0]);
                                        var invoked = Type.getArgumentTypes(descriptor);
                                        for (int i = 0; i < invoked.length; i++)
                                            assertEquals(invoked[i], args[i + receiver]);
                                        int operation = invoked.length + receiver;
                                        assertEquals(
                                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                                args[operation].getInternalName());
                                        assertEquals(
                                                Type.getReturnType(descriptor),
                                                Type.getReturnType(method.desc));
                                        boolean capturedLocal = method.invisibleParameterAnnotations != null
                                                && Arrays.stream(method.invisibleParameterAnnotations)
                                                        .filter(Objects::nonNull)
                                                        .flatMap(Collection::stream)
                                                        .anyMatch(n -> n.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;"));
                                        if (capturedLocal && name.equals("MeshDispatcherProbeMixin")) {
                                            for (var upstream : methods)
                                                assertEquals(1, Arrays.stream(Type.getArgumentTypes(upstream.desc))
                                                        .filter(t -> t.equals(args[operation + 1])).count(), "unique upload data argument");
                                        } else if (capturedLocal) {
                                            assertTrue(Set.of("GroundNativeDrawMixin", "MeshLevelProbeMixin").contains(name));
                                            assertEquals("net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection",
                                                    args[operation + 1].getInternalName());
                                            for (var upstream : methods) {
                                                var draws = java.util.stream.StreamSupport.stream(upstream.instructions.spliterator(), false)
                                                        .filter(i -> i instanceof MethodInsnNode call && expected.equals("L" + call.owner + ";" + call.name + call.desc))
                                                        .toList();
                                                assertEquals(1, draws.size());
                                                int draw = upstream.instructions.indexOf(draws.getFirst());
                                                assertEquals(1, upstream.localVariables.stream()
                                                        .filter(v -> v.desc.equals(args[operation + 1].getDescriptor())
                                                                && upstream.instructions.indexOf(v.start) <= draw
                                                                && upstream.instructions.indexOf(v.end) > draw)
                                                        .count(), "unique live section at the real draw call");
                                            }
                                        }
                                        if (!capturedLocal && args.length > operation + 1)
                                            for (var upstream : methods) {
                                                var enclosing =
                                                        Type.getArgumentTypes(upstream.desc);
                                                assertEquals(
                                                        enclosing.length + operation + 1,
                                                        args.length);
                                                for (int i = 0; i < enclosing.length; i++)
                                                    assertEquals(
                                                            enclosing[i], args[i + operation + 1]);
                                            }
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
        verifyPlanetSoundCallChain();
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
        verifyMultiTargetShadowRuntimeRule();
    }

    /** Execute the framework's real remap predicate without bootstrapping a Mixin transformer. */
    private static java.lang.reflect.Method runtimeShadowRule() throws Exception {
        String path = "/org/spongepowered/asm/mixin/transformer/MixinInfo$State.class";
        var state = new ClassNode();
        try (var in = org.spongepowered.asm.mixin.Shadow.class.getResourceAsStream(path)) {
            assertNotNull(in, "Actual runtime Mixin validation bytecode");
            new ClassReader(in).accept(state, ClassReader.SKIP_FRAMES);
        }
        System.out.println(
                "Mixin shadow rule source: "
                        + org.spongepowered.asm.mixin.Shadow.class.getResource(path));
        var guard = selected(state, "validateRemappables").getFirst();
        var code =
                java.util.stream.StreamSupport.stream(guard.instructions.spliterator(), false)
                        .filter(i -> i.getOpcode() >= 0)
                        .toList();
        assertTrue(
                code.get(1) instanceof MethodInsnNode call
                        && call.owner.equals("java/util/List")
                        && call.name.equals("size"));
        assertEquals(Opcodes.ICONST_1, code.get(2).getOpcode());
        assertEquals(Opcodes.IF_ICMPLE, code.get(3).getOpcode());
        var exit = ((JumpInsnNode) code.get(3)).label;
        var afterExit = exit.getNext();
        while (afterExit.getOpcode() < 0) afterExit = afterExit.getNext();
        assertEquals(Opcodes.RETURN, afterExit.getOpcode());
        assertEquals(
                2,
                invocations(guard).stream()
                        .filter(c -> c.name.equals("validateRemappable"))
                        .count());

        var rule = selected(state, "validateRemappable").getFirst();
        rule.access = Opcodes.ACC_PUBLIC;
        rule.signature = null;
        // Only the diagnostic's owning MixinInfo is unavailable in this isolated invocation.
        // Keep the original predicate, default value and InvalidMixinException construction intact.
        int diagnostics = 0;
        for (var instruction : rule.instructions.toArray())
            if (instruction instanceof FieldInsnNode field && field.name.equals("this$0")) {
                assertEquals(Opcodes.GETFIELD, field.getOpcode());
                var replacement = new InsnList();
                replacement.add(new InsnNode(Opcodes.POP));
                replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                rule.instructions.insertBefore(field, replacement);
                rule.instructions.remove(field);
                diagnostics++;
            }
        assertEquals(1, diagnostics);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = "dev/starfold/ExtractedMixinShadowRule";
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        rule.accept(writer);
        writer.visitEnd();
        var extracted =
                new ClassLoader(MixinContractTest.class.getClassLoader()) {
                    Class<?> define(byte[] bytes) {
                        return defineClass(null, bytes, 0, bytes.length);
                    }
                }.define(writer.toByteArray());
        return extracted.getMethod(
                "validateRemappable", Class.class, String.class, AnnotationNode.class);
    }

    private static void verifyMultiTargetShadowRuntimeRule() throws Exception {
        var rule = runtimeShadowRule();
        var validator = rule.getDeclaringClass().getConstructor().newInstance();
        // Negative controls reproduce the original default-remap bug as well as explicit true.
        for (var remap : Arrays.asList(null, Boolean.TRUE, Boolean.FALSE)) {
            var annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;");
            if (remap != null) annotation.values = new ArrayList<>(List.of("remap", remap));
            if (Boolean.FALSE.equals(remap))
                rule.invoke(
                        validator, org.spongepowered.asm.mixin.Shadow.class, "fixture", annotation);
            else {
                var error =
                        assertThrows(
                                java.lang.reflect.InvocationTargetException.class,
                                () ->
                                        rule.invoke(
                                                validator,
                                                org.spongepowered.asm.mixin.Shadow.class,
                                                "fixture",
                                                annotation));
                assertInstanceOf(
                        org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException
                                .class,
                        error.getCause());
                assertTrue(error.getCause().getMessage().contains("remappable @Shadow"));
            }
        }
        int checked = 0;
        for (String name : names()) {
            var mixin = read("dev/starfold/mixin/" + name);
            if (targets(mixin).size() <= 1) continue;
            var shadows = new ArrayList<AnnotationNode>();
            for (var field : mixin.fields)
                shadows.addAll(annotations(field.visibleAnnotations, field.invisibleAnnotations));
            for (var method : mixin.methods)
                shadows.addAll(annotations(method.visibleAnnotations, method.invisibleAnnotations));
            for (var annotation : shadows)
                if (annotation.desc.endsWith("/Shadow;")) {
                    assertDoesNotThrow(
                            () ->
                                    rule.invoke(
                                            validator,
                                            org.spongepowered.asm.mixin.Shadow.class,
                                            name,
                                            annotation),
                            name);
                    checked++;
                }
        }
        assertTrue(checked >= 1, "Exercise the multi-target player sound shadow");
    }

    private static List<MethodInsnNode> invocations(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }

    private static void verifyPlanetSoundCallChain() throws Exception {
        var wrapper =
                selected(read("dev/starfold/mixin/PlanetSoundMixin"), "starfold$muffle").getFirst();
        assertEquals(1, invocations(wrapper).stream().filter(c -> c.name.equals("scale")).count());
        assertEquals(1, invocations(wrapper).stream().filter(c -> c.name.equals("call")).count());
        assertFalse(invocations(wrapper).stream().anyMatch(c -> c.name.equals("clamp")));
        var multiply =
                java.util.stream.StreamSupport.stream(wrapper.instructions.spliterator(), false)
                        .filter(i -> i.getOpcode() == Opcodes.FMUL)
                        .toList();
        assertEquals(1, multiply.size());
        var operation =
                invocations(wrapper).stream()
                        .filter(c -> c.name.equals("call"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                wrapper.instructions.indexOf(multiply.getFirst())
                        < wrapper.instructions.indexOf(operation));
        // Probe observes the actual original result, after the single production multiply/call.
        var tap =
                invocations(wrapper).stream()
                        .filter(c -> c.name.equals("calculated"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(wrapper.instructions.indexOf(tap) > wrapper.instructions.indexOf(operation));
        assertTrue(invocations(wrapper).stream().anyMatch(c -> c.name.equals("enabled")));
        assertEquals(
                Opcodes.FRETURN,
                java.util.stream.StreamSupport.stream(wrapper.instructions.spliterator(), false)
                        .filter(i -> i.getOpcode() >= 0)
                        .toList()
                        .getLast()
                        .getOpcode());
        var mixinSound = read("dev/starfold/mixin/PlanetSoundMixin");
        for (String name : List.of("starfold$initialVolume", "starfold$instanceVolume")) {
            var method = selected(mixinSound, name).getFirst();
            assertEquals(
                    1,
                    invocations(method).stream()
                            .filter(c -> c.name.equals("starfold$muffle"))
                            .count());
        }
        var production = read("dev/starfold/planet/PlanetSoundClient");
        var productionScale =
                selected(
                                production,
                                "scale(Lnet/minecraft/client/resources/sounds/SoundInstance;)F")
                        .getFirst();
        var scaling =
                invocations(productionScale).stream()
                        .filter(c -> c.name.equals("scale"))
                        .findFirst()
                        .orElseThrow();
        var observed =
                invocations(productionScale).stream()
                        .filter(c -> c.name.equals("scaled"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                productionScale.instructions.indexOf(scaling)
                        < productionScale.instructions.indexOf(observed));
        assertTrue(invocations(productionScale).stream().anyMatch(c -> c.name.equals("enabled")));
        assertTrue(
                invocations(selected(production, "created").getFirst()).stream()
                        .anyMatch(
                                c ->
                                        c.owner.equals("dev/starfold/planet/PlanetSoundClient$Observer")
                                                && c.name.equals("created")));
        for (String name : List.of("AbstractSoundInstance", "SimpleSoundInstance"))
            assertTrue(
                    selected(read("net/minecraft/client/resources/sounds/" + name), "equals")
                            .isEmpty(),
                    name);

        var engine = read("net/minecraft/client/sounds/SoundEngine");
        String instance = "(Lnet/minecraft/client/resources/sounds/SoundInstance;)F";
        String scalar = "(FLnet/minecraft/sounds/SoundSource;)F";
        for (String selector : List.of("play", "calculateVolume" + instance)) {
            var method = selected(engine, selector).getFirst();
            assertEquals(
                    1,
                    invocations(method).stream()
                            .filter(c -> c.name.equals("calculateVolume") && c.desc.equals(scalar))
                            .count());
        }
        var clamp = selected(engine, "calculateVolume" + scalar).getFirst();
        assertTrue(
                invocations(clamp).stream()
                        .anyMatch(c -> c.name.equals("clamp") && c.desc.equals("(FFF)F")));
        assertTrue(
                engine.methods.stream()
                        .filter(m -> m.name.equals("tickNonPaused"))
                        .flatMap(m -> invocations(m).stream())
                        .anyMatch(
                                c -> c.name.equals("calculateVolume") && c.desc.equals(instance)));
        assertTrue(
                engine.methods.stream()
                        .filter(m -> m.name.startsWith("lambda$updateCategoryVolume$"))
                        .flatMap(m -> invocations(m).stream())
                        .anyMatch(
                                c -> c.name.equals("calculateVolume") && c.desc.equals(instance)));
        var manager = read("net/minecraft/client/sounds/SoundManager");
        assertTrue(
                invocations(selected(manager, "updateSourceVolume").getFirst()).stream()
                        .anyMatch(c -> c.name.equals("updateCategoryVolume")));
        var refresh = selected(read("dev/starfold/planet/PlanetSoundClient"), "tick").getFirst();
        assertTrue(
                invocations(refresh).stream().anyMatch(c -> c.name.equals("updateSourceVolume")));

        var local = read("net/minecraft/client/player/LocalPlayer");
        for (String action : List.of("playSound", "playNotifySound", "updateIsUnderwater"))
            assertTrue(
                    invocations(selected(local, action).getFirst()).stream()
                            .anyMatch(
                                    c ->
                                            c.name.equals("playLocalSound")
                                                    && c.desc.startsWith("(DDD")),
                    action);
        var entity = read("net/minecraft/world/entity/Entity");
        assertTrue(
                invocations(selected(entity, "playStepSound").getFirst()).stream()
                        .anyMatch(c -> c.name.equals("playSound")));
        for (String name : List.of("PlanetEntitySoundMixin", "PlanetPlayerSoundMixin")) {
            var mixin = read("dev/starfold/mixin/" + name);
            var field =
                    mixin.fields.stream()
                            .filter(f -> !f.name.startsWith("starfold$"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(invocations(selected(mixin, "starfold$soundOwner").getFirst()).isEmpty());
            for (var target : targets(mixin))
                assertTrue(
                        target.fields.stream()
                                .anyMatch(
                                        f ->
                                                f.name.equals(field.name)
                                                        && f.desc.equals(field.desc)),
                        target.name);
            assertTrue(
                    java.util.stream.StreamSupport.stream(
                                    selected(mixin, "starfold$soundOwner")
                                            .getFirst()
                                            .instructions
                                            .spliterator(),
                                    false)
                            .anyMatch(
                                    i ->
                                            i instanceof FieldInsnNode f
                                                    && f.getOpcode() == Opcodes.GETFIELD
                                                    && f.name.equals(field.name)));
        }

        // Constructor WrapOperation selectors are not INVOKEs: audit their allocation and signature
        // too.
        var factory = read("dev/starfold/mixin/PlanetLevelSoundMixin");
        for (var method : factory.methods)
            for (var annotation :
                    annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                var positions = new ArrayList<AnnotationNode>();
                ats(annotation, positions);
                for (var position : positions)
                    if ("NEW".equals(value(position, "value"))) {
                        String descriptor = (String) value(position, "target");
                        Type type = Type.getReturnType(descriptor);
                        String constructor =
                                descriptor.substring(0, descriptor.indexOf(')') + 1) + "V";
                        var upstream =
                                selected(
                                                read(
                                                        "net/minecraft/client/multiplayer/ClientLevel"),
                                                "playSound")
                                        .getFirst();
                        assertEquals(
                                1,
                                invocations(upstream).stream()
                                        .filter(
                                                c ->
                                                        c.owner.equals(type.getInternalName())
                                                                && c.name.equals("<init>")
                                                                && c.desc.equals(constructor))
                                        .count());
                        var args = Type.getArgumentTypes(method.desc);
                        var expected = Type.getArgumentTypes(descriptor);
                        assertEquals(expected.length + 1, args.length);
                        for (int i = 0; i < expected.length; i++)
                            assertEquals(expected[i], args[i]);
                        assertEquals(
                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                args[args.length - 1].getInternalName());
                        assertEquals(type, Type.getReturnType(method.desc));
                    }
            }
    }

    @Test
    void planetGravityCoversVanillaFallingEntitiesAndSimpleProjectiles() throws Exception {
        var entity = read("net/minecraft/world/entity/Entity");
        var gravity = selected(entity, "getGravity()D").getFirst();
        assertNotEquals(0, gravity.access & Opcodes.ACC_FINAL);
        assertTrue(
                java.util.stream.StreamSupport.stream(gravity.instructions.spliterator(), false)
                        .anyMatch(
                                i ->
                                        i instanceof MethodInsnNode call
                                                && call.name.equals("getDefaultGravity")));
        for (var name :
                List.of(
                        "item/ItemEntity",
                        "item/FallingBlockEntity",
                        "item/PrimedTnt",
                        "ExperienceOrb",
                        "projectile/AbstractArrow",
                        "projectile/ThrowableProjectile",
                        "projectile/LlamaSpit")) {
            var type = read("net/minecraft/world/entity/" + name);
            assertEquals(1, selected(type, "getDefaultGravity()D").size(), name);
            assertTrue(
                    type.methods.stream()
                            .anyMatch(
                                    m ->
                                            java.util.stream.StreamSupport.stream(
                                                            m.instructions.spliterator(), false)
                                                    .anyMatch(
                                                            i ->
                                                                    i instanceof MethodInsnNode call
                                                                            && (call.name.equals(
                                                                                            "getGravity")
                                                                                    || call.name
                                                                                            .equals(
                                                                                                    "applyGravity")))),
                    name);
        }
    }

    private static List<MethodInsnNode> calls(String clazz) throws Exception {
        var out = new ArrayList<MethodInsnNode>();
        for (var method : read("dev/starfold/" + clazz).methods)
            for (var i : method.instructions) if (i instanceof MethodInsnNode c) out.add(c);
        return out;
    }
}
