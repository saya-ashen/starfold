package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

class GasDamageWiringTest {
    private static final String ADAPTER = "dev/starfold/ship/GasShipDamage";

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
        for (var i : method.instructions) if (i instanceof MethodInsnNode call) result.add(call);
        return result;
    }

    @Test
    void existingLoadedCohortIsServicedOnceAfterLeadAndFollowersNeverFromPhysicsSubsteps()
            throws Exception {
        var node = type("dev/starfold/space/SpaceServer");
        var step =
                node.methods.stream().filter(m -> m.name.equals("step")).findFirst().orElseThrow();
        var names = calls(step).stream().map(c -> c.owner + "." + c.name).toList();
        int damage = names.indexOf(ADAPTER + ".step");
        assertEquals(names.size() - 1, damage);
        assertTrue(damage > names.indexOf("dev/starfold/space/SpaceServer.advanceLead"));
        assertTrue(damage > names.indexOf("dev/starfold/space/SpaceServer.settleFollowers"));
        assertTrue(damage > names.indexOf("dev/starfold/chart/SunBoundary.inFrame"));
        assertEquals(1, calls(step).stream().filter(c -> c.name.equals("spaceBodies")).count());
        assertEquals(
                1,
                node.methods.stream()
                        .flatMap(m -> calls(m).stream())
                        .filter(c -> c.owner.equals(ADAPTER) && c.name.equals("step"))
                        .count());
        for (var method : node.methods)
            if (method.name.contains("Physics"))
                assertFalse(calls(method).stream().anyMatch(c -> c.owner.equals(ADAPTER)));
    }

    @Test
    void emptySpaceCohortReleasesTransientStateBeforeItsEarlyReturn() throws Exception {
        var node = type("dev/starfold/space/SpaceServer");
        var step =
                node.methods.stream().filter(m -> m.name.equals("step")).findFirst().orElseThrow();
        var empty =
                calls(step).stream()
                        .filter(c -> c.name.equals("isEmpty"))
                        .findFirst()
                        .orElseThrow();
        var next = empty.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        var branch = assertInstanceOf(JumpInsnNode.class, next);
        assertEquals(org.objectweb.asm.Opcodes.IFEQ, branch.getOpcode());
        var earlyCalls = new ArrayList<String>();
        boolean returns = false;
        for (var i = branch.getNext(); i != branch.label; i = i.getNext()) {
            assertNotNull(i);
            if (i instanceof MethodInsnNode c) earlyCalls.add(c.owner + "." + c.name);
            if (i.getOpcode() == org.objectweb.asm.Opcodes.RETURN) returns = true;
        }
        int release = earlyCalls.indexOf(ADAPTER + ".release");
        int clear = earlyCalls.indexOf("dev/starfold/space/SpaceServer.clearEmptyRegion");
        assertTrue(
                release >= 0 && clear > release,
                "empty cohort must clear pressure scans before return");
        assertTrue(returns);
        assertFalse(
                earlyCalls.stream()
                        .anyMatch(
                                c ->
                                        c.contains("GasDamageSave")
                                                || c.contains("GasDamageCooldown")));
    }

    @Test
    void systemUnloadAndServerStopReleaseScanStateWithoutClearingSavedCooldown() throws Exception {
        var node = type("dev/starfold/space/SpaceSessions");
        var unload =
                node.methods.stream()
                        .filter(m -> m.desc.contains("LevelEvent$Unload"))
                        .findFirst()
                        .orElseThrow();
        var stop =
                node.methods.stream()
                        .filter(m -> m.desc.contains("ServerStoppedEvent"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(calls(unload).stream().anyMatch(c -> c.owner.equals(node.name) && c.name.equals("release")));
        assertTrue(calls(stop).stream().anyMatch(c -> c.owner.equals(node.name) && c.name.equals("clear")));
        for (String name : List.of("release", "clear")) {
            var reset = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            assertTrue(calls(reset).stream().anyMatch(c -> c.owner.equals(ADAPTER) && c.name.equals(name)));
        }
        var adapter = type(ADAPTER);
        for (var method : adapter.methods)
            if (List.of("release", "clear", "removed").contains(method.name))
                assertFalse(
                        calls(method).stream()
                                .anyMatch(
                                        c ->
                                                c.owner.contains("GasDamageSave")
                                                        || c.owner.contains("GasDamageCooldown")));
    }

    @Test
    void everyNativeSublevelRemovalIncludingDormancyDropsItsScanCursor() throws Exception {
        var observer = type("dev/starfold/ship/ShipRecords$1");
        var removal =
                observer.methods.stream()
                        .filter(m -> m.name.equals("onSubLevelRemoved"))
                        .findFirst()
                        .orElseThrow();
        var calls = calls(removal);
        int cleanup = -1, record = -1;
        for (int i = 0; i < calls.size(); i++) {
            var c = calls.get(i);
            if (c.owner.equals(ADAPTER) && c.name.equals("removed")) cleanup = i;
            if (c.owner.endsWith("ShipObserver") && c.name.equals("removed")) record = i;
        }
        assertTrue(cleanup >= 0 && record > cleanup);
    }
}
