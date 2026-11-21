package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

/** Contracts across the renderer, lifecycle and remote fallback, without creating a GL context. */
class GroundQuadtreeIntegrationTest {
    private static ClassNode bytecode(String simpleName) throws Exception {
        try (var in = getClassResource(simpleName)) {
            assertNotNull(in, simpleName);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static java.io.InputStream getClassResource(String simpleName) {
        return GroundQuadtreeIntegrationTest.class.getResourceAsStream(simpleName + ".class");
    }

    private static List<MethodInsnNode> calls(String type, String method) throws Exception {
        var result = new ArrayList<MethodInsnNode>();
        for (var m : bytecode(type).methods)
            if (m.name.equals(method))
                for (var insn : m.instructions)
                    if (insn instanceof MethodInsnNode call) result.add(call);
        assertFalse(result.isEmpty(), type + "." + method);
        return result;
    }

    @Test
    void cullerFramesAreSafelyPublishedAndWorkerAdmissionDoesNotReadMutableViews() throws Exception {
        var renderer = bytecode("GroundRenderer");
        var field = renderer.fields.stream().filter(f -> f.name.equals("cullers")).findFirst().orElseThrow();
        assertNotEquals(0, field.access & org.objectweb.asm.Opcodes.ACC_VOLATILE);
        var update = renderer.methods.stream().filter(m -> m.name.equals("updateCullers")).findFirst().orElseThrow();
        int publish = -1, lastElementStore = -1, publications = 0;
        for (var insn : update.instructions) {
            if (insn.getOpcode() == org.objectweb.asm.Opcodes.AASTORE)
                lastElementStore = update.instructions.indexOf(insn);
            if (insn instanceof FieldInsnNode store && store.name.equals("cullers")
                    && store.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC) {
                publish = update.instructions.indexOf(insn);
                publications++;
            }
        }
        assertEquals(1, publications);
        assertTrue(lastElementStore >= 0 && publish > lastElementStore,
                "publish the completed local array, never an empty shared array");
        for (var method : renderer.methods)
            if (method.name.startsWith("lambda$prepareSurfaceUpdatesImpl$"))
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call)
                        assertFalse(call.owner.equals(renderer.name)
                                        && (call.name.equals("visible") || call.name.equals("current")),
                                "worker callbacks must not inspect mutable frame/tree state");
    }

    @Test
    void admissionReclaimRechecksTicketAndClosesStalePendingBeforeRetry() throws Exception {
        var update = bytecode("GroundRenderer").methods.stream()
                .filter(m -> m.name.equals("updateMeshesImpl")).findFirst().orElseThrow();
        MethodInsnNode reclaim = null;
        for (var insn : update.instructions)
            if (insn instanceof MethodInsnNode call && call.name.equals("evictOffscreenToBudget")) {
                reclaim = call;
                break;
            }
        assertNotNull(reclaim);
        AbstractInsnNode next = reclaim.getNext();
        while (next != null && !(next instanceof MethodInsnNode)) next = next.getNext();
        assertInstanceOf(MethodInsnNode.class, next);
        assertEquals("current", ((MethodInsnNode) next).name,
                "reclaim can invalidate this pending ticket; check it before admission or packing");
        var branch = assertInstanceOf(JumpInsnNode.class, next.getNext());
        assertEquals(org.objectweb.asm.Opcodes.IFNE, branch.getOpcode());
        var staleCalls = new ArrayList<String>();
        boolean skipsPending = false;
        for (next = branch.getNext(); next != branch.label; next = next.getNext()) {
            assertNotNull(next);
            if (next instanceof MethodInsnNode call) staleCalls.add(call.name);
            if (next.getOpcode() == org.objectweb.asm.Opcodes.GOTO) skipsPending = true;
        }
        assertTrue(staleCalls.containsAll(List.of("nodeEvent", "remove", "close")));
        assertFalse(staleCalls.contains("admission") || staleCalls.contains("resolve"));
        assertTrue(skipsPending, "stale pending must leave this iteration before packing/upload");
    }

    @Test
    void workerPackingHasNoClientWorldModelOrGlobalPaletteCallsAndRendererNeverWaits() throws Exception {
        for (var method : bytecode("GroundLodPacking").methods)
            for (var insn : method.instructions)
                if (insn instanceof MethodInsnNode call) {
                    assertFalse(call.owner.startsWith("net/minecraft/client/")
                                    || call.owner.startsWith("com/mojang/blaze3d/")
                                    || call.owner.startsWith("org/lwjgl/"),
                            method.name + " calls " + call.owner + "." + call.name);
                    assertFalse(call.name.equals("registerMaterial"));
                }
        var resolving = calls("GroundRenderer$Pending", "resolve");
        assertTrue(resolving.stream().anyMatch(c -> c.name.equals("captureAppearance")));
        assertTrue(resolving.stream().anyMatch(c -> c.name.equals("surfaceLight")));
        assertTrue(resolving.stream().anyMatch(c -> c.name.equals("finish")
                && c.owner.endsWith("GroundQuadtreeScheduler")));
        assertTrue(resolving.stream().anyMatch(c -> c.name.equals("remap")));
        assertFalse(resolving.stream().anyMatch(c -> c.name.equals("join")
                || c.name.equals("get") && c.owner.contains("Future")
                || c.name.equals("wait") || c.name.equals("putShort")),
                "render thread must neither wait for workers nor construct packed vertices");
    }

    @Test
    void blockedUploadYieldsToNextPendingAndStableCutsUseExactViewIdentity() throws Exception {
        var update = bytecode("GroundRenderer").methods.stream()
                .filter(m -> m.name.equals("updateMeshesImpl")).findFirst().orElseThrow();
        boolean checked = false;
        // Locate the diagnostics counter immediately preceding the upload-block continue.
        for (var insn : update.instructions) {
            if (!(insn instanceof FieldInsnNode field) || !field.name.equals("FAR_LOD_BLOCK_UPLOAD")) continue;
            AbstractInsnNode next = insn;
            while (next != null && !(next instanceof JumpInsnNode)) next = next.getNext();
            var jump = assertInstanceOf(JumpInsnNode.class, next);
            assertEquals(org.objectweb.asm.Opcodes.GOTO, jump.getOpcode());
            assertTrue(update.instructions.indexOf(jump.label) < update.instructions.indexOf(jump),
                    "waiting on one pool continues the pending loop instead of ending it");
            checked = true;
        }
        assertTrue(checked);
        var renderer = bytecode("GroundRenderer");
        for (String name : List.of("draw", "nodeView")) {
            var method = renderer.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                    .anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("exactViewEpoch")),
                    name + " must use the exact camera identity outside orbital windows");
        }
    }

    @Test
    void compactDrawDoesNotIterateModExtendedChunkLayers() throws Exception {
        var renderTypeCalls = calls("GroundRenderer", "draw").stream()
                .filter(c -> c.owner.equals("net/minecraft/client/renderer/RenderType"))
                .map(c -> c.name).collect(java.util.stream.Collectors.toSet());
        assertFalse(renderTypeCalls.contains("chunkBufferLayers"),
                "Aeronautics adds levitite/ghost layers which compact arenas cannot draw");
        assertTrue(renderTypeCalls.containsAll(List.of(
                "solid", "cutoutMipped", "cutout", "translucent", "tripwire")),
                "retain all supported terrain layers and the tripwire connector pass");
    }

    @Test
    void remoteReadinessUsesWholeRootSubtree() throws Exception {
        var calls = calls("GroundRenderer", "remotePatchReady");
        assertTrue(
                calls.stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("/GroundQuadtree")
                                                && c.name.equals("patchReady")));
        assertFalse(
                calls.stream()
                        .anyMatch(c -> c.name.equals("visible") || c.name.equals("selectCut")));
    }

    @Test
    void rendererResetPreservesBaseCacheAndDisconnectClearsIt() throws Exception {
        for (String owner : List.of("GroundRenderer", "GroundMeshSession"))
            for (var m : bytecode(owner).methods)
                for (var insn : m.instructions)
                    if (insn instanceof MethodInsnNode call)
                        assertFalse(
                                call.owner.endsWith("/GroundBaseCache") && call.name.equals("clear"),
                                owner + "." + m.name);
        assertTrue(
                calls("GroundViewClient", "disconnected").stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("/GroundBaseCache")
                                                && c.name.equals("clear")));
        var renderer = calls("GroundRenderer", "clear");
        assertTrue(callIndex(renderer, "GroundMeshSession", "invalidate")
                < callIndex(renderer, "GroundMeshSession", "clear"));
        var meshes = calls("GroundMeshSession", "clear");
        assertTrue(callIndex(meshes, "AtomicBoolean", "set")
                < callIndex(meshes, "GroundQuadtreeScheduler", "reset"));
        assertTrue(callIndex(meshes, "GroundQuadtreeScheduler", "reset")
                < callIndex(meshes, "GroundRenderer$Pending", "close"));
        assertTrue(callIndex(meshes, "GroundRenderer$Pending", "close")
                < callIndex(meshes, "GroundFaceArenas", "close"));
        assertTrue(callIndex(meshes, "GroundRenderer$Bundle", "close")
                < callIndex(meshes, "GroundFaceArenas", "close"));
    }

    private static int callIndex(List<MethodInsnNode> calls, String owner, String method) {
        for (int i = 0; i < calls.size(); i++) {
            var c = calls.get(i);
            if (c.owner.endsWith("/" + owner) && c.name.equals(method)) return i;
        }
        throw new AssertionError(owner + "." + method);
    }

    @Test
    void meshResetRevokesBuildPermitsAndIsolatesLatePayloadAccounting() {
        var meshes = new GroundMeshSession(1);
        var oldArena = meshes.arenas;
        var permit = new java.util.concurrent.atomic.AtomicBoolean(true);
        var ready = meshes.readyBytes;
        var packing = meshes.packingBytes;
        try {
            meshes.buildPermits.put(new GroundQuadtreeScheduler.Ticket(0, 1, 1, 0), permit);
            ready.add(false, 96);
            packing.addAndGet(48);

            meshes.invalidate();
            meshes.clear(0, 0, new java.util.BitSet());

            assertEquals(1, meshes.generation);
            assertFalse(permit.get());
            assertTrue(meshes.buildPermits.isEmpty());
            assertNotSame(oldArena, meshes.arenas);
            assertNotSame(ready, meshes.readyBytes);
            assertNotSame(packing, meshes.packingBytes);
            ready.add(false, -96);
            packing.addAndGet(-48);
            assertEquals(0, meshes.readyBytes.detail());
            assertEquals(0, meshes.packingBytes.get());
        } finally {
            meshes.arenas.close();
            meshes.scheduler.close();
        }
    }

    @Test
    void rendererUsesBudgetedTargetRefreshForExactAndConservativeViews() throws Exception {
        var calls = calls("GroundRenderer", "prepareSurfaceUpdatesImpl");
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("requestTargetRefresh")));
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("updateChangedTargets")
                && c.desc.endsWith("J)V")));
        assertFalse(calls.stream().anyMatch(c -> c.name.equals("updateTargets")));
    }

    @Test
    void actualNativePassRefreshesMaskBeforeDrawingCut() throws Exception {
        var refresh = calls("GroundRenderer", "refreshNativeDraws");
        int visible = -1, mask = -1;
        for (int i = 0; i < refresh.size(); i++) {
            if (refresh.get(i).name.equals("visible")) visible = i;
            if (refresh.get(i).name.equals("updateNativeMask")) mask = i;
        }
        assertTrue(visible >= 0 && mask > visible);
        assertFalse(
                calls("GroundRenderer", "prepareSurfaceUpdates").stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("/GroundNativeCoverage")
                                                && c.name.equals("visible")));
    }

    @Test
    void rendererRetainsNoLegacyPipelineStateOrEagerExteriorCopies() throws Exception {
        for (var field : bytecode("GroundRenderer").fields) {
            assertFalse(
                    field.desc.matches(
                            ".*Ground(FarCover|ResidentLod|LodField|Stitches|Ownership|Handoff|Pipeline|CoverageAtlas).*"),
                    field.name);
            assertFalse(
                    field.name.equals("farCover")
                            || field.name.equals("stitching")
                            || field.name.contains("Cohort"));
        }
        for (var field : bytecode("GroundExterior").fields)
            assertFalse(
                    field.desc.contains("VertexBuffer") || field.desc.startsWith("["), field.name);
    }
}
