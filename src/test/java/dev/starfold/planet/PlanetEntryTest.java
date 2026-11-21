package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

class PlanetEntryTest {
    @Test
    void homePreparationRunsAfterAllLevelsExistBeforeTheServerStartsTicking() throws Exception {
        var config =
                com.google.gson.JsonParser.parseString(
                                Files.readString(
                                        Path.of("src/main/resources/starfold.mixins.json")))
                        .getAsJsonObject();
        assertEquals(
                1,
                config.getAsJsonArray("mixins").asList().stream()
                        .filter(v -> v.getAsString().equals("PlanetPrepareMixin"))
                        .count());
        var load = calls(method("net/minecraft/server/MinecraftServer", "loadLevel"));
        assertTrue(load.indexOf("createLevels") >= 0);
        assertTrue(load.indexOf("createLevels") < load.indexOf("prepareLevels"));
        var run = calls(method("net/minecraft/server/MinecraftServer", "runServer"));
        assertTrue(run.indexOf("initServer") < run.indexOf("tickServer"));
        for (var name :
                java.util.List.of(
                        "net/minecraft/server/dedicated/DedicatedServer",
                        "net/minecraft/client/server/IntegratedServer")) {
            var init = calls(method(name, "initServer"));
            assertTrue(init.contains("loadLevel"), name);
        }
    }

    private static MethodNode method(String name, String method) throws IOException {
        try (var in =
                net.minecraft.world.entity.Entity.class.getResourceAsStream(
                        "/" + name + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node.methods.stream()
                    .filter(m -> m.name.equals(method))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static java.util.List<String> calls(MethodNode method) {
        var result = new ArrayList<String>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) result.add(call.name);
        return result;
    }
}
