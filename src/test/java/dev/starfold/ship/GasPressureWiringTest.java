package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

class GasPressureWiringTest {
    private static ClassNode type(String name) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode c) result.add(c);
        return result;
    }

    @Test
    void pressurePublishesFromExistingViewTraversalNotFromHullMutationLoop() throws Exception {
        var server = type("dev/starfold/space/SpaceServer");
        var view =
                type("dev/starfold/space/SpaceSessions").methods.stream()
                        .filter(m -> m.name.equals("view") && m.desc.endsWith("J"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                1,
                calls(view).stream()
                        .filter(
                                c ->
                                        c.owner.equals("dev/starfold/space/GasPressureServer")
                                                && c.name.equals("send"))
                        .count());
        var step =
                server.methods.stream()
                        .filter(m -> m.name.equals("step"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(
                calls(step).stream()
                        .anyMatch(c -> c.owner.equals("dev/starfold/space/GasPressureServer")));
        var publisher = type("dev/starfold/space/GasPressureServer");
        var send =
                publisher.methods.stream()
                        .filter(m -> m.name.equals("send"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                calls(send).stream()
                        .anyMatch(
                                c ->
                                        c.owner.equals("dev/starfold/ship/GasShipDamage")
                                                && c.name.equals("sample")));
        assertFalse(
                calls(send).stream()
                        .anyMatch(
                                c ->
                                        List.of(
                                                        "padding",
                                                        "setBlock",
                                                        "getLoadedChunks",
                                                        "destroyBlock")
                                                .contains(c.name)));
        var init =
                server.methods.stream()
                        .filter(m -> m.name.equals("initialize"))
                        .findFirst()
                        .orElseThrow();
        String packet = null;
        boolean registeredS2C = false;
        for (var i : init.instructions) {
            if (i instanceof FieldInsnNode f && f.name.equals("TYPE")) packet = f.owner;
            if (i instanceof MethodInsnNode c
                    && c.owner.equals("dev/starfold/network/StarfoldNetwork")) {
                if ("dev/starfold/ship/GasPressurePacket".equals(packet)) {
                    assertNotEquals("playC2S", c.name);
                    if (c.name.equals("playS2C")) registeredS2C = true;
                }
            }
        }
        assertTrue(registeredS2C);
    }

    @Test
    void selectedHudReadsAuthoritativeHullSamplesAndAmbientWarningIgnoresTargetLock()
            throws Exception {
        var client = type("dev/starfold/space/SpaceClient");
        assertTrue(
                client.methods.stream()
                        .flatMap(m -> calls(m).stream())
                        .anyMatch(
                                c ->
                                        c.owner.equals("dev/starfold/space/GasPressureClient")
                                                && c.name.equals("initialize")));
        var navigation = type("dev/starfold/space/SpaceNavigationClient");
        var all = navigation.methods.stream().flatMap(m -> calls(m).stream()).toList();
        assertTrue(
                all.stream()
                        .anyMatch(
                                c ->
                                        c.owner.equals("dev/starfold/space/GasPressureClient")
                                                && c.name.equals("selected")));
        assertFalse(
                all.stream()
                        .anyMatch(
                                c ->
                                        c.owner.equals(
                                                        "dev/starfold/space/CelestialNavigation$Reading")
                                                && c.name.equals("gas")));
        var pressure = type("dev/starfold/space/GasPressureClient");
        var warning =
                pressure.methods.stream()
                        .filter(m -> m.name.equals("warning"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(
                calls(warning).stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("SpaceNavigationClient")
                                                || c.name.equals("selected")));
        assertTrue(calls(warning).stream().anyMatch(c -> c.name.equals("packet")));
    }
}
