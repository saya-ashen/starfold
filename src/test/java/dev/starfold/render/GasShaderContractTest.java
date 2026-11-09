package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.util.*;

/** Actual platform bytecode and resource contracts; no GL context or shader upload. */
class GasShaderContractTest {
    private static ClassNode type(String name) throws Exception {
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(String owner, String name, String descriptor) throws Exception {
        return type(owner).methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst()
                .orElseThrow();
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do instruction = instruction.getPrevious();
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    @Test
    void gasCustomUniformsDoNotReusePlatformManagedNamesEvenWithMatchingSizes() throws Exception {
        var reserved = new TreeSet<String>();
        for (var m : type("net/minecraft/client/renderer/ShaderInstance").methods) {
            if (!m.name.equals("<init>")) continue;
            for (var i : m.instructions)
                if (i instanceof MethodInsnNode call && call.name.equals("getUniform")) {
                    var literal = previous(i);
                    assertInstanceOf(LdcInsnNode.class, literal);
                    reserved.add((String) ((LdcInsnNode) literal).cst);
                }
        }
        assertTrue(reserved.containsAll(Set.of("ModelViewMat", "ProjMat", "FogColor")));
        var json = JsonParser.parseString(
                        Files.readString(GasRenderTest.SHADERS.resolve("native_gas_giant.json")))
                .getAsJsonObject();
        var managed = new TreeSet<String>();
        for (var entry : json.getAsJsonArray("uniforms")) {
            var uniform = entry.getAsJsonObject();
            var name = uniform.get("name").getAsString();
            if (!reserved.contains(name)) continue;
            managed.add(name);
            assertTrue(Set.of("ModelViewMat", "ProjMat").contains(name),
                    "Custom GAS uniform collides with platform-managed " + name);
            assertEquals("matrix4x4", uniform.get("type").getAsString(), name);
            assertEquals(16, uniform.get("count").getAsInt(), name);
        }
        assertEquals(Set.of("ModelViewMat", "ProjMat"), managed);
    }

    @Test
    void platformWritesFourFogFloatsAfterRendererValuesBeforeApplyingShader() throws Exception {
        String shader = "net/minecraft/client/renderer/ShaderInstance";
        var defaults = type(shader).methods.stream()
                .filter(m -> m.name.equals("setDefaultUniforms"))
                .findFirst().orElseThrow();
        boolean fogWrite = false;
        for (var i : defaults.instructions) {
            if (!(i instanceof MethodInsnNode call)
                    || !call.owner.equals("com/mojang/blaze3d/shaders/Uniform")
                    || !call.name.equals("set") || !call.desc.equals("([F)V")) continue;
            if (!(previous(i) instanceof MethodInsnNode getter)
                    || !getter.name.equals("getShaderFogColor")) continue;
            var field = (FieldInsnNode) previous(getter);
            assertEquals("FOG_COLOR", field.name);
            fogWrite = true;
        }
        assertTrue(fogWrite, "Platform fog write contract changed");
        String renderSystem = "com/mojang/blaze3d/systems/RenderSystem";
        var getter = method(renderSystem, "getShaderFogColor", "()[F");
        var fogFields = new ArrayList<FieldInsnNode>();
        for (var i : getter.instructions)
            if (i instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)
                fogFields.add(field);
        assertEquals(1, fogFields.size());
        var fogField = fogFields.getFirst();
        boolean fourFloats = false;
        for (var i : method(renderSystem, "<clinit>", "()V").instructions)
            if (i instanceof IntInsnNode allocation && allocation.getOpcode() == Opcodes.NEWARRAY
                    && allocation.operand == Opcodes.T_FLOAT
                    && previous(i).getOpcode() == Opcodes.ICONST_4) {
                for (var next = i.getNext(); next != null; next = next.getNext())
                    if (next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
                        fourFloats = field.name.equals(fogField.name);
                        break;
                    }
                if (fourFloats) break;
            }
        assertTrue(fourFloats, "Fog color must be the actual four-float platform array");
        var setter = method("com/mojang/blaze3d/shaders/Uniform", "set", "([F)V");
        assertTrue(Arrays.stream(setter.instructions.toArray()).anyMatch(i ->
                i instanceof MethodInsnNode c && c.owner.equals("java/nio/FloatBuffer")
                        && c.name.equals("put") && c.desc.equals("([F)Ljava/nio/FloatBuffer;")));
        var draw = type("com/mojang/blaze3d/vertex/VertexBuffer").methods.stream()
                .filter(m -> m.name.equals("_drawWithShader")).findFirst().orElseThrow();
        var calls = Arrays.stream(draw.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode)
                .map(i -> ((MethodInsnNode) i).name).toList();
        assertTrue(calls.indexOf("setDefaultUniforms") >= 0);
        assertTrue(calls.indexOf("setDefaultUniforms") < calls.indexOf("apply"));
    }
}
