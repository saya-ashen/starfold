package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Inspect compiled annotation selectors against original dependency bytecode. No Minecraft startup.
 * The explicit inventory is exhaustive; "audited" means these structural contracts, not a complete
 * Mixin transformer simulation. Injection ordering, shifts/slices, cancellation behavior, remapping,
 * bridge/coercion assignability and interactions with other mods require runtime coverage. Local
 * capture supports unique typed locals; ThrusterOcclusion/WaterAppearance have dedicated contracts.
 * Other mutable local captures, local-variable modification, injector kinds and allocation/field
 * forms need an audit extension or an exact named exemption before they can be included.
 */
class MixinContractTest {
    private static java.io.InputStream resource(String path) throws Exception {
        if (path.startsWith("dev/starfold/mixin/")) {
            String name = path.substring(path.lastIndexOf('/') + 1).replace(".class", "");
            for (var config : CONFIGS.entrySet()) {
                try (var in = resource(config.getKey(), config.getValue())) {
                    var json =
                            com.google.gson.JsonParser.parseReader(
                                            new java.io.InputStreamReader(in))
                                    .getAsJsonObject();
                    for (String side : List.of("mixins", "client", "server")) {
                        if (!json.has(side)) continue;
                        for (var entry : json.getAsJsonArray(side)) {
                            if (name.equals(entry.getAsString()))
                                return resource(
                                        json.get("package").getAsString().replace('.', '/')
                                                + "/"
                                                + name
                                                + ".class",
                                        config.getValue());
                        }
                    }
                }
            }
            throw new IllegalArgumentException("Mixin not declared in a configuration: " + name);
        }
        return resource(
                path,
                CONFIGS.getOrDefault(
                        path,
                        (path.startsWith("net/minecraft/")
                                        || path.startsWith("net/neoforged/neoforge/")
                                        || path.startsWith("com/mojang/blaze3d/"))
                                ? "starfold.auditUpstream"
                                : path.startsWith("dev/ryanhcode/sable/")
                                        ? "starfold.auditSable"
                                        : path.startsWith("foundry/veil/")
                                                ? "starfold.auditVeil"
                                                : "starfold.auditJar"));
    }

    private static final Map<String, String> CONFIGS =
            Map.of(
                    "starfold.mixins.json", "starfold.auditJar",
                    "starfold.create.mixins.json", "starfold.auditJar",
                    "starfold.mekanism.mixins.json", "starfold.auditJar",
                    "starfold.jade.mixins.json", "starfold.auditJar",
                    "starfold.flywheel.mixins.json", "starfold.auditJar",
                    "starfold_probes.mixins.json", "starfold.auditProbes",
                    "starfold_worlds.mixins.json", "starfold.auditPack");

