package dev.starfold.render;

import dev.starfold.worldgen.PlanetTerrainTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GroundSurfaceStoreTest {
    @TempDir Path directory;
    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }

    static final class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;
        public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    @Test void lateLoadDoesNotSendKnownToAnotherBindingAndSaveIsOrdered() throws Exception {
        var io = new QueueExecutor();
        var store = new GroundSurfaceStore(io);
        var known = new AtomicInteger();
        var saved = new AtomicInteger();
        var file = directory.resolve("surface.bin");
        var changed = new BitSet(); changed.set(7);
        var image = GroundSurfaceRunsTest.forest();
        store.bind(new Object());
        store.save(file, Map.of(7L, image), changed, Map.of(), saved::incrementAndGet);
        assertFalse(Files.exists(file), "disk work must wait for the executor");
        store.load(file, changed, images -> known.incrementAndGet());
        store.bind(new Object());
        store.save(file, Map.of(), new BitSet(), Map.of(), saved::incrementAndGet);
        io.runAll(); store.drain();
        assertEquals(0, known.get());
        assertEquals(1, saved.get());
        assertTrue(GroundSurfaceDisk.load(file, changed).isEmpty());
        store.close();
    }

    @Test void logoutBeforeLoadCallbackPreservesUnseenDiskEditsButHonorsTombstones() throws Exception {
        var file = directory.resolve("surface.bin");
        var image = GroundSurfaceRunsTest.forest();
        GroundSurfaceDisk.save(file, Map.of(7L, image, 9L, image));
        var io = new QueueExecutor();
        var store = new GroundSurfaceStore(io);
        var changed = new BitSet(); changed.set(7); changed.set(9);
        store.bind(new Object());
        store.load(file, changed, images -> fail("logout must invalidate Known callback"));
        store.save(file, Map.of(), changed, Map.of(9L, image.revision()), () -> fail("old save callback"));
        store.bind(null);
        io.runAll(); store.drain();
        assertEquals(Set.of(7L), GroundSurfaceDisk.load(file, changed).keySet());
        store.close();
    }

    @Test void delayedLoadMergesAgainstNetworkRevisionsAndCoverageBeforeKnown() throws Exception {
        var file = directory.resolve("merge.bin");
        var disk = new GroundSurface(4, new int[256], new int[256]);
        var network = new GroundSurface(8, new int[256], new int[256]);
        GroundSurfaceDisk.save(file, Map.of(7L, disk, 8L, disk, 9L, disk, 10L, disk));
        var io = new QueueExecutor();
        var store = new GroundSurfaceStore(io);
        var state = new GroundViewState<Object>();
        state.changed.set(7, 11);
        var known = new ArrayList<Map<Long, GroundSurface>>();
        store.bind(new Object());
        store.load(file, state.changed, images -> {
            state.loaded(images);
            known.add(Map.copyOf(state.overlays));
        });
        state.surface(7, 8, network);
        state.surface(8, 8, null);
        var coverage = (BitSet) state.changed.clone();
        coverage.clear(9);
        state.coverage(coverage, 9);
        assertTrue(known.isEmpty());
        io.runAll();
        assertTrue(known.isEmpty(), "Known waits for client-thread publication");
        store.drain();
        assertEquals(1, known.size());
        assertEquals(Set.of(7L, 10L), known.getFirst().keySet());
        assertEquals(8L, state.surfaceRevisions.get(7L));
        assertSame(network, state.overlays.get(7L));
        assertEquals(4, state.overlays.get(10L).revision());
        store.save(file, state.overlays, state.changed, state.surfaceRevisions, () -> {});
        store.bind(null);
        io.runAll(); store.drain();
        var saved = GroundSurfaceDisk.load(file, state.changed);
        assertEquals(Set.of(7L, 10L), saved.keySet());
        assertEquals(8, saved.get(7L).revision());
        store.close();
    }

    @Test void changedOrFailedSaveDoesNotClearDirty() throws Exception {
        var io = new QueueExecutor();
        var store = new GroundSurfaceStore(io);
        store.bind(new Object());
        store.save(directory.resolve("ok.bin"), Map.of(), new BitSet(), Map.of(), () -> fail("changed save"));
        store.changed();
        var obstruction = directory.resolve("file"); Files.writeString(obstruction, "x");
        store.save(obstruction.resolve("bad.bin"), Map.of(), new BitSet(), Map.of(), () -> fail("failed save"));
        io.runAll(); store.drain(); store.close();
    }
}
