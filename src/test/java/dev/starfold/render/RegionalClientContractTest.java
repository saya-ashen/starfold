package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class RegionalClientContractTest {
    private static ClassNode node(String name) throws Exception {
        var n = new ClassNode();
        try (var in =
                dev.starfold.TestArchiveResources.open(
                        "/dev/starfold/" + name + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(n, 0);
            return n;
        }
    }

    private static int known(MethodNode m) {
        for (int i = 0; i < m.instructions.size(); i++)
            if (m.instructions.get(i) instanceof MethodInsnNode c
                    && c.owner.equals("dev/starfold/worldgen/PlanetTint")
                    && c.name.equals("known")) return i;
        return -1;
    }

    private static int profileWrite(MethodNode m) {
        for (int i = 0; i < m.instructions.size(); i++)
            if (m.instructions.get(i) instanceof FieldInsnNode f
                    && f.getOpcode() == Opcodes.PUTSTATIC
                    && f.name.equals("profile")) return i;
        return -1;
    }

    @Test
    void localReceiptAndPreviewValidateBeforeReplacingProfile() throws Exception {
        var n = node("render/GroundViewClient");
        int guarded = 0;
        for (var m : n.methods)
            if (profileWrite(m) >= 0 && known(m) >= 0) {
                assertTrue(known(m) < profileWrite(m));
                boolean branches = false;
                for (int i = known(m) + 1; i < profileWrite(m); i++)
                    if (m.instructions.get(i) instanceof JumpInsnNode) branches = true;
                assertTrue(branches);
                guarded++;
            }
        assertEquals(
                2,
                guarded,
                "both local payload receiver and passive preview must reject unknown IDs before profile mutation");
        var preview =
                n.methods.stream().filter(m -> m.name.equals("preview")).findFirst().orElseThrow();
        assertTrue(
                java.util.stream.StreamSupport.stream(preview.instructions.spliterator(), false)
                        .anyMatch(
                                i ->
                                        i instanceof MethodInsnNode c
                                                && c.owner.endsWith("PlanetRemoteView$Snapshot")
                                                && c.name.equals("terrain")));
    }

    @Test
    void remoteReceiptAndEveryProceduralTintPathCarryTheProjection() throws Exception {
        var remote = node("render/PlanetRemoteRenderer");
        boolean guarded = false;
        for (var m : remote.methods)
            if (known(m) >= 0) {
                int mutation = -1;
                for (int i = 0; i < m.instructions.size(); i++)
                    if (m.instructions.get(i) instanceof MethodInsnNode c
                            && c.name.equals("computeIfAbsent")) mutation = i;
                assertTrue(mutation > known(m));
                guarded = true;
            }
        assertTrue(guarded);
        for (String path :
                new String[] {
                    "render/GroundMeshContext", "render/RemotePlanetMeshes$Job", "dev/DevPortProbe"
                }) {
            var n = node(path);
            int calls = 0;
            for (var m : n.methods)
                for (var i : m.instructions)
                    if (i instanceof MethodInsnNode c
                            && c.owner.equals("dev/starfold/worldgen/PlanetTint")
                            && c.name.equals("view")) {
                        assertTrue(c.desc.contains("Ldev/starfold/worldgen/PlanetTerrain;"), path);
                        calls++;
                    }
            assertTrue(calls > 0, path);
        }
        var snapshot =
                node("render/PlanetRemoteView").methods.stream()
                        .filter(m -> m.name.equals("snapshot"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                java.util.stream.StreamSupport.stream(snapshot.instructions.spliterator(), false)
                        .anyMatch(
                                i ->
                                        i instanceof FieldInsnNode c
                                                && c.owner.endsWith("PlanetRemoteView")
                                                && c.name.equals("terrain")));
    }
}
