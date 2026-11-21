package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.Test;

/** Offline work bounds and cache regressions, not a GPU/FPS benchmark. */
class GroundPerformanceTest {
    @Test
    void recoveryReadsDrainedDemandBeforeGroundTargetRefreshClearsIt() throws Exception {
        var type = new org.objectweb.asm.tree.ClassNode();
        try (var in = GroundRenderer.class.getResourceAsStream("GroundRenderer.class")) {
            new org.objectweb.asm.ClassReader(in).accept(type, 0);
        }
        var prepare = type.methods.stream().filter(m -> m.name.equals("prepareSurfaceUpdatesImpl")).findFirst().orElseThrow();
        int recovery = -1, clearDemand = -1, targets = -1;
        for (int i = 0; i < prepare.instructions.size(); i++) {
            var instruction = prepare.instructions.get(i);
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (call.owner.equals("dev/starfold/render/GroundViewPolicy")
                        && call.name.equals("recoveryGroups")) recovery = i;
                if (call.owner.equals("dev/starfold/render/GroundQuadtree")
                        && call.name.equals("updateChangedTargets")) targets = i;
            }
            if (clearDemand < 0 && instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC
                    && field.owner.equals(type.name) && field.name.equals("noDemand")) clearDemand = i;
        }
        assertTrue(recovery >= 0 && recovery < clearDemand && clearDemand < targets,
                "recovery must see the previous demand state before target refresh clears it");
    }

    @Test
    void arenaCopiesParticipateInCatchUpWithoutChangingFrameLimits() throws Exception {
        var method = new org.objectweb.asm.tree.ClassNode();
        try (var in = GroundRenderer.class.getResourceAsStream("GroundRenderer.class")) {
            new org.objectweb.asm.ClassReader(in).accept(method, 0);
        }
        assertTrue(method.methods.stream().filter(m -> m.name.equals("updateMeshesImpl"))
                .flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .anyMatch(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.endsWith("GroundFaceArenas") && call.name.equals("pendingCopyBytes")));
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var copiesOnly = new GroundUploadBudget(Long.MAX_VALUE, 8L << 20, clock::get);
        assertEquals(4L << 20, copiesOnly.byteLimit());
        long started = copiesOnly.startTransfer();
        clock.set(GroundUploadBudget.BURST_NANOS);
        copiesOnly.endTransfer(started);
        assertEquals(1L << 20, copiesOnly.byteLimit(), "copy cost still closes catch-up");
    }

    @Test
    void unchangedSnapshotsReuseUntilLightRefreshAndEditsInvalidateImmediately() {
        assertFalse(GroundViewPolicy.captureNeeded(true, 7, 7, 99, 0));
        assertTrue(GroundViewPolicy.captureNeeded(true, 7, 7, 100, 0));
        assertTrue(GroundViewPolicy.captureNeeded(true, 8, 7, 1, 0));
        assertTrue(GroundViewPolicy.captureNeeded(false, 7, 7, 1, 0));
        assertTrue(GroundViewPolicy.captureNeeded(true, 7, 7, 0, 1));
    }

    @Test
    void recoveryUsesStagingCapacityOnlyAfterPreviousDemandDrains() {
        assertEquals(6, GroundViewPolicy.recoveryGroups(true, 0, 0, 24));
        assertEquals(4, GroundViewPolicy.recoveryGroups(true, 0, 0, 16));
        assertEquals(0, GroundViewPolicy.recoveryGroups(true, 0, 0, 3));
        assertEquals(0, GroundViewPolicy.recoveryGroups(false, 0, 0, 24));
        assertEquals(0, GroundViewPolicy.recoveryGroups(true, 1, 0, 24));
        assertEquals(0, GroundViewPolicy.recoveryGroups(true, 0, 1, 24));
    }

    @Test
    void dirtyTilesCoalesceIntoLocalPatchesAndDoNotRebuildAnEntireFace() {
        for (int face = 0; face < 6; face++)
            assertEquals(face * 64 + 63, GroundViewPolicy.patch(face * 16384L + 16383));
    }

    @Test
    void generatorHeightsMemoizeWithoutGrowingAcrossTheWholePlanet() {
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);
        var cache = new PlanetHeightCache(terrain);
        assertEquals(terrain.surfaceY(Face.TOP, 8, 9), cache.ground(Face.TOP, 8, 9));
        cache.ground(Face.TOP, 8, 9);
        assertEquals(1, cache.samples());
        cache.node(Face.TOP, 0, 0);
        cache.node(Face.TOP, 0, 0);
        assertEquals(2, cache.samples());
        for (int u = 0; u < 2048; u += 16)
            for (int v = 0; v < 2048; v += 16) cache.ground(Face.TOP, u, v);
        assertEquals(128, cache.columns());
        for (int u = 0; u < 200; u++) for (int v = 0; v < 100; v++) cache.node(Face.TOP, u, v);
        assertEquals(16384, cache.nodes());
    }
}
