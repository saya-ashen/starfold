package dev.starfold.space;

import dev.starfold.TestArchiveResources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Offline boundaries for engine callbacks that need a running level to execute. */
final class SpaceBytecode {
    static MethodNode method(String type, String name) throws Exception {
        var node = new ClassNode();
        try (var input = TestArchiveResources.open("/dev/starfold/" + type + ".class")) {
            new ClassReader(input).accept(node, 0);
        }
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    static java.util.List<String> calls(String type, String method) throws Exception {
        var calls = new java.util.ArrayList<String>();
        for (var instruction : method(type, method).instructions)
            if (instruction instanceof MethodInsnNode call)
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
        return calls;
    }

    static void before(java.util.List<String> calls, String first, String second) {
        org.junit.jupiter.api.Assertions.assertTrue(calls.contains(first), first);
        org.junit.jupiter.api.Assertions.assertTrue(calls.indexOf(second) > calls.indexOf(first), first + " before " + second);
    }
}
