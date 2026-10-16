package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Reads the selected Sable jar, including in the packaged audit. No game startup. */
class SablePersistenceContractTest {
    private static final String ROOT = "dev/ryanhcode/sable/";
    private static final String MAP = ROOT + "sublevel/storage/holding/SubLevelHoldingChunkMap";
    private static final String STORAGE = ROOT + "sublevel/storage/serialization/SubLevelStorage";
    private static final String SERIALIZER =
            ROOT + "sublevel/storage/serialization/SubLevelSerializer";
    private static final String HULL = "L" + ROOT + "sublevel/ServerSubLevel;";
    private static final String DATA = "L" + ROOT + "sublevel/storage/serialization/SubLevelData;";
    private static final String POINTER =
            "L" + ROOT + "sublevel/storage/holding/GlobalSavedSubLevelPointer;";
    private static final String CHUNK = "Lnet/minecraft/world/level/ChunkPos;";
    private static final String MOVE = "(" + HULL + DATA + POINTER + CHUNK + ")" + POINTER;
    private static final String TO_DATA = "(" + HULL + "Ljava/util/List;)" + DATA;

    private static ClassNode read(String name) throws Exception {
        var archive = System.getProperty("starfold.auditSable");
        byte[] bytes;
        if (archive != null) {
            try (var zip = new java.util.zip.ZipFile(archive)) {
                var entry = zip.getEntry(name + ".class");
                assertNotNull(entry, name);
                try (var in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
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
        return read(owner).methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(owner + "." + name + desc));
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        var calls = new ArrayList<MethodInsnNode>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode call) calls.add(call);
        return calls;
    }

    private static MethodInsnNode only(MethodNode method, String owner, String name, String desc) {
        var matches =
                calls(method).stream()
                        .filter(
                                c ->
                                        c.owner.equals(owner)
                                                && c.name.equals(name)
                                                && c.desc.equals(desc))
                        .toList();
        assertEquals(1, matches.size(), method.name + " -> " + owner + "." + name + desc);
        return matches.getFirst();
    }

    @Test
    void bothLiveAndNullLiveHoldingSavesUseThePinnedOverload() throws Exception {
        var save = method(MAP, "saveAll", "()V");
        var live = method(MAP, "moveAndSaveSubLevel", "(" + HULL + CHUNK + "Ljava/util/List;)V");
        var serialized = only(live, SERIALIZER, "toData", TO_DATA);
        var moved = only(live, MAP, "moveAndSaveSubLevel", MOVE);
        var setLive =
                only(
                        live,
                        ROOT + "sublevel/ServerSubLevel",
                        "setLastSerializationPointer",
                        "(" + POINTER + ")V");
        assertTrue(live.instructions.indexOf(serialized) < live.instructions.indexOf(moved));
        assertTrue(live.instructions.indexOf(moved) < live.instructions.indexOf(setLive));
        var holding = only(save, MAP, "moveAndSaveSubLevel", MOVE);
        var setHolding =
                only(
                        save,
                        ROOT + "sublevel/storage/HoldingSubLevel",
                        "setPointer",
                        "(" + POINTER + ")V");
        assertTrue(save.instructions.indexOf(holding) < save.instructions.indexOf(setHolding));
        // The null live argument is deliberate, not a live-only setter path.
        int before = save.instructions.indexOf(holding);
        boolean nullLive = false;
        for (int i = before - 12; i < before; i++)
            nullLive |= save.instructions.get(i).getOpcode() == Opcodes.ACONST_NULL;
        assertTrue(nullLive);
        method(MAP, "moveAndSaveSubLevel", MOVE);
        method(
                STORAGE,
                "attemptLoadSubLevel",
                "("
                        + CHUNK
                        + "L"
                        + ROOT
                        + "sublevel/storage/holding/SavedSubLevelPointer;)"
                        + DATA);
    }

    @Test
    void sameAddressWriteCanFailWithoutNullMapReturn() throws Exception {
        var move = method(MAP, "moveAndSaveSubLevel", MOVE);
        var write =
                calls(move).stream()
                        .filter(
                                c ->
                                        c.owner.equals(STORAGE)
                                                && c.name.equals("attemptSaveSubLevel")
                                                && c.desc.equals("(" + POINTER + DATA + ")V"))
                        .toList();
        assertEquals(2, write.size(), "same-address write and old-payload deletion");
        only(move, STORAGE, "attemptSaveSubLevel", "(" + CHUNK + DATA + ")" + POINTER);
        var overwrite = method(STORAGE, "attemptSaveSubLevel", "(" + POINTER + DATA + ")V");
        var fileWrite =
                only(
                        overwrite,
                        ROOT + "sublevel/storage/region/SubLevelStorageFile",
                        "write",
                        "(ILnet/minecraft/nbt/CompoundTag;)V");
        assertTrue(
                overwrite.tryCatchBlocks.stream()
                        .anyMatch(
                                b ->
                                        b.type.equals("java/io/IOException")
                                                && overwrite.instructions.indexOf(b.start)
                                                        < overwrite.instructions.indexOf(fileWrite)
                                                && overwrite.instructions.indexOf(fileWrite)
                                                        < overwrite.instructions.indexOf(b.end)));
        assertEquals(
                1,
                Arrays.stream(overwrite.instructions.toArray())
                        .filter(i -> i.getOpcode() == Opcodes.RETURN)
                        .count());
        assertEquals(
                3,
                Arrays.stream(move.instructions.toArray())
                        .filter(i -> i.getOpcode() == Opcodes.ARETURN)
                        .count());
        var allocate = method(STORAGE, "attemptSaveSubLevel", "(" + CHUNK + DATA + ")" + POINTER);
        only(
                allocate,
                ROOT + "sublevel/storage/region/SubLevelStorageFile",
                "write",
                "(ILnet/minecraft/nbt/CompoundTag;)V");
        assertTrue(
                allocate.tryCatchBlocks.stream()
                        .anyMatch(b -> b.type.equals("java/io/IOException")));
    }

    @Test
    void unloadRoutesSerializeBeforeRemovalButCloseDoesNot() throws Exception {
        for (var entry :
                List.of(
                        Map.entry("moveToUnloaded", "(" + HULL + CHUNK + ")V"),
                        Map.entry("processUnload", "(" + CHUNK + "Ljava/util/Collection;)V"))) {
            var unload = method(MAP, entry.getKey(), entry.getValue());
            var serialized = only(unload, SERIALIZER, "toData", TO_DATA);
            var remove =
                    calls(unload).stream()
                            .filter(c -> c.name.equals("removeSubLevel"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    unload.instructions.indexOf(serialized) < unload.instructions.indexOf(remove));
        }
        var close = method(ROOT + "api/sublevel/ServerSubLevelContainer", "close", "()V");
        assertFalse(calls(close).stream().anyMatch(c -> c.owner.equals(SERIALIZER)));
        only(
                method(SERIALIZER, "toData", TO_DATA),
                SERIALIZER,
                "serialize",
                "(" + HULL + "Ljava/util/List;)Lnet/minecraft/nbt/CompoundTag;");
        only(
                method(
                        SERIALIZER,
                        "fullyLoad",
                        "(Lnet/minecraft/server/level/ServerLevel;" + DATA + ")" + HULL),
                ROOT + "util/SableNBTUtils",
                "readPose3d",
                "(Lnet/minecraft/nbt/CompoundTag;)Ldev/ryanhcode/sable/companion/math/Pose3d;");
    }

    @Test
    void plotEntitiesUseNativeSectionVisibilityAndAreNotDeletedOnUnload() throws Exception {
        var plot = ROOT + "sublevel/plot/ServerLevelPlot";
        var manager = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
        var status =
                "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V";
        for (var entry :
                List.of(
                        Map.entry(
                                method(
                                        plot,
                                        "onRemoveChunkHolder",
                                        "(Lnet/minecraft/world/level/chunk/LevelChunk;)V"),
                                "INACCESSIBLE"),
                        Map.entry(
                                method(
                                        plot,
                                        "addChunkHolder",
                                        "("
                                                + CHUNK
                                                + "L"
                                                + ROOT
                                                + "sublevel/plot/PlotChunkHolder;Z)V"),
                                "ENTITY_TICKING"))) {
            var m = entry.getKey();
            var update = only(m, manager, "updateChunkStatus", status);
            assertTrue(
                    Arrays.stream(m.instructions.toArray())
                            .anyMatch(
                                    i ->
                                            i instanceof FieldInsnNode field
                                                    && field.owner.equals(
                                                            "net/minecraft/server/level/FullChunkStatus")
                                                    && field.name.equals(entry.getValue())
                                                    && m.instructions.indexOf(field)
                                                            < m.instructions.indexOf(update)));
        }
        var remove =
                method(
                        ROOT + "api/sublevel/ServerSubLevelContainer",
                        "removeSubLevel",
                        "(IIL" + ROOT + "sublevel/storage/SubLevelRemovalReason;)V");
        var delete = only(remove, ROOT + "sublevel/ServerSubLevel", "deleteAllEntities", "()V");
        assertTrue(
                Arrays.stream(remove.instructions.toArray())
                        .anyMatch(
                                i ->
                                        i instanceof JumpInsnNode jump
                                                && jump.getOpcode() == Opcodes.IF_ACMPNE
                                                && remove.instructions.indexOf(jump)
                                                        < remove.instructions.indexOf(delete)
                                                && remove.instructions.indexOf(jump.label)
                                                        > remove.instructions.indexOf(delete)));
        assertTrue(
                Arrays.stream(remove.instructions.toArray())
                        .anyMatch(
                                i ->
                                        i instanceof FieldInsnNode field
                                                && field.name.equals("REMOVED")
                                                && remove.instructions.indexOf(field)
                                                        < remove.instructions.indexOf(delete)));
    }

    @Test
    void controlledWakeRewritesBeforeAllocationAndSnatchBypassesOldReadiness() throws Exception {
        var load =
                method(
                        MAP,
                        "loadHoldingSubLevel",
                        "(L" + ROOT + "sublevel/storage/HoldingSubLevel;)V");
        var restore =
                only(
                        load,
                        SERIALIZER,
                        "fullyLoad",
                        "(Lnet/minecraft/server/level/ServerLevel;" + DATA + ")" + HULL);
        var pointer =
                only(
                        load,
                        ROOT + "sublevel/ServerSubLevel",
                        "setLastSerializationPointer",
                        "(" + POINTER + ")V");
        assertTrue(load.instructions.indexOf(restore) < load.instructions.indexOf(pointer));
        var snatch = method(MAP, "snatchAndLoad", "(" + POINTER + "Ljava/util/UUID;)V");
        only(
                snatch,
                MAP,
                "loadHoldingSubLevel",
                "(L" + ROOT + "sublevel/storage/HoldingSubLevel;)V");
        assertFalse(calls(snatch).stream().anyMatch(c -> c.name.equals("canLoadSubLevel")));
        var ready =
                method(
                        ROOT + "sublevel/storage/holding/SubLevelHoldingChunk",
                        "canLoadSubLevel",
                        "(Lnet/minecraft/server/level/ServerLevel;" + DATA + ")Z");
        only(
                ready,
                ROOT + "sublevel/storage/serialization/SubLevelData",
                "bounds",
                "()L" + ROOT + "companion/math/BoundingBox3d;");
        assertTrue(calls(ready).stream().anyMatch(c -> c.name.equals("isChunkLoadedEnough")));
        method(
                ROOT + "sublevel/system/ticket/PhysicsChunkTicketManager",
                "isChunkLoadedEnough",
                "(Lnet/minecraft/server/level/ServerLevel;II)Z");
        var fully =
                method(
                        SERIALIZER,
                        "fullyLoad",
                        "(Lnet/minecraft/server/level/ServerLevel;" + DATA + ")" + HULL);
        var pose =
                only(
                        fully,
                        ROOT + "util/SableNBTUtils",
                        "readPose3d",
                        "(Lnet/minecraft/nbt/CompoundTag;)L" + ROOT + "companion/math/Pose3d;");
        var allocate =
                calls(fully).stream()
                        .filter(c -> c.name.equals("allocateSubLevel"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(fully.instructions.indexOf(pose) < fully.instructions.indexOf(allocate));
        only(fully, ROOT + "sublevel/ServerSubLevel", "forceUpdateGlobalBounds", "()V");
    }

    @Test
    void flushPrecedesSavedTicketDirtyingAndCatchesIoFailure() throws Exception {
        var save = method(MAP, "saveAll", "()V");
        var flush = only(save, STORAGE, "flush", "()V");
        var prune = only(save, STORAGE, "pruneCache", "()V");
        var dirty =
                only(save, ROOT + "sublevel/storage/SubLevelTicketsSavedData", "setDirty", "()V");
        assertTrue(save.instructions.indexOf(flush) < save.instructions.indexOf(prune));
        assertTrue(save.instructions.indexOf(prune) < save.instructions.indexOf(dirty));
        assertTrue(
                save.tryCatchBlocks.stream()
                        .anyMatch(
                                b ->
                                        b.type.equals("java/io/IOException")
                                                && save.instructions.indexOf(b.start)
                                                        < save.instructions.indexOf(flush)
                                                && save.instructions.indexOf(flush)
                                                        < save.instructions.indexOf(b.end)));
    }
}
