package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned U678xqle mass API and native upload ordering, also audited against the packaged dependency. */
class SableMassContractTest {
    private static final String ROOT = "dev/ryanhcode/sable/";
    private static final String HULL = ROOT + "sublevel/ServerSubLevel";
    private static final String TRACKER = ROOT + "api/physics/mass/MassTracker";

    private static ClassNode read(String name) throws Exception {
        byte[] bytes;
        var archive = System.getProperty("starfold.auditSable");
        if (archive != null) {
            try (var zip = new java.util.zip.ZipFile(archive)) {
                var entry = zip.getEntry(name + ".class");
                assertNotNull(entry, name);
                try (var in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
            }
        } else {
            try (var in = TestArchiveResources.open(name + ".class")) {
                assertNotNull(in, name);
                bytes = in.readAllBytes();
            }
        }
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(String owner, String name, String desc) throws Exception {
        var method = read(owner).methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
        assertNotEquals(0, method.access & Opcodes.ACC_PUBLIC, owner + "." + name);
        return method;
    }

    private static int call(MethodNode method, String owner, String name, String desc) {
        for (var i : method.instructions)
            if (i instanceof MethodInsnNode c && c.owner.equals(owner)
                    && c.name.equals(name) && c.desc.equals(desc)) return method.instructions.indexOf(i);
        throw new AssertionError(method.name + " -> " + owner + "." + name + desc);
    }

    @Test
    void publicIncrementalApiAndMergedUploadMatchThePinnedJar() throws Exception {
        method(HULL, "getSelfMassTracker", "()L" + TRACKER + ";");
        var increment = method(TRACKER, "addBlockMass", "(Lnet/minecraft/world/level/BlockGetter;"
                + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
                + "DLnet/minecraft/world/phys/Vec3;)V");
        call(increment, TRACKER, "moveCenterOfMass", "(Lorg/joml/Vector3d;)V");
        var merge = method(HULL, "updateMergedMassData", "(F)V");
        var merged = ROOT + "api/physics/mass/MergedMassTracker";
        call(merge, merged, "update", "(F)V");
        var upload = read(merged).methods.stream().filter(m -> m.name.equals("uploadData")).findFirst().orElseThrow();
        call(upload, ROOT + "api/physics/PhysicsPipeline", "onStatsChanged", "(L" + HULL + ";)V");
    }

    @Test
    void nativeLoadMergesFuelBeforeRestoringSavedPoseAndPhysicsMergesBeforeActors() throws Exception {
        var physics = read(ROOT + "sublevel/system/SubLevelPhysicsSystem").methods.stream()
                .filter(m -> m.name.equals("tickPipelinePhysics")).findFirst().orElseThrow();
        int merge = call(physics, HULL, "updateMergedMassData", "(F)V");
        assertTrue(merge < call(physics, HULL, "prePhysicsTick", "(L" + ROOT
                + "sublevel/system/SubLevelPhysicsSystem;L" + ROOT + "api/physics/handle/RigidBodyHandle;D)V"));
        var plot = method(ROOT + "sublevel/plot/ServerLevelPlot", "load", "(Lnet/minecraft/nbt/CompoundTag;)V");
        call(plot, HULL, "updateMergedMassData", "(F)V");
        var serializer = method(ROOT + "sublevel/storage/serialization/SubLevelSerializer", "fullyLoad",
                "(Lnet/minecraft/server/level/ServerLevel;L" + ROOT + "sublevel/storage/serialization/SubLevelData;)L" + HULL + ";");
        assertTrue(call(serializer, ROOT + "sublevel/plot/ServerLevelPlot", "load", "(Lnet/minecraft/nbt/CompoundTag;)V")
                < call(serializer, ROOT + "companion/math/Pose3d", "set", "(L" + ROOT + "companion/math/Pose3dc;)L" + ROOT + "companion/math/Pose3d;"));
    }
}
