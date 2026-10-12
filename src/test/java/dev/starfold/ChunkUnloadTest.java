package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetEcologyTest;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;

import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.*;
import java.util.*;

class ChunkUnloadTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private static ChunkHolder holder() {
        return new ChunkHolder(
                new ChunkPos(244, 66), 45, LevelHeightAccessor.create(-64, 384), null, null, null);
    }

    private static ClassNode mixin() throws Exception {
        try (var in = TestArchiveResources.open("/dev/starfold/mixin/ChunkUnloadMixin.class")) {
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    /** Execute the compiled handler without asking Mixin to load its own mixin class. Only the class name,
     * visibility and annotations change; the identity comparison and cancel branch are the production bytecode. */
    private static Object harness(Long2ObjectLinkedOpenHashMap<ChunkHolder> pending)
            throws Exception {
        var node = mixin();
        String original = node.name;
        node.name = "dev/starfold/UnloadGuardHarness";
        node.access &= ~Opcodes.ACC_ABSTRACT;
        node.visibleAnnotations = null;
        node.invisibleAnnotations = null;
        for (var field : node.fields) {
            field.access = Opcodes.ACC_PUBLIC;
            field.visibleAnnotations = null;
            field.invisibleAnnotations = null;
        }
        for (var method : node.methods) {
            method.access = Opcodes.ACC_PUBLIC;
            method.visibleAnnotations = null;
            method.invisibleAnnotations = null;
            for (var instruction : method.instructions)
                if (instruction instanceof FieldInsnNode field && field.owner.equals(original))
                    field.owner = node.name;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        class Loader extends ClassLoader {
            Loader() {
                super(ChunkUnloadTest.class.getClassLoader());
            }

            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }
        var type = new Loader().define();
        var guard = type.getConstructor().newInstance();
        type.getField("pendingUnloads").set(guard, pending);
        return guard;
    }

    private static boolean cancelled(Object guard, long key, ChunkHolder holder) throws Exception {
        var ci = new CallbackInfo("scheduleUnload", true);
        guard.getClass()
                .getMethod(
                        "starfold$currentUnload", long.class, ChunkHolder.class, CallbackInfo.class)
                .invoke(guard, key, holder, ci);
        return ci.isCancelled();
    }

    @Test
    void obsoleteCallbacksCannotWaitOnARevivedOrReplacementHolder() throws Exception {
        var pending = new Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        var guard = harness(pending);
        var old = holder();
        long key = old.getPos().toLong();
        old.increaseGenerationRefCount();
        assertTrue(old.getSaveSyncFuture().isDone());
        assertFalse(old.isReadyForSaving());
        assertTrue(cancelled(guard, key, old));
        var replacement = holder();
        pending.put(key, replacement);
        assertTrue(cancelled(guard, key, old));
        assertSame(replacement, pending.get(key));
        assertEquals(1, old.getGenerationRefCount());
        assertTrue(old.getSaveSyncFuture().isDone());
    }

    @Test
    void currentUnloadsKeepTheirReadinessAndSaveWork() throws Exception {
        var pending = new Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        var guard = harness(pending);
        var current = holder();
        long key = current.getPos().toLong();
        pending.put(key, current);
        current.increaseGenerationRefCount();
        assertFalse(cancelled(guard, key, current));
        assertFalse(current.isReadyForSaving());
        assertSame(current, pending.get(key));
        current.decreaseGenerationRefCount();
        assertTrue(current.isReadyForSaving());
        assertFalse(cancelled(guard, key, current));
        assertSame(current, pending.get(key));
    }

    /** Vanilla's old completed-future callback runs after revival. Dropping only its obsolete retry lets
     * the server return to the main queue; a later genuine unload still saves that holder. */
    @Test
    void revivedCallbackDrainsBeforeMainThreadGenerationAndLaterSave() throws Exception {
        var pending = new Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        var guard = harness(pending);
        var current = holder();
        long key = current.getPos().toLong();
        var queue = new ArrayDeque<Runnable>();
        var saves = new ArrayList<ChunkHolder>();
        class Unloads {
            void schedule() {
                try {
                    if (cancelled(guard, key, current)) return;
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                current.getSaveSyncFuture()
                        .thenRunAsync(
                                () -> {
                                    if (!current.isReadyForSaving()) schedule();
                                    else if (pending.remove(key, current)) saves.add(current);
                                },
                                queue::add);
            }
        }
        var unloads = new Unloads();
        pending.put(key, current);
        unloads.schedule();
        assertEquals(1, queue.size());
        pending.remove(key);
        current.increaseGenerationRefCount();
        queue.remove().run();
        assertTrue(queue.isEmpty());
        assertTrue(saves.isEmpty());
        current.decreaseGenerationRefCount();
        pending.put(key, current);
        unloads.schedule();
        queue.remove().run();
        assertTrue(queue.isEmpty());
        assertTrue(pending.isEmpty());
        assertEquals(List.of(current), saves);
    }

    @Test
    void guardIsOnlyAtUnloadSchedulingAndDoesNotOverrideWorkOrReadiness() throws Exception {
        var node = mixin();
        var method =
                node.methods.stream()
                        .filter(m -> m.name.equals("starfold$currentUnload"))
                        .findFirst()
                        .orElseThrow();
        var inject =
                method.visibleAnnotations.stream()
                        .filter(a -> a.desc.endsWith("/Inject;"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                List.of("scheduleUnload"), inject.values.get(inject.values.indexOf("method") + 1));
        assertEquals(true, inject.values.get(inject.values.indexOf("cancellable") + 1));
        var at =
                (AnnotationNode)
                        ((List<?>) inject.values.get(inject.values.indexOf("at") + 1)).getFirst();
        assertEquals("HEAD", at.values.get(at.values.indexOf("value") + 1));
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) calls.add(call.name);
        assertEquals(List.of("get", "cancel"), calls);
        assertTrue(
                Files.readString(Path.of("src/main/resources/starfold.mixins.json"))
                        .contains("\"ChunkUnloadMixin\""));
    }
}
