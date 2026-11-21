package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

class GroundCoverageOwnerTest {
    private static MethodNode method(String name, String method) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in, name);
            new ClassReader(in).accept(node, 0);
        }
        return node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
    }

    @Test
    void coverageAndInvalidationsAreNotConsumedBeforeOwnershipIsReady() throws Exception {
        var instructions = method("dev/starfold/render/GroundRenderer", "prepareSurfaceUpdatesImpl").instructions;
        var calls = new ArrayList<MethodInsnNode>();
        for (var instruction : instructions)
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        var readiness = calls.stream()
                .filter(c -> c.owner.equals("dev/starfold/render/GroundViewClient")
                        && c.name.equals("coverageReady"))
                .toList();
        assertEquals(1, readiness.size());
        var ready = readiness.getFirst();
        var branch = assertInstanceOf(JumpInsnNode.class, nextCode(ready));
        assertTrue(branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE);
        var rejected = branch.getOpcode() == Opcodes.IFEQ
                ? nextCode(branch.label) : nextCode(branch);
        while (rejected instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO)
            rejected = nextCode(jump.label);
        assertNotNull(rejected);
        while (rejected.getOpcode() != Opcodes.RETURN) {
            if (rejected instanceof MethodInsnNode call)
                assertEquals("dev/starfold/api/RenderWorkDiagnostics", call.owner,
                        "only diagnostic accounting may run on the rejected branch");
            assertFalse(rejected instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.PUTSTATIC || field.getOpcode() == Opcodes.PUTFIELD));
            assertFalse(rejected instanceof JumpInsnNode,
                    "unowned coverage must return without rejoining the update pipeline");
            rejected = nextCode(rejected);
            assertNotNull(rejected);
        }

        for (String effect : List.of("focus", "drainInvalidated", "pollBase", "invalidate", "invalidateTiles", "requestTargetRefresh", "updateChangedTargets")) {
            var consumers = calls.stream().filter(c -> c.name.equals(effect)).toList();
            assertFalse(consumers.isEmpty(), effect + " remains part of the update pipeline");
            for (var consumer : consumers)
                assertTrue(instructions.indexOf(ready) < instructions.indexOf(consumer), effect);
        }
        for (var instruction : instructions) {
            if (instruction == ready) break;
            assertFalse(instruction instanceof FieldInsnNode field
                            && (field.getOpcode() == Opcodes.PUTSTATIC || field.getOpcode() == Opcodes.PUTFIELD),
                    "ownership guards precede frame/selection mutations");
        }
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do instruction = instruction.getNext();
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    @Test
    void theLargePlanetsLastTileHaloCannotBeReinterpretedOnTheSmallPlanet() {
        var big = new PlanetShape(2048);
        var small = new PlanetShape(1024);
        var cell = new CubeSurfaceTopology.Cell(CubeSurfaceTopology.Face.TOP, 2048, 0, 1024);
        assertDoesNotThrow(() -> big.topology().canonical(cell));
        assertThrows(IllegalArgumentException.class, () -> small.topology().canonical(cell));
    }
}
