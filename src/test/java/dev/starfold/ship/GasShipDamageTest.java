package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.starfold.TestArchiveResources;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class GasShipDamageTest {
    private static Pose3d pose(double x) {
        return new Pose3d(
                new Vector3d(x, 0, 0), new Quaterniond(), new Vector3d(), new Vector3d(1));
    }

    private static KinematicContraption child(boolean valid, AtomicInteger calls) {
        return (KinematicContraption)
                Proxy.newProxyInstance(
                        KinematicContraption.class.getClassLoader(),
                        new Class<?>[] {KinematicContraption.class},
                        (proxy, method, args) -> {
                            calls.incrementAndGet();
                            return switch (method.getName()) {
                                case "sable$isValid" -> valid;
                                case "sable$getLocalBounds" -> {
                                    ((BoundingBox3i) args[0]).set(0, 0, 0, 0, 0, 0);
                                    yield null;
                                }
                                case "sable$getLocalPose" -> {
                                    var dest = (Pose3d) args[0];
                                    dest.position().set(100, 0, 0);
                                    dest.orientation().identity();
                                    dest.rotationPoint().zero();
                                    dest.scale().set(1);
                                    yield dest;
                                }
                                default ->
                                        throw new AssertionError(
                                                "Unexpected child metadata/block access: "
                                                        + method);
                            };
                        });
    }

    @Test
    void actualChildMetadataAdapterIsBoundedAndIncludesMountedExtent() {
        var calls = new AtomicInteger();
        var children = new ArrayList<KinematicContraption>();
        children.add(child(true, calls));
        var unit = new BoundingBox3i(0, 0, 0, 0, 0, 0);
        double actual = GasShipDamage.padding(pose(0), unit, children).orElseThrow();
        assertEquals(Math.sqrt(10203) + Math.sqrt(3) / 2, actual, 1e-10);
        assertEquals(3, calls.get());
        children.clear();
        for (int i = 0; i < GasShipDamage.MAX_CHILDREN; i++) children.add(child(true, calls));
        calls.set(0);
        assertTrue(GasShipDamage.padding(pose(0), unit, children).isPresent());
        assertEquals(256 * 3, calls.get());
        children.add(child(true, calls));
        calls.set(0);
        assertTrue(GasShipDamage.padding(pose(0), unit, children).isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void unsupportedGeometryIsUnknownAndInvalidChildrenAreNotDereferenced() {
        var calls = new AtomicInteger();
        var unit = new BoundingBox3i(0, 0, 0, 0, 0, 0);
        assertEquals(
                Math.sqrt(3) * 1.5,
                GasShipDamage.padding(pose(0), unit, List.of(child(false, calls))).orElseThrow(),
                1e-12);
        assertEquals(1, calls.get());
        assertTrue(GasShipDamage.padding(pose(0), null, List.of()).isEmpty());
        var bad = pose(0);
        bad.scale().x = Double.NaN;
        assertTrue(GasShipDamage.padding(bad, unit, List.of()).isEmpty());
    }

    @Test
    void sharedTickGateRejectsRepeatedSubstepOrRollbackService() {
        var gate = new GasShipDamage.TickGate();
        assertTrue(gate.enter(10));
        for (int i = 0; i < 100; i++) assertFalse(gate.enter(10));
        assertFalse(gate.enter(9));
        assertFalse(gate.enter(0));
        assertTrue(gate.enter(11));
        assertThrows(IllegalArgumentException.class, () -> gate.enter(-1));
    }

    @Test
    void normalMutationKeepsAdmissionExposureCadenceAndSuccessOrdering() throws Exception {
        assertEquals(
                0, GasShipDamage.FLAGS & net.minecraft.world.level.block.Block.UPDATE_NEIGHBORS);
        assertEquals(50, GasShipDamage.FLAGS);
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/ship/GasShipDamage.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        var method =
                node.methods.stream()
                        .filter(m -> m.name.equals("scanAndRemove"))
                        .findFirst()
                        .orElseThrow();
        var calls = new ArrayList<String>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode m) calls.add(m.name);
        int mutation = calls.indexOf("setBlock");
        assertTrue(mutation > calls.indexOf("ready"));
        assertTrue(mutation > calls.indexOf("checkedChunk"));
        assertTrue(mutation > calls.lastIndexOf("eligible"));
        assertTrue(mutation > calls.lastIndexOf("exposed"));
        assertTrue(calls.indexOf("succeeded") > mutation);
        assertTrue(calls.lastIndexOf("getBlockState") > mutation, "only count a confirmed AIR write");
        var step =
                node.methods.stream().filter(m -> m.name.equals("step")).findFirst().orElseThrow();
        var serviceCalls = new ArrayList<String>();
        for (var i : step.instructions)
            if (i instanceof MethodInsnNode call) serviceCalls.add(call.name);
        assertTrue(serviceCalls.indexOf("ordered") >= 0);
        assertTrue(serviceCalls.indexOf("ordered") < serviceCalls.indexOf("begin"));
        for (var m : node.methods)
            for (var i : m.instructions)
                if (i instanceof MethodInsnNode call) {
                    assertNotEquals("destroyBlock", call.name);
                    assertNotEquals("addRegionTicket", call.name);
                    assertNotEquals(
                            "setBlockState", call.name, "do not bypass Level notifications");
                }
    }
}
