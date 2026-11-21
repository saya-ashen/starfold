package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

class OptionalMiningProbeTest {
    private static final String MOD_LIST = "net/neoforged/fml/ModList";
    private static final Set<String> INTEGRATIONS =
            Set.of(
                    "dev/starfold/dev/DevMiningProbe",
                    "dev/starfold/dev/DevOilProbe",
                    "dev/starfold/dev/DevMiningGuideProbe");

    @Test
    void outerGuideHasNoTypedMiningDependencies() throws Exception {
        // Remapping visits type instructions, descriptors, signatures, handles and frame types.
        reader("DevGuideProbe")
                .accept(
                        new ClassRemapper(
                                new ClassWriter(0),
                                new Remapper() {
                                    @Override
                                    public String map(String name) {
                                        assertFalse(name.startsWith("dev/starfold/mining/"), name);
                                        return name;
                                    }
                                }),
                        0);
    }

    @Test
    void miningEntryPointsAreUnreachableWithoutTheModule() throws Exception {
        var reached = new HashSet<String>();
        for (String name : new String[] {"DevBoardingProbe", "DevGuideProbe"}) {
            var node = new ClassNode();
            reader(name).accept(node, 0);
            for (var method : node.methods) checkPaths(method, reached);
        }
        assertEquals(
                Set.of(
                        "dev/starfold/dev/DevMiningProbe.initialize",
                        "dev/starfold/dev/DevOilProbe.initialize",
                        "dev/starfold/dev/DevMiningGuideProbe.console",
                        "dev/starfold/dev/DevMiningGuideProbe.menuValue"),
                reached);
    }

    private record Path(int instruction, boolean miningLoaded) {}

    /** Explore both sides of ordinary branches, retaining the actual ModList guard's result. */
    private static void checkPaths(MethodNode method, Set<String> reached) {
        if (method.instructions.size() == 0) return;
        var pending = new ArrayDeque<Path>();
        var visited = new HashSet<Path>();
        pending.add(new Path(0, false));
        while (!pending.isEmpty()) {
            var path = pending.removeFirst();
            if (path.instruction >= method.instructions.size() || !visited.add(path)) continue;
            var instruction = method.instructions.get(path.instruction);
            if (instruction instanceof MethodInsnNode call && INTEGRATIONS.contains(call.owner)) {
                assertTrue(
                        path.miningLoaded,
                        method.name
                                + " can reach "
                                + call.owner
                                + "."
                                + call.name
                                + " without Mining");
                reached.add(call.owner + "." + call.name);
            }
            int opcode = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode jump) {
                boolean guarded = miningGuard(jump);
                assertFalse(
                        guarded && opcode != Opcodes.IFEQ && opcode != Opcodes.IFNE,
                        "Unexpected ModList boolean branch");
                pending.add(
                        new Path(
                                method.instructions.indexOf(jump.label),
                                guarded ? opcode == Opcodes.IFNE : path.miningLoaded));
                if (opcode == Opcodes.GOTO) continue;
                pending.add(
                        new Path(
                                path.instruction + 1,
                                guarded ? opcode == Opcodes.IFEQ : path.miningLoaded));
            } else if (instruction instanceof TableSwitchInsnNode select) {
                pending.add(new Path(method.instructions.indexOf(select.dflt), path.miningLoaded));
                for (var label : select.labels)
                    pending.add(new Path(method.instructions.indexOf(label), path.miningLoaded));
            } else if (instruction instanceof LookupSwitchInsnNode select) {
                pending.add(new Path(method.instructions.indexOf(select.dflt), path.miningLoaded));
                for (var label : select.labels)
                    pending.add(new Path(method.instructions.indexOf(label), path.miningLoaded));
            } else if (opcode != Opcodes.ATHROW
                    && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) {
                pending.add(new Path(path.instruction + 1, path.miningLoaded));
            }
        }
    }

    private static boolean miningGuard(AbstractInsnNode branch) {
        var previous = previousCode(branch);
        if (!(previous instanceof MethodInsnNode call)
                || !call.owner.equals(MOD_LIST)
                || !call.name.equals("isLoaded")
                || !call.desc.equals("(Ljava/lang/String;)Z")) return false;
        return previousCode(call) instanceof LdcInsnNode constant
                && constant.cst.equals("starfold_mining");
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static ClassReader reader(String name) throws Exception {
        try (var input = OptionalMiningProbeTest.class.getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            return new ClassReader(input);
        }
    }
}
