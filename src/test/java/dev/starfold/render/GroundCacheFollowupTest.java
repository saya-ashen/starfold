package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class GroundCacheFollowupTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @AfterEach
    void clear() {
        GroundBaseCache.clear();
    }

    private static void awaitTerrainLock(Thread worker, PlanetTerrain terrain) {
        var threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var info = threads.getThreadInfo(worker.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
                assertEquals(
                        System.identityHashCode(terrain),
                        info.getLockInfo().getIdentityHashCode(),
                        "fine misses must reach terrain evaluation without the Entry monitor");
                return;
            }
            Thread.onSpinWait();
        }
        fail("fine miss did not reach terrain evaluation");
    }

    @Test
    void concurrentFineMissesLeaveHitsAndAccountingAvailableAndInsertOnlyOnce() throws Exception {
        var terrain = GroundFarReadinessTest.terrain();
        var entry = new GroundBaseCache.Entry(terrain);
        var hit = entry.fine(Face.TOP, 100, 100);
        long before = entry.fineBytes();
        var planField = PlanetTerrain.class.getDeclaredField("terrainPlan");
        planField.setAccessible(true);
        var plan = planField.get(terrain);
        var firstThread = new AtomicReference<Thread>();
        var secondThread = new AtomicReference<Thread>();
        var started = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(3)) {
            Future<VoxelColumn> first;
            Future<VoxelColumn> second;
            // Force both evaluations to wait at the lazy terrain-plan monitor. Restore the plan
            // before releasing it, so neither evaluation regenerates or changes terrain inputs.
            synchronized (terrain) {
                planField.set(terrain, null);
                try {
                    first =
                            workers.submit(
                                    () -> {
                                        firstThread.set(Thread.currentThread());
                                        started.countDown();
                                        return entry.fine(Face.TOP, 101, 100);
                                    });
                    second =
                            workers.submit(
                                    () -> {
                                        secondThread.set(Thread.currentThread());
                                        started.countDown();
                                        return entry.fine(Face.TOP, 101, 100);
                                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    awaitTerrainLock(firstThread.get(), terrain);
                    long waiterDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (secondThread.get().getState() != Thread.State.WAITING
                            && System.nanoTime() < waiterDeadline) Thread.onSpinWait();
                    assertEquals(
                            Thread.State.WAITING,
                            secondThread.get().getState(),
                            "the duplicate miss joins the in-flight column");
                    assertEquals(before, workers.submit(entry::fineBytes).get(5, TimeUnit.SECONDS));
                    assertEquals(
                            hit,
                            workers.submit(() -> entry.fine(Face.TOP, 100, 100))
                                    .get(5, TimeUnit.SECONDS));
                } finally {
                    planField.set(terrain, plan);
                }
            }
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(
                    before + 80 + 8L * first.get().packed().length,
                    entry.fineBytes(),
                    "identical concurrent misses charge one packed column");
            assertTrue(entry.fineBytes() <= GroundBaseCache.Entry.FINE_LIMIT);

        } finally {
            entry.close();
        }
    }

    private static BitSet consumers(PlanetTerrain terrain, Collection<Integer> publications) {
        var expected = new BitSet();
        for (int p : publications) {
            int u = p % 32 * 64, v = p % 1024 / 32 * 64;
            var face = Face.VALUES[p / 1024];
            for (int[] offset : new int[][] {{0, 0}, {-1, 0}, {64, 0}, {0, -1}, {0, 64}}) {
                var c =
                        terrain.shape()
                                .topology()
                                .canonical(new Cell(face, u + offset[0], 0, v + offset[1]))
                                .cell();
                expected.set(c.face().ordinal() * 64 + c.u() / 256 + 8 * (c.v() / 256));
            }
        }
        return expected;
    }

    @Test
    void publicationCursorPreservesIndependentLateAndResetConsumersWithAllEdgeHalos()
            throws Exception {
        var terrain = GroundFarReadinessTest.terrain();
        var base = GroundFarReadinessTest.install(terrain);
        var first = new GroundMipCache();
        var second = new GroundMipCache();
        first.initialField(terrain);
        second.initialField(terrain);
        var history = new ArrayList<Integer>();
        for (var face : Face.VALUES) {
            for (int v = 0; v < terrain.shape().size(); v += 64) {
                for (int u = 0; u < terrain.shape().size(); u += 64) {
                    int patch = face.ordinal() * 1024 + u / 64 + v / 64 * 32;
                    history.add(patch);
                    base.ready.set(patch, 1); // Direct fixture writes must publish events too.
                    var expected = consumers(terrain, List.of(patch));
                    assertEquals(expected, first.poll(), "publication " + patch);
                    assertTrue(first.poll().isEmpty());
                    assertTrue(first.polledOverlays().isEmpty());
                    base.ready.set(patch, 1);
                    assertTrue(first.poll().isEmpty(), "duplicate writes must not republish");
                }
            }
        }
        assertEquals(history.size(), base.ready.publicationCount());
        assertEquals(consumers(terrain, history), second.poll());
        assertTrue(second.poll().isEmpty());
        var late = new GroundMipCache();
        late.initialField(terrain);
        assertEquals(consumers(terrain, history), late.poll());
        assertTrue(late.poll().isEmpty());
        first.clear();
        first.initialField(terrain);
        assertEquals(consumers(terrain, history), first.poll());
        var replacement = GroundFarReadinessTest.install(terrain);
        replacement.ready.set(33, 1);
        first.initialField(terrain);
        assertEquals(consumers(terrain, List.of(33)), first.poll());
        assertTrue(first.poll().isEmpty());
        assertEquals(1, replacement.ready.get(33));
        assertEquals(6144, replacement.ready.length());
    }

    @Test
    void overlayChangesArePolledEvenWithoutNewBasePublications() throws Exception {
        var terrain = GroundFarReadinessTest.terrain();
        var base = GroundFarReadinessTest.install(terrain);
        var cache = new GroundMipCache();
        var captures = new HashMap<Long, GroundSurface>();
        cache.field(terrain.shape(), terrain, captures, 0, 0);
        assertTrue(cache.poll().isEmpty());
        var gold = GroundSurfaceMipTest.image(200, Blocks.GOLD_BLOCK.defaultBlockState());
        captures.put(0L, gold);
        base.ready.set(33, 1);
        cache.field(terrain.shape(), terrain, captures, 1, 0);
        assertEquals(consumers(terrain, List.of(33)), cache.poll());
        assertEquals(BitSet.valueOf(new long[] {1}), cache.polledOverlays());
        assertTrue(cache.poll().isEmpty());
        captures.clear();
        cache.field(terrain.shape(), terrain, captures, 2, 0);
        assertEquals(BitSet.valueOf(new long[] {1}), cache.poll());
        assertEquals(BitSet.valueOf(new long[] {1}), cache.polledOverlays());
        assertTrue(cache.poll().isEmpty());
        assertTrue(cache.polledOverlays().isEmpty());
        assertEquals(1, base.ready.publicationCount());
    }

    @Test
    void concurrentPublishersReleaseCompleteEventsWithoutDestructiveReads() throws Exception {
        var ready = new GroundBaseCache.Entry.Readiness();
        var payload = new int[ready.length()];
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<?>>();
            for (int lane = 0; lane < 4; lane++) {
                int start = lane;
                tasks.add(
                        workers.submit(
                                () -> {
                                    for (int p = start; p < ready.length(); p += 4) {
                                        payload[p] = p + 1;
                                        ready.set(p, 1);
                                        ready.set(p, 1);
                                    }
                                }));
            }
            int cursor = 0;
            var seen = new BitSet();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cursor < ready.length() && System.nanoTime() < deadline) {
                int count = ready.publicationCount();
                while (cursor < count) {
                    int p = ready.publication(cursor++);
                    assertFalse(seen.get(p));
                    seen.set(p);
                    assertEquals(1, ready.get(p));
                    assertEquals(p + 1, payload[p], "published event must release patch data");
                }
                Thread.onSpinWait();
            }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(ready.length(), cursor);
            assertEquals(ready.length(), seen.cardinality());
            assertEquals(ready.length(), ready.publicationCount());
            var replay = new BitSet();
            for (int i = 0; i < ready.publicationCount(); i++) replay.set(ready.publication(i));
            assertEquals(seen, replay);
        }
    }
}