    private static java.io.InputStream resource(String path, String archiveProperty)
            throws Exception {
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

    private record RegisteredMixin(String className, String archiveProperty) {
        String simpleName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }

    private static ClassNode read(RegisteredMixin mixin) throws Exception {
        try (var in =
                resource(mixin.className().replace('.', '/') + ".class", mixin.archiveProperty())) {
            assertNotNull(in, mixin.className());
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    /**
     * Every registration needs an explicit decision, including optional/plugin-disabled mixins.
     * Keep this in the two packaged test entry points: a separate @Test would not be selected there.
     * The inventory is a TEST resource, never read from the production audit jar.
     */
    private static List<RegisteredMixin> names() throws Exception {
        com.google.gson.JsonObject inventory;
        try (var in =
                MixinContractTest.class.getResourceAsStream("/dev/starfold/mixin-audit.json")) {
            assertNotNull(in, "Explicit mixin audit inventory");
            inventory =
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(
                                            in, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
        }
        assertEquals(CONFIGS.keySet(), inventory.keySet(), "Audit configuration inventory");
        var result = new ArrayList<RegisteredMixin>();
        for (var config : CONFIGS.entrySet()) {
            var decision = inventory.getAsJsonObject(config.getKey());
            assertEquals(Set.of("audited", "exemptions"), decision.keySet(), config.getKey());
            var audited = new TreeSet<String>();
            for (var entry : decision.getAsJsonArray("audited"))
                assertTrue(audited.add(entry.getAsString()), "Duplicate audit decision: " + entry);
            var exemptions = decision.getAsJsonObject("exemptions");
            var decided = new TreeSet<>(audited);
            for (String exempt : exemptions.keySet())
                assertTrue(decided.add(exempt), "Both audited and exempt: " + exempt);
            var registered = new TreeSet<String>();
            try (var in = resource(config.getKey(), config.getValue())) {
                var manifest =
                        com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in))
                                .getAsJsonObject();
                for (String side : List.of("mixins", "client", "server")) {
                    if (!manifest.has(side)) continue;
                    for (var entry : manifest.getAsJsonArray(side)) {
                        String name =
                                manifest.get("package").getAsString() + "." + entry.getAsString();
                        assertTrue(registered.add(name), "Duplicate registration: " + name);
                    }
                }
            }
            var undecided = new TreeSet<>(registered);
            undecided.removeAll(decided);
            var stale = new TreeSet<>(decided);
            stale.removeAll(registered);
            assertTrue(
                    undecided.isEmpty() && stale.isEmpty(),
                    config.getKey()
                            + " needs explicit audit decisions; undecided="
                            + undecided
                            + "; stale="
                            + stale);
            for (String name : registered) {
                var registration = new RegisteredMixin(name, config.getValue());
                var targetNames = targetNames(read(registration));
                assertFalse(targetNames.isEmpty(), name + " has no @Mixin targets");
                if (audited.contains(name)) result.add(registration);
                else {
                    var exemption = exemptions.getAsJsonObject(name);
                    assertEquals(Set.of("targets", "reason", "coverage"), exemption.keySet(), name);
                    for (String key : List.of("reason", "coverage"))
                        assertFalse(
                                exemption.get(key).getAsString().isBlank(),
                                name + " missing " + key);
                    var expectedTargets =
                            exemption.getAsJsonArray("targets").asList().stream()
                                    .map(e -> e.getAsString().replace('.', '/'))
                                    .toList();
                    assertEquals(expectedTargets, targetNames, name + " exemption target drift");
                    System.out.println(
                            "Mixin audit exemption: "
                                    + name
                                    + " — "
                                    + exemption.get("reason").getAsString()
                                    + " Coverage: "
                                    + exemption.get("coverage").getAsString());
                }
            }
            System.out.println(
                    "Mixin audit "
                            + config.getKey()
                            + ": "
                            + registered.size()
                            + " registered = "
                            + audited.size()
                            + " generic + "
                            + exemptions.size()
                            + " exempt");
        }
        return result;
    }

    @Test
    void aquaticSpawnHeightHandlersCaptureTheExactUpstreamArguments() throws Exception {
        for (String name : List.of("WaterAnimalSpawnMixin", "TurtleSpawnMixin")) {
            var mixin = read("dev/starfold/mixin/" + name);
            var upstream = target(mixin);
            String selector =
                    name.equals("WaterAnimalSpawnMixin")
                            ? "checkSurfaceWaterAnimalSpawnRules"
                            : "checkTurtleSpawnRules";
            var predicate = selected(upstream, selector).getFirst();
            var handler = selected(mixin, "starfold$spawnSeaLevel").getFirst();
            var injection =
                    annotations(handler.visibleAnnotations, handler.invisibleAnnotations).stream()
                            .filter(
                                    a ->
                                            a.desc.equals(
                                                    "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(List.of(selector), strings(value(injection, "method")));
            var positions = new ArrayList<AnnotationNode>();
            ats(injection, positions);
            assertEquals(1, positions.size());
            assertEquals("INVOKE", value(positions.getFirst(), "value"));
            assertEquals(
                    "Lnet/minecraft/world/level/LevelAccessor;getSeaLevel()I",
                    value(positions.getFirst(), "target"));
            assertNotEquals(0, handler.access & Opcodes.ACC_STATIC);
            assertEquals(Type.INT_TYPE, Type.getReturnType(handler.desc));
            var captured = new ArrayList<Type>();
            captured.add(Type.INT_TYPE);
            captured.addAll(List.of(Type.getArgumentTypes(predicate.desc)));
            assertEquals(captured, List.of(Type.getArgumentTypes(handler.desc)), name);
            assertEquals(
                    1,
                    invocations(predicate).stream()
                            .filter(
                                    c ->
                                            c.owner.equals(
                                                            "net/minecraft/world/level/LevelAccessor")
                                                    && c.name.equals("getSeaLevel")
                                                    && c.desc.equals("()I"))
                            .count(),
                    name);
            assertEquals(
                    1,
                    invocations(handler).stream()
                            .filter(
                                    c ->
                                            c.owner.equals(
                                                            "dev/starfold/planet/PlanetWaterSpawning")
                                                    && c.name.equals("seaLevel")
                                                    && c.desc.equals(
                                                            "(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;I)I"))
                            .count(),
                    name);
        }
    }

    @Test
    void waterAppearanceLocalsAreBeforeTheFirstShadeInsideTheVisibleBranch() throws Exception {
        var node = read("net/minecraft/client/renderer/block/LiquidBlockRenderer");
        var method = selected(node, "tesselate").getFirst();
        var shade =
                java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .filter(i -> i instanceof MethodInsnNode m && m.name.equals("getShade"))
                        .findFirst()
                        .orElseThrow();
        int at = method.instructions.indexOf(shade);
        var locals =
                method.localVariables.stream()
                        .filter(
                                v ->
                                        method.instructions.indexOf(v.start) <= at
                                                && method.instructions.indexOf(v.end) > at)
                        .toList();
        assertEquals(
                List.of("alpha", "f", "f1", "f2"),
                locals.stream().filter(v -> v.desc.equals("F")).map(v -> v.name).toList());
        assertEquals(
                1,
                locals.stream()
                        .filter(
                                v ->
                                        v.desc.equals(
                                                "[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
                        .count());
        assertEquals(
                9,
                java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .limit(at)
                        .filter(
                                i ->
                                        i instanceof JumpInsnNode j
                                                && (j.getOpcode() == Opcodes.IFNE
                                                        || j.getOpcode() == Opcodes.IFEQ))
                        .count());
    }

    private static ClassNode target(ClassNode node) throws Exception {
        return targets(node).getFirst();
    }

    private static List<ClassNode> targets(ClassNode node) throws Exception {
        var result = new ArrayList<ClassNode>();
        for (String name : targetNames(node)) result.add(read(name));
        return result;
    }

    private static List<String> targetNames(ClassNode node) {
        var a =
                annotations(node.visibleAnnotations, node.invisibleAnnotations).stream()
                        .filter(v -> v.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                        .findFirst()
                        .orElseThrow();
        var result = new ArrayList<String>();
        var types = (List<?>) value(a, "value");
        if (types != null) for (var type : types) result.add(((Type) type).getInternalName());
        for (String name : strings(value(a, "targets"))) result.add(name.replace('.', '/'));
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
        for (var config : CONFIGS.entrySet()) {
            var module = switch (config.getValue()) {
                case "starfold.auditProbes" -> "probes/";
                case "starfold.auditPack" -> "worlds/";
                default -> "";
            };
            var listed = new TreeSet<String>();
            String packagePath;
            try (var in = resource(config.getKey(), config.getValue())) {
                var json =
                        com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                                .getAsJsonObject();
                packagePath = json.get("package").getAsString().replace('.', '/');
                for (var key : List.of("mixins", "client", "server"))
                    if (json.has(key))
                        json.getAsJsonArray(key).forEach(e -> listed.add(e.getAsString()));
            }
            var files = new TreeSet<String>();
            try (var paths = java.nio.file.Files.list(
                    java.nio.file.Path.of(module + "src/main/java/" + packagePath))) {
                paths.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".java"))
                        .forEach(name -> files.add(name.substring(0, name.length() - 5)));
            }
            assertEquals(listed, files, config.getKey());
        }
    }

    private static final Type OPERATION =
            Type.getObjectType("com/llamalad7/mixinextras/injector/wrapoperation/Operation");
    private static final Set<String> SUPPORTED_INJECTORS =
            Set.of(
                    "Lorg/spongepowered/asm/mixin/injection/Inject;",
                    "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
                    "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
                    "Lorg/spongepowered/asm/mixin/injection/Redirect;",
                    "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
                    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
                    "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    private static boolean walkingProjectionHandler(String mixin, String handler) {
        return mixin.equals("GroundFlightRenderMixin")
                && (handler.equals("starfold$captureWalkingProjection")
                        || handler.equals("starfold$publishWalkingProjection"));
    }

    private static void verifyWalkingProjectionLocal(
            String handler, MethodNode upstream, Type type, AnnotationNode local,
            String invocation, AnnotationNode position) {
        assertEquals("renderLevel", upstream.name);
        assertEquals(Type.getObjectType("org/joml/Matrix4f"), type);
        assertEquals(0, value(local, "ordinal"));
        assertNull(value(local, "index"));
        assertFalse(Boolean.TRUE.equals(value(local, "argsOnly")));

        String renderer = "net/minecraft/client/renderer/GameRenderer";
        String reset = "resetProjectionMatrix(Lorg/joml/Matrix4f;)V";
        boolean publish = handler.equals("starfold$publishWalkingProjection");
        assertEquals("L" + renderer + ";" + (publish ? reset
                : "bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"), invocation);
        if (publish) {
            assertArrayEquals(new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"},
                    (String[]) value(position, "shift"));
        } else {
            assertNull(value(position, "shift"));
        }

        var calls = invocations(upstream);
        var resets = calls.stream().filter(call -> call.owner.equals(renderer)
                && (call.name + call.desc).equals(reset)).toList();
        var points = calls.stream().filter(call -> invocation.equals(
                "L" + call.owner + ";" + call.name + call.desc)).toList();
        assertEquals(1, resets.size());
        assertEquals(1, points.size());
        var resetCall = resets.getFirst();
        var projection = assertInstanceOf(VarInsnNode.class, resetCall.getPrevious());
        assertEquals(Opcodes.ALOAD, projection.getOpcode());
        var point = points.getFirst();
        var injection = publish ? point.getNext() : point;
        while (injection.getOpcode() < 0) injection = injection.getNext();
        int at = upstream.instructions.indexOf(injection);
        var liveMatrices = upstream.localVariables.stream()
                .filter(v -> v.desc.equals(type.getDescriptor())
                        && upstream.instructions.indexOf(v.start) <= at
                        && at < upstream.instructions.indexOf(v.end))
                .map(v -> v.index).distinct().sorted().toList();
        assertEquals(List.of(projection.var), liveMatrices,
                handler + " captures the live matrix passed to resetProjectionMatrix");
    }

    /** Supports enclosing argument prefixes and unambiguous, typed @Local captures. */
    private static void verifyCapturedArguments(
            String name,
            MethodNode handler,
            int start,
            MethodNode upstream,
            String invocation,
            AnnotationNode position) {
        var args = Type.getArgumentTypes(handler.desc);
        var enclosing = Type.getArgumentTypes(upstream.desc);
        int captured = 0;
        for (int i = start; i < args.length; i++) {
            var parameter =
                    annotations(
                            handler.visibleParameterAnnotations == null
                                    ? null
                                    : handler.visibleParameterAnnotations[i],
                            handler.invisibleParameterAnnotations == null
                                    ? null
                                    : handler.invisibleParameterAnnotations[i]);
            var local =
                    parameter.stream()
                            .filter(a -> a.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;"))
                            .findFirst()
                            .orElse(null);
            if (local == null) {
                assertTrue(captured < enclosing.length, name + " excess enclosing arguments");
                assertEquals(enclosing[captured++], args[i], name + " captured enclosing argument");
                continue;
            }
            if (walkingProjectionHandler(name, handler.name)) {
                verifyWalkingProjectionLocal(handler.name, upstream, args[i], local, invocation, position);
                continue;
            }
            assertNull(
                    value(local, "ordinal"),
                    name + " typed @Local ordinal needs a dedicated contract");
            assertNull(
                    value(local, "index"), name + " typed @Local index needs a dedicated contract");
            assertFalse(
                    args[i].getInternalName().startsWith("com/llamalad7/mixinextras/sugar/ref/"),
                    name + " mutable @Local needs a dedicated contract");
            final Type type = args[i];
            if (Boolean.TRUE.equals(value(local, "argsOnly"))) {
                assertEquals(
                        1,
                        Arrays.stream(enclosing).filter(type::equals).count(),
                        name + " unique @Local argument " + type);
            } else {
                assertNotNull(invocation, name + " @Local requires an audited invocation point");
                var calls =
                        invocations(upstream).stream()
                                .filter(
                                        call ->
                                                invocation.equals(
                                                        "L"
                                                                + call.owner
                                                                + ";"
                                                                + call.name
                                                                + call.desc))
                                .toList();
                assertFalse(calls.isEmpty(), name + " @Local invocation");
                var ordinal = (Integer) value(position, "ordinal");
                var points = ordinal == null || ordinal < 0 ? calls : List.of(calls.get(ordinal));
                for (var call : points) {
                    int at = upstream.instructions.indexOf(call);
                    assertNotNull(
                            upstream.localVariables,
                            name + " @Local requires upstream debug locals");
                    assertEquals(
                            1,
                            upstream.localVariables.stream()
                                    .filter(
                                            v ->
                                                    v.desc.equals(type.getDescriptor())
                                                            && upstream.instructions.indexOf(
                                                                            v.start)
                                                                    <= at
                                                            && upstream.instructions.indexOf(v.end)
                                                                    > at)
                                    .map(v -> v.index)
                                    .distinct()
                                    .count(),
                            name + " unique live @Local " + type);
                }
            }
        }
    }

    @Test
    void everySelectedInjectionSelectsRealMethodsAndEveryInvokeMatchesExactly() throws Exception {
        int injectors = 0, invokes = 0;
        for (var registration : names()) {
            String name = registration.simpleName();
            var mixin = read(registration);
            for (var target : targets(mixin))
                for (var method : mixin.methods)
                    for (var a :
                            annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                        assertNotEquals(
                                "Lorg/spongepowered/asm/mixin/Overwrite;",
                                a.desc,
                                name + " @Overwrite needs a dedicated contract or named exemption");
                        boolean injector =
                                a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                                        || a.desc.startsWith(
                                                "Lcom/llamalad7/mixinextras/injector/");
                        if (!injector) continue;
                        assertTrue(
                                SUPPORTED_INJECTORS.contains(a.desc),
                                name
                                        + " unsupported injector "
                                        + a.desc
                                        + "; extend the audit or record a named exemption");
                        var selectors = strings(value(a, "method"));
                        assertFalse(
                                selectors.isEmpty(),
                                name + " missing method selectors: " + method.name);
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
                                    // Mixin permits a callback-only handler, including for
                                    // constructors.
                                    boolean walkingProjection = walkingProjectionHandler(name, method.name);
                                    int captured = args.length == 1 || walkingProjection ? 0 : original.length;
                                    assertEquals(
                                            captured
                                                    + (walkingProjection || name.equals("ThrusterOcclusionMixin")
                                                            ? 2
                                                            : name.equals("WaterAppearanceMixin")
                                                                    ? 6
                                                                    : 1),
                                            args.length,
                                            name + " callback args " + selector);
                                    for (int i = 0; i < captured; i++) {
                                        boolean coerced =
                                                method.invisibleParameterAnnotations != null
                                                        && method.invisibleParameterAnnotations[i]
                                                                != null
                                                        && method
                                                                .invisibleParameterAnnotations[i]
                                                                .stream()
                                                                .anyMatch(
                                                                        p ->
                                                                                p.desc.endsWith(
                                                                                        "/Coerce;"));
                                        if (coerced) {
                                            assertEquals(Type.OBJECT, original[i].getSort());
                                            assertEquals(Type.getType(Object.class), args[i]);
                                        } else
                                            assertEquals(
                                                    original[i],
                                                    args[i],
                                                    name + " callback argument " + i);
                                    }
                                    assertEquals(Type.VOID_TYPE, Type.getReturnType(method.desc));
                                    assertEquals(
                                            Type.getReturnType(upstream.desc).equals(Type.VOID_TYPE)
                                                    ? "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"
                                                    : "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable",
                                            args[captured].getInternalName(),
                                            name + " callback type");
                                }
                                if (name.equals("ThrusterOcclusionMixin")
                                        && a.desc.endsWith("/Inject;")) {
                                    var local =
                                            args[Type.getArgumentTypes(upstream.desc).length + 1];
                                    assertEquals(
                                            Type.getObjectType(
                                                    "net/minecraft/world/level/block/state/BlockState"),
                                            local);
                                    var call =
                                            java.util.stream.StreamSupport.stream(
                                                            upstream.instructions.spliterator(),
                                                            false)
                                                    .filter(
                                                            i ->
                                                                    i instanceof MethodInsnNode m
                                                                            && m.name.equals(
                                                                                    "skipRendering"))
                                                    .findFirst()
                                                    .orElseThrow();
                                    int at = upstream.instructions.indexOf(call);
                                    assertEquals(
                                            1,
                                            upstream.localVariables.stream()
                                                    .filter(
                                                            v ->
                                                                    v.index
                                                                                    == Type
                                                                                            .getArgumentTypes(
                                                                                                    upstream.desc)
                                                                                            .length
                                                                            && v.desc.equals(
                                                                                    local
                                                                                            .getDescriptor())
                                                                            && upstream.instructions
                                                                                            .indexOf(
                                                                                                    v.start)
                                                                                    <= at
                                                                            && upstream.instructions
                                                                                            .indexOf(
                                                                                                    v.end)
                                                                                    > at)
                                                    .count());
                                }
                                if (a.desc.endsWith("/WrapMethod;")) {
                                    assertEquals(
                                            original.length + 1,
                                            args.length,
                                            name + " wrapper arguments");
                                    for (int i = 0; i < original.length; i++)
                                        assertEquals(original[i], args[i]);
                                    assertEquals(
                                            OPERATION,
                                            args[original.length],
                                            name + " wrapper Operation");
                                    assertEquals(
                                            Type.getReturnType(upstream.desc),
                                            Type.getReturnType(method.desc));
                                }
                            }
                            var at = new ArrayList<AnnotationNode>();
                            ats(a, at);
                            if (!a.desc.endsWith("/WrapMethod;"))
                                assertFalse(at.isEmpty(), name + " missing injection point");
                            for (var position : at) {
                                String kind = (String) value(position, "value");
                                var supportedPoints =
                                        a.desc.endsWith("/Inject;")
                                                ? Set.of("HEAD", "TAIL", "RETURN", "INVOKE")
                                                : a.desc.endsWith("/WrapOperation;")
                                                        ? Set.of("INVOKE", "FIELD", "NEW")
                                                        : a.desc.endsWith("/ModifyVariable;")
                                                                ? Set.of("HEAD")
                                                                : Set.of("INVOKE");
                                assertTrue(
                                        supportedPoints.contains(kind),
                                        name
                                                + " unsupported injection point "
                                                + kind
                                                + " for "
                                                + a.desc);
                                if ("NEW".equals(kind))
                                    assertEquals(
                                            "PlanetLevelSoundMixin",
                                            name,
                                            "NEW is covered by verifyPlanetSoundCallChain; add an exact contract for other allocations");
                                if ("FIELD".equals(kind)) {
                                    assertTrue(
                                            a.desc.endsWith("/WrapOperation;"),
                                            name + " unsupported FIELD injector");
                                    var expected = (String) value(position, "target");
                                    var opcode = (Integer) value(position, "opcode");
                                    assertEquals(Opcodes.PUTFIELD, opcode);
                                    for (var upstream : methods) {
                                        var writes =
                                                java.util.stream.StreamSupport.stream(
                                                                upstream.instructions.spliterator(),
                                                                false)
                                                        .filter(
                                                                i ->
                                                                        i instanceof FieldInsnNode f
                                                                                && f.getOpcode()
                                                                                        == opcode
                                                                                && expected.equals(
                                                                                        "L"
                                                                                                + f.owner
                                                                                                + ";"
                                                                                                + f.name
                                                                                                + ":"
                                                                                                + f.desc))
                                                        .map(i -> (FieldInsnNode) i)
                                                        .toList();
                                        assertEquals(
                                                1,
                                                writes.size(),
                                                name + " exact field write " + selector);
                                        var args = Type.getArgumentTypes(method.desc);
                                        assertEquals(3, args.length);
                                        assertEquals(
                                                Type.getObjectType(writes.getFirst().owner),
                                                args[0]);
                                        assertEquals(Type.getType(writes.getFirst().desc), args[1]);
                                        assertEquals(
                                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                                args[2].getInternalName());
                                        assertEquals(
                                                Type.VOID_TYPE, Type.getReturnType(method.desc));
                                    }
                                }
                                if ("INVOKE".equals(value(position, "value"))) {
                                    var expected = (String) value(position, "target");
                                    assertNotNull(expected);
                                    boolean found = false, staticInvoke = false;
                                    int matches = 0;
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
                                                matches++;
                                                staticInvoke =
                                                        call.getOpcode() == Opcodes.INVOKESTATIC;
                                            }
                                    assertTrue(found, name + ": " + selector + " -> " + expected);
                                    var ordinal = (Integer) value(position, "ordinal");
                                    if (ordinal != null && ordinal >= 0)
                                        assertTrue(
                                                ordinal < matches,
                                                name
                                                        + " invocation ordinal "
                                                        + ordinal
                                                        + " -> "
                                                        + expected);
                                    invokes++;
                                    String descriptor = expected.substring(expected.indexOf('('));
                                    if (a.desc.endsWith("/Inject;") && walkingProjectionHandler(name, method.name)) {
                                        for (var upstream : methods)
                                            verifyCapturedArguments(name, method, 1, upstream, expected, position);
                                    }
                                    if (a.desc.endsWith("/ModifyExpressionValue;")) {
                                        assertEquals(
                                                Type.getReturnType(descriptor),
                                                Type.getArgumentTypes(method.desc)[0]);
                                        assertEquals(
                                                Type.getReturnType(descriptor),
                                                Type.getReturnType(method.desc));
                                        for (var upstream : methods)
                                            verifyCapturedArguments(
                                                    name, method, 1, upstream, expected, position);
                                    }
                                    if (a.desc.endsWith("/ModifyArg;")) {
                                        var args = Type.getArgumentTypes(method.desc);
                                        var invoked = Type.getArgumentTypes(descriptor);
                                        var index = (Integer) value(a, "index");
                                        assertNotNull(
                                                index,
                                                name
                                                        + " ModifyArg requires an explicit audited index");
                                        assertTrue(
                                                index >= 0 && index < invoked.length,
                                                name + " ModifyArg index");
                                        assertEquals(
                                                List.of(invoked[index]),
                                                List.of(args),
                                                name + " ModifyArg parameter");
                                        assertEquals(
                                                invoked[index],
                                                Type.getReturnType(method.desc),
                                                name + " ModifyArg return");
                                    }
                                    if (a.desc.endsWith("/Redirect;")) {
                                        var expectedArgs = new ArrayList<Type>();
                                        if (!staticInvoke)
                                            expectedArgs.add(
                                                    Type.getObjectType(
                                                            expected.substring(
                                                                    1, expected.indexOf(';'))));
                                        expectedArgs.addAll(
                                                List.of(Type.getArgumentTypes(descriptor)));
                                        assertEquals(
                                                expectedArgs,
                                                List.of(Type.getArgumentTypes(method.desc)),
                                                name + " redirect arguments");
                                        assertEquals(
                                                Type.getReturnType(descriptor),
                                                Type.getReturnType(method.desc),
                                                name + " redirect return");
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
                                        if (walkingProjectionHandler(name, method.name))
                                            assertEquals(operation + 2, args.length);
                                        assertEquals(
                                                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                                args[operation].getInternalName());
                                        assertEquals(
                                                Type.getReturnType(descriptor),
                                                Type.getReturnType(method.desc));
                                        for (var upstream : methods)
                                            verifyCapturedArguments(
                                                    name,
                                                    method,
                                                    operation + 1,
                                                    upstream,
                                                    expected,
                                                    position);
                                    }
                                }
                            }
                            if (a.desc.endsWith("/ModifyVariable;")) {
                                assertEquals(
                                        Boolean.TRUE,
                                        value(a, "argsOnly"),
                                        name + " only argument ModifyVariable is audited");
                                var args = Type.getArgumentTypes(method.desc);
                                var type = args[0];
                                assertEquals(
                                        type,
                                        Type.getReturnType(method.desc),
                                        name + " variable return type");
                                var ordinal = (Integer) value(a, "ordinal");
                                for (var upstream : methods) {
                                    long count =
                                            Arrays.stream(Type.getArgumentTypes(upstream.desc))
                                                    .filter(type::equals)
                                                    .count();
                                    if (ordinal == null || ordinal < 0)
                                        assertEquals(
                                                1,
                                                count,
                                                name
                                                        + " implicit variable discriminator "
                                                        + selector);
                                    else
                                        assertTrue(
                                                count > ordinal,
                                                name + " variable type/ordinal " + selector);
                                    verifyCapturedArguments(name, method, 1, upstream, null, null);
                                }
                            }
                        }
                    }
        }
        assertTrue(injectors >= 30);
        assertTrue(invokes >= 2);
        verifyPlanetSoundCallChain();
        aquaticSpawnHeightHandlersCaptureTheExactUpstreamArguments();
        waterAppearanceLocalsAreBeforeTheFirstShadeInsideTheVisibleBranch();
    }

    @Test
    void shadowFieldsAndPacketAccessorsMatchUpstreamDescriptors() throws Exception {
        int checked = 0;
        for (var registration : names()) {
            String name = registration.simpleName();
            var mixin = read(registration);
            for (var target : targets(mixin)) {
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
                    for (var a :
                            annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                        if (a.desc.endsWith("/Shadow;")) {
                            assertTrue(
                                    target.methods.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.name.equals(method.name)
                                                                    && m.desc.equals(method.desc)),
                                    name
                                            + " method shadow "
                                            + target.name
                                            + "."
                                            + method.name
                                            + method.desc);
                            checked++;
                        }
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
                                descriptor =
                                        descriptor.substring(0, descriptor.indexOf(')') + 1) + "V";
                            final String d = descriptor;
                            assertTrue(
                                    target.methods.stream()
                                            .anyMatch(
                                                    m -> m.name.equals(called) && m.desc.equals(d)),
                                    name + " invoker " + called);
                            checked++;
                        }
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
        for (var registration : names()) {
            String name = registration.simpleName();
            var mixin = read(registration);
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
                                        c.owner.equals(
                                                        "dev/starfold/planet/PlanetSoundClient$Observer")
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
