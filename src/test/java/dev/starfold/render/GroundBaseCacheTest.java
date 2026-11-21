package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;

import org.junit.jupiter.api.Test;

class GroundBaseCacheTest {
    @Test
    void failedBuildRetriesOnlyToTheBoundAndPublishesItsFinalState() {
        PlanetTerrainTest.registry();
        var entry = new GroundBaseCache.Entry(new PlanetTerrain(42));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        entry.build(
                () -> {
                    if (calls.incrementAndGet() < 2) throw new IllegalStateException("transient");
                    return null;
                });
        assertEquals(2, entry.attempts);
        assertEquals(GroundBaseCache.Entry.State.READY, entry.state);
        assertNull(entry.failure);
        entry.build(
                () -> {
                    throw new IllegalStateException("permanent");
                });
        assertEquals(GroundBaseCache.Entry.MAX_ATTEMPTS, entry.attempts);
        assertEquals(GroundBaseCache.Entry.State.FAILED, entry.state);
        assertEquals("permanent", entry.failure.getMessage());
        entry.close();
        entry.build(() -> fail("closed entries cannot restart"));
        assertEquals(GroundBaseCache.Entry.State.CLOSED, entry.state);
    }

    @Test
    void chartResetKeepsDeterministicBaseUntilDisconnect() throws Exception {
        PlanetTerrainTest.registry();
        var terrain = new PlanetTerrain(42);
        var entry = new GroundBaseCache.Entry(terrain);
        var field = GroundBaseCache.class.getDeclaredField("planets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var planets =
                (java.util.Map<dev.starfold.worldgen.surface.SurfaceKey, GroundBaseCache.Entry>)
                        field.get(null);
        planets.put(dev.starfold.worldgen.surface.SurfaceKey.of(terrain), entry);
        try {
            GroundViewClient.clear();
            assertSame(entry, GroundBaseCache.get(terrain, null));
            assertNotNull(entry.cache);
            GroundBaseCache.clear();
            assertTrue(planets.isEmpty());
            assertNull(entry.cache);
        } finally {
            GroundBaseCache.clear();
        }
    }

    @Test
    void availableWorkersRecheckCameraAndObservedPlanetWithoutDuplicateClaims() throws Exception {
        PlanetTerrainTest.registry();
        var firstTerrain = new PlanetTerrain(42);
        var secondTerrain = new PlanetTerrain(43);
        var first = new GroundBaseCache.Entry(firstTerrain);
        var second = new GroundBaseCache.Entry(secondTerrain);
        first.diskLoaded = second.diskLoaded = true;
        first.focus(Face.TOP.normal.scale(10000));
        second.focus(Face.TOP.normal.scale(20000));
        var field = GroundBaseCache.class.getDeclaredField("planets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var planets =
                (java.util.Map<dev.starfold.worldgen.surface.SurfaceKey, GroundBaseCache.Entry>)
                        field.get(null);
        synchronized (GroundBaseCache.class) {
            planets.put(first.key, first);
            planets.put(second.key, second);
            try {
                var initial = GroundBaseCache.claimWork();
                var parallel = GroundBaseCache.claimWork();
                assertSame(first, initial.entry());
                assertSame(first, parallel.entry());
                assertNotEquals(initial.patch(), parallel.patch());
                assertEquals(Face.TOP.ordinal(), initial.patch() / 1024);
                assertEquals(2, first.claimed.cardinality());
                // Existing entries receive camera changes, including after their initial queueing.
                assertSame(
                        first, GroundBaseCache.get(firstTerrain, Face.BOTTOM.normal.scale(10000)));
                var moved = GroundBaseCache.claimWork();
                assertSame(first, moved.entry());
                assertEquals(Face.BOTTOM.ordinal(), moved.patch() / 1024);
                GroundBaseCache.observe(secondTerrain);
                var transferred = GroundBaseCache.claimWork();
                assertSame(second, transferred.entry());
                assertEquals(Face.TOP.ordinal(), transferred.patch() / 1024);
                second.focus(null);
                assertEquals(
                        Face.TOP.ordinal(),
                        second.nextPatch() / 1024,
                        "map reads do not overwrite renderer focus");
            } finally {
                first.running.clear();
                second.running.clear();
                GroundBaseCache.clear();
            }
        }
    }

    @Test
    void equalPrioritiesKeepOrderAndExhaustedClaimsAllowOtherPlanetsToWork() throws Exception {
        PlanetTerrainTest.registry();
        var first = new GroundBaseCache.Entry(new PlanetTerrain(42));
        var second = new GroundBaseCache.Entry(new PlanetTerrain(43));
        var third = new GroundBaseCache.Entry(new PlanetTerrain(44));
        first.diskLoaded = second.diskLoaded = third.diskLoaded = true;
        first.focus(Face.TOP.normal.scale(10000));
        second.focus(Face.TOP.normal.scale(10000));
        third.focus(Face.TOP.normal.scale(20000));
        // Make every patch equidistant so the key traversal order is observable too.
        java.util.Arrays.fill(first.centers, Face.TOP.normal);
        var field = GroundBaseCache.class.getDeclaredField("planets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var planets =
                (java.util.Map<dev.starfold.worldgen.surface.SurfaceKey, GroundBaseCache.Entry>)
                        field.get(null);
        synchronized (GroundBaseCache.class) {
            GroundBaseCache.clear();
            planets.put(first.key, first);
            planets.put(second.key, second);
            planets.put(third.key, third);
            try {
                assertSame(first, GroundBaseCache.claimWork().entry());
                assertTrue(first.claimed.get(first.patchKeys[0]));
                assertEquals(first.patchKeys[1], GroundBaseCache.claimWork().patch());
                for (int key : first.patchKeys) first.claimed.set(key);
                assertSame(second, GroundBaseCache.claimWork().entry());
                second.loading = true;
                assertSame(third, GroundBaseCache.claimWork().entry());
                // A failed/cancelled claim becomes available again when released.
                int released = first.patchKeys[0];
                first.claimed.clear(released);
                var retry = GroundBaseCache.claimWork();
                assertSame(first, retry.entry());
                assertEquals(released, retry.patch());
            } finally {
                first.running.clear();
                second.running.clear();
                third.running.clear();
                GroundBaseCache.clear();
            }
        }
    }

    @Test
    void cancellationKeepsReplacementBlockedUntilJournalCloseCompletes(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        PlanetTerrainTest.registry();
        var terrain = new PlanetTerrain(42);
        var original = new GroundBaseCache.Entry(terrain);
        var replacement = new GroundBaseCache.Entry(terrain);
        var journal =
                GroundBaseDisk.open(root.resolve("0123456789abcdef-closing.bin"), original.cache);
        original.disk = journal;
        original.state = GroundBaseCache.Entry.State.READY;
        var field = GroundBaseCache.class.getDeclaredField("planets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var planets =
                (java.util.Map<dev.starfold.worldgen.surface.SurfaceKey, GroundBaseCache.Entry>)
                        field.get(null);
        var closeFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var closer =
                new Thread(
                        () -> {
                            try {
                                GroundBaseCache.release(original);
                            } catch (Throwable failure) {
                                closeFailure.set(failure);
                            }
                        });
        try {
            // Hold only the journal monitor: entry cancellation and scheduler queries must proceed.
            synchronized (journal) {
                closer.start();
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (closer.getState() != Thread.State.BLOCKED
                        && closer.isAlive()
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED, closer.getState());
                original.close();
                assertSame(journal, original.disk, "closing journals remain visible to retirement");
                synchronized (GroundBaseCache.class) {
                    planets.put(replacement.key, replacement);
                    var cleanup = GroundBaseCache.claimWork();
                    assertSame(original, cleanup.entry());
                    assertEquals(-2, cleanup.patch());
                    assertNull(
                            GroundBaseCache.claimWork(), "replacement cannot race journal close");
                }
            }
            closer.join(3000);
            assertFalse(closer.isAlive());
            assertNull(closeFailure.get());
            assertNull(original.disk);
            synchronized (GroundBaseCache.class) {
                var next = GroundBaseCache.claimWork();
                assertSame(replacement, next.entry());
                assertEquals(-1, next.patch(), "replacement may now replay persisted progress");
            }
        } finally {
            closer.join(3000);
            replacement.running.clear();
            GroundBaseCache.release(original);
            GroundBaseCache.clear();
            journal.close();
        }
    }

    @Test
    void reducedColumnsShareFineBudgetWithoutAliasingAndRemainExact() {
        PlanetTerrainTest.registry();
        var entry = new GroundBaseCache.Entry(new PlanetTerrain(42));
        try {
            var children = new java.util.ArrayList<dev.starfold.worldgen.lod.VoxelColumn>();
            for (int z = 0; z < 2; z++)
                for (int x = 0; x < 2; x++) children.add(entry.voxel(Face.TOP, 100 + x, 100 + z, 1));
            var expected = dev.starfold.worldgen.lod.VoxelColumn.reduce(children, 2);
            assertEquals(expected, entry.voxel(Face.TOP, 100, 100, 2));
            long fineKey = ((long) Face.TOP.ordinal() * 2048 + 100) * 2048 + 100;
            var packed = entry.fine.get((1L << 25) | fineKey, () -> fail("reduction was retained"));
            long bytes = entry.fineBytes();
            for (int i = 0; i < 10; i++) assertEquals(expected, entry.voxel(Face.TOP, 100, 100, 2));
            assertSame(packed, entry.fine.get((1L << 25) | fineKey, () -> fail("cache miss")));
            assertEquals(children.getFirst(), entry.voxel(Face.TOP, 100, 100, 1));
            assertEquals(bytes, entry.fineBytes());
            assertTrue(bytes <= GroundBaseCache.Entry.FINE_LIMIT);
        } finally {
            entry.close();
        }
        assertEquals(0, entry.fineBytes());
    }

    @Test
    void revisitingFineColumnsReusesBoundedPackedData() {
        PlanetTerrainTest.registry();
        var entry = new GroundBaseCache.Entry(new PlanetTerrain(42));
        var first = entry.voxel(Face.TOP, 100, 100, 1);
        long key = ((long) Face.TOP.ordinal() * 2048 + 100) * 2048 + 100;
        var data = entry.fine.get(key, () -> fail("fine column was already retained"));
        assertEquals(first, entry.voxel(Face.TOP, 100, 100, 1));
        assertSame(data, entry.fine.get(key, () -> fail("fine column was already retained")));
        assertEquals(80 + 8L * data.length, entry.fineBytes());
        entry.close();
    }
}
