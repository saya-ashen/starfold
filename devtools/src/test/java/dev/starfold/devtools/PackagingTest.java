package dev.starfold.devtools;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PackagingTest {
    private static ClassNode read(ZipFile zip, String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(zip.getInputStream(zip.getEntry(name)).readAllBytes()).accept(node, 0);
        return node;
    }
    @Test void jarsAreSeparateAndClientOnly() throws Exception {
        try (ZipFile mod = new ZipFile(System.getProperty("devtools.jar"));
                ZipFile root = new ZipFile(System.getProperty("starfold.jar"))) {
            assertNotNull(mod.getEntry("dev/starfold/devtools/Devtools.class"));
            assertNull(mod.getEntry("dev/starfold/api/RenderTimings.class"));
            assertTrue(root.stream().noneMatch(e -> e.getName().startsWith("dev/starfold/devtools/")));
            assertNotNull(root.getEntry("dev/starfold/api/RenderTimings.class"));
            String mixins = new String(mod.getInputStream(mod.getEntry("starfold_devtools.mixins.json")).readAllBytes());
            assertTrue(mixins.contains("\"client\"")); assertFalse(mixins.contains("\"mixins\""));
            String metadata = new String(mod.getInputStream(mod.getEntry("META-INF/neoforge.mods.toml")).readAllBytes());
            assertTrue(metadata.contains("modId=\"starfold_devtools\"")); assertFalse(metadata.contains("${version}"));
            var annotation = read(mod, "dev/starfold/devtools/Devtools.class").visibleAnnotations.stream()
                    .filter(a -> a.desc.equals("Lnet/neoforged/fml/common/Mod;")).findFirst().orElseThrow();
            int dist = annotation.values.indexOf("dist");
            assertTrue(dist >= 0);
            assertEquals("CLIENT", ((String[]) ((List<?>) annotation.values.get(dist + 1)).getFirst())[1]);
        }
    }
    @Test void packagedWrapsMatchEveryPatchedCall() throws Exception {
        try (ZipFile mod = new ZipFile(System.getProperty("devtools.jar"));
                ZipFile upstream = new ZipFile(System.getProperty("minecraft.jar"))) {
            int checked = 0;
            for (String mixin : List.of("FrameMixin", "LevelMixin", "WorldMixin")) {
                var node = read(mod, "dev/starfold/devtools/mixin/" + mixin + ".class");
                String owner = switch (mixin) {
                    case "FrameMixin" -> "net/minecraft/client/Minecraft";
                    case "LevelMixin" -> "net/minecraft/client/renderer/LevelRenderer";
                    default -> "net/minecraft/client/renderer/GameRenderer";
                };
                var target = read(upstream, owner + ".class");
                for (var method : node.methods) {
                    if (method.visibleAnnotations == null) continue;
                    for (var annotation : method.visibleAnnotations) {
                        if (!annotation.desc.endsWith("/WrapOperation;")) continue;
                        @SuppressWarnings("unchecked")
                        var names = (List<String>) annotation.values.get(annotation.values.indexOf("method") + 1);
                        var at = (org.objectweb.asm.tree.AnnotationNode) ((List<?>) annotation.values.get(annotation.values.indexOf("at") + 1)).getFirst();
                        String invoke = (String) at.values.get(at.values.indexOf("target") + 1);
                        int semicolon = invoke.indexOf(';'), paren = invoke.indexOf('(');
                        String invokeOwner = invoke.substring(1, semicolon);
                        String invokeName = invoke.substring(semicolon + 1, paren);
                        String descriptor = invoke.substring(paren);
                        var args = Type.getArgumentTypes(method.desc);
                        assertEquals(invokeOwner, args[0].getInternalName());
                        // Last handler argument is the wrapped Operation.
                        assertEquals(descriptor, Type.getMethodDescriptor(Type.getReturnType(method.desc),
                                java.util.Arrays.copyOfRange(args, 1, args.length - 1)));
                        var invoked = read(upstream, invokeOwner + ".class");
                        assertEquals(1, invoked.methods.stream().filter(m -> m.name.equals(invokeName) && m.desc.equals(descriptor)).count());
                        for (String name : names) {
                            var callers = target.methods.stream().filter(m -> m.name.equals(name)).toList();
                            assertEquals(1, callers.size());
                            int calls = 0;
                            for (var insn : callers.getFirst().instructions)
                                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                                        && call.owner.equals(invokeOwner) && call.name.equals(invokeName) && call.desc.equals(descriptor)) calls++;
                            assertEquals(mixin.equals("LevelMixin") ? 7 : 1, calls);
                        }
                        assertFalse(method.tryCatchBlocks.isEmpty(), "must end on exception paths");
                        checked++;
                    }
                }
            }
            assertEquals(3, checked);
        }
    }
}
