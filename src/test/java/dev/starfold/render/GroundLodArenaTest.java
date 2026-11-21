package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.RenderWorkDiagnostics;

import org.junit.jupiter.api.Test;

class GroundLodArenaTest {
    @Test
    void trimmingNeverReplacesWithTheSameOrLargerMinimumCapacity() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(400000);
        ranges.allocate(10000);
        assertEquals(GroundLodArena.AllocationStatus.INVALID,
                ranges.planTrim(400000, 10_000_000, 12).status());
        assertEquals(GroundLodArena.AllocationStatus.INVALID,
                ranges.planTrim(800000, 10_000_000, 12).status());
        assertEquals(GroundLodArena.AllocationStatus.GROW,
                ranges.planTrim(32, 10_000_000, 12).status());
    }

    @Test
    void independentPassCommandsReuseUntilRelocationEvenWhenTheirEpochsMatch() {
        try (var opaque = new GroundLodArena.Commands(); var translucent = new GroundLodArena.Commands()) {
            assertTrue(opaque.begin(1, 1, 0));
            assertTrue(translucent.begin(2, 1, 0));
            assertFalse(opaque.begin(1, 1, 0));
            assertFalse(translucent.begin(2, 1, 0));
            assertTrue(opaque.begin(1, 1, 1), "relocation changes base vertices without a new cut");
            assertTrue(translucent.begin(2, 1, 1));
            assertFalse(opaque.begin(1, 1, 1));
        }
    }

    @Test
    void heapUploadSlicesUseBoundedNativeScratchWithoutMutatingSource() {
        var heap = java.nio.ByteBuffer.allocate(64);
        for (int i = 0; i < 64; i++) heap.put((byte) i);
        heap.position(12).limit(48);
        var nativeBytes = GroundLodArena.uploadSource(heap.asReadOnlyBuffer());
        assertTrue(nativeBytes.isDirect());
        assertEquals(36, nativeBytes.remaining());
        for (int i = 0; i < 36; i++) assertEquals((byte) (12 + i), nativeBytes.get(i));
        assertEquals(12, heap.position());
        assertEquals(48, heap.limit());
        var direct = java.nio.ByteBuffer.allocateDirect(12);
        assertSame(direct, GroundLodArena.uploadSource(direct));
        assertThrows(IllegalArgumentException.class,
                () -> GroundLodArena.uploadSource(java.nio.ByteBuffer.allocate(
                        GroundUploadBudget.SLICE_BYTES + 1)));
    }

    @Test
    void cachedCommandsPreserveCountsBasesAndOffsetsUntilTopologyChanges() {
        try (var commands = new GroundLodArena.Commands()) {
            assertTrue(commands.begin(3, 1));
            commands.add(new GroundLodArena.Range(null, 0, 8));
            commands.add(new GroundLodArena.Range(null, 8, 12));
            commands.add(new GroundLodArena.Range(null, 40, 4));
            commands.prepare();
            assertEquals(2, commands.draws);
            assertEquals(24, commands.vertices); // Includes both merged and disjoint ranges.
            assertEquals(30, commands.counts.get(0));
            assertEquals(6, commands.counts.get(1));
            assertEquals(0, commands.bases.get(0));
            assertEquals(40, commands.bases.get(1));
            assertEquals(0L, commands.offsets.get(1));
            assertFalse(commands.begin(3, 1));
            assertEquals(
                    24, commands.vertices); // Retained even if recording starts after assembly.
            commands.prepare();
            assertEquals(
                    2,
                    commands.counts.remaining()); // Reuse must not flip an already flipped buffer.
            assertEquals(30, commands.counts.get(0));
            assertTrue(
                    commands.begin(
                            20, 2)); // Compaction / replacement changes offsets and grows storage.
            commands.add(new GroundLodArena.Range(null, 80, 24));
            commands.prepare();
            assertEquals(1, commands.draws);
            assertEquals(24, commands.vertices);
            assertEquals(36, commands.counts.get(0));
            assertEquals(80, commands.bases.get(0));
            assertEquals(0L, commands.offsets.get(0));
            assertTrue(commands.begin(0, 3)); // Retirement clears every old draw.
            commands.prepare();
            assertEquals(0, commands.draws);
            assertEquals(0, commands.vertices);
            assertEquals(0, commands.counts.remaining());
        }
    }

    @Test
    void arenaMetricsObserveMergedReusedAndEmptyBatchesAfterInactiveAssembly() {
        try (var commands = new GroundLodArena.Commands()) {
            assertTrue(commands.begin(3, 1));
            commands.add(new GroundLodArena.Range(null, 0, 8));
            commands.add(new GroundLodArena.Range(null, 8, 12));
            commands.add(new GroundLodArena.Range(null, 40, 4));
            commands.prepare();
            assertEquals(36, commands.indices);
            long[][] row = {null};
            RenderWorkDiagnostics.register(values -> row[0] = values.clone());
            try {
                RenderWorkDiagnostics.beginFrame();
                for (int pass = 0; pass < 2; pass++) {
                    assertFalse(commands.begin(3, 1));
                    commands.prepare();
                    assertEquals(36, commands.indices);
                    assertEquals(24, commands.vertices);
                }
                assertTrue(commands.begin(0, 2));
                commands.prepare();
                assertEquals(0, commands.indices);
                assertEquals(0, commands.vertices);
                RenderWorkDiagnostics.endFrame();
                assertEquals(2, row[0][FAR_ARENA_REUSES.ordinal()]);
                assertEquals(1, row[0][FAR_ARENA_REBUILDS.ordinal()]);
                assertEquals(4, row[0][FAR_ARENA_COMMANDS.ordinal()]);
                assertEquals(0, row[0][FAR_DRAW_CALLS.ordinal()]);
                assertEquals(0, row[0][FAR_INDICES_SUBMITTED.ordinal()]);
            } finally {
                RenderWorkDiagnostics.register(null);
            }
        }
    }

    @Test
    void submissionUnitsRepeatForEachPassAndIncludeTransitionRanges() {
        try (var commands = new GroundLodArena.Commands()) {
            commands.begin(3, 1);
            commands.add(new GroundLodArena.Range(null, 0, 8));
            commands.add(new GroundLodArena.Range(null, 8, 12));
            commands.add(new GroundLodArena.Range(null, 40, 4));
            commands.prepare();
            var transition = new GroundLodArena.Range(null, 80, 12);
            GroundLodArena.recordSubmission(commands.indices); // Inactive emissions are discarded.
            long[][] row = {null};
            RenderWorkDiagnostics.register(values -> row[0] = values.clone());
            try {
                RenderWorkDiagnostics.beginFrame();
                for (int pass = 0; pass < 2; pass++) {
                    assertFalse(commands.begin(3, 1));
                    commands.prepare();
                    // Exercise the shared recorder called immediately after both GL paths.
                    GroundLodArena.recordSubmission(commands.indices);
                    GroundLodArena.recordSubmission(transition.indices());
                }
                RenderWorkDiagnostics.endFrame();
                assertEquals(4, row[0][FAR_DRAW_CALLS.ordinal()]);
                assertEquals(108, row[0][FAR_INDICES_SUBMITTED.ordinal()]);
                assertEquals(72, row[0][FAR_VERTICES_SUBMITTED.ordinal()]);
                assertEquals(4, row[0][FAR_ARENA_COMMANDS.ordinal()]);
            } finally {
                RenderWorkDiagnostics.register(null);
            }
        }
    }

    @Test
    void reusableCapacityAtThreeGiBCeilingHasZeroIncrementalCost() {
        var ranges = new GroundLodArena.Ranges();
        long ceiling = 3L * 1024 * 1024 * 1024;
        int capacity = (int) (ceiling / 12);
        ranges.grow(capacity);
        assertEquals(0, ranges.allocate(capacity - 4));
        var before = new java.util.TreeMap<>(ranges.free);

        var plan = ranges.planAllocation(4, 65536, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REUSE, plan.status());
        assertEquals(capacity - 4, plan.first());
        assertEquals(capacity, plan.capacityVertices());
        assertEquals(0, plan.additionalBytes());
        assertEquals(0, plan.retainedBytesDelta());
        assertEquals(before, ranges.free);
        assertEquals(capacity, ranges.capacity);
        assertEquals(plan.first(), ranges.allocate(4));
        assertEquals(0, ranges.freeVertices());
        assertFalse(ranges.isEmpty());
    }

    @Test
    void fragmentedFreeSpaceRequiresPackedReplacementUntilAdjacentHolesCoalesce() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        for (int first = 0; first < 32; first += 8) assertEquals(first, ranges.allocate(8));
        ranges.free(0, 8);
        ranges.free(16, 8);
        assertEquals(16, ranges.freeVertices());
        assertEquals(8, ranges.largestFreeRange());
        var plan = ranges.planAllocation(12, 0, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REJECTED, plan.status());
        assertEquals(28 * 12L, plan.additionalBytes());
        var compact = ranges.planAllocation(12, 0, 0, 28 * 12L, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, compact.status());
        assertEquals(28, compact.capacityVertices());
        assertEquals(-4 * 12L, compact.retainedBytesDelta());
        assertEquals(-1, ranges.allocate(12));
        ranges.free(8, 8);
        plan = ranges.planAllocation(12, 0, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REUSE, plan.status());
        assertEquals(24, ranges.largestFreeRange());
        assertEquals(plan.first(), ranges.allocate(12));
        assertEquals(0, plan.first());
    }

    @Test
    void growthChargesWholeReplacementBesideOldStorageAndFallsBackToExactSize() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        ranges.allocate(32);
        // The final delta fits, but cannot fund a replacement beside the old 384 bytes.
        var rejected = ranges.planAllocation(8, 0, 0, 8 * 12L, 12);
        assertEquals(GroundLodArena.AllocationStatus.REJECTED, rejected.status());
        assertEquals(40, rejected.capacityVertices());
        assertEquals(40 * 12L, rejected.additionalBytes());
        assertEquals(8 * 12L, rejected.retainedBytesDelta());

        var exact = ranges.planAllocation(8, 0, 0, 40 * 12L, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, exact.status());
        assertEquals(40, exact.capacityVertices());
        assertEquals(40 * 12L, exact.additionalBytes());
        assertEquals(8 * 12L, exact.retainedBytesDelta());
        assertEquals(32 * 12L + exact.additionalBytes(), (32 + 40) * 12L);

        var spare = ranges.planAllocation(8, 0, 0, 64 * 12L, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, spare.status());
        assertEquals(52, spare.capacityVertices());
        assertEquals(52 * 12L, spare.additionalBytes());
        assertEquals(20 * 12L, spare.retainedBytesDelta());
        ranges.grow((int) spare.capacityVertices());
        var reuse = ranges.planAllocation(8, 0, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REUSE, reuse.status());
        assertEquals(32, reuse.first());
        assertEquals(reuse.first(), ranges.allocate(8));
    }

    @Test
    void limitedHeadroomStillReservesSpaceForMoreThanOneTinyUpload() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        ranges.allocate(32);
        var plan = ranges.planAllocation(4, 0, 0, 44 * 12L + 11, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, plan.status());
        assertEquals(44, plan.capacityVertices(), "use affordable spare space instead of recopying the pool for every mesh");
        assertEquals(44 * 12L, plan.additionalBytes());
        new GroundLodArena.Relocation(java.util.List.of(new GroundLodArena.Range(null, 0, 32)), 44).commit(ranges);
        assertEquals(32, ranges.allocate(4));
        assertEquals(GroundLodArena.AllocationStatus.REUSE, ranges.planAllocation(4, 0, 0, 0, 12).status());
    }

    @Test
    void initialAllocationUsesMinimumCapacityOnlyWhenPeakHeadroomAllowsIt() {
        var ranges = new GroundLodArena.Ranges();
        var preferred = ranges.planAllocation(4, 65536, 0, Long.MAX_VALUE, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, preferred.status());
        assertEquals(65536, preferred.capacityVertices());
        assertEquals(65536 * 12L, preferred.additionalBytes());
        assertEquals(preferred.additionalBytes(), preferred.retainedBytesDelta());
        var exact = ranges.planAllocation(4, 65536, 0, 48, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, exact.status());
        assertEquals(4, exact.capacityVertices());
        assertEquals(48, exact.additionalBytes());
        assertEquals(
                GroundLodArena.AllocationStatus.REJECTED,
                ranges.planAllocation(4, 65536, 0, 47, 12).status());
        assertTrue(ranges.isEmpty());
        assertEquals(0, ranges.capacity);
        assertTrue(ranges.free.isEmpty());
    }

    @Test
    void pendingGrowthWaitsWithoutChargingReplacementTwiceOrConsumingOldHoles() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        ranges.allocate(16);
        var before = new java.util.TreeMap<>(ranges.free);
        for (int vertices : new int[] {4, 48, 128}) {
            var pending = ranges.planAllocation(vertices, 0, 64, 0, 12);
            assertEquals(GroundLodArena.AllocationStatus.WAIT, pending.status());
            assertEquals(-1, pending.first());
            assertEquals(64, pending.capacityVertices());
            assertEquals(0, pending.additionalBytes());
            assertEquals(0, pending.retainedBytesDelta());
        }
        assertEquals(before, ranges.free);
        assertEquals(32, ranges.capacity);
        assertEquals(16, ranges.freeVertices());
        ranges.grow(64);
        var completed = ranges.planAllocation(48, 0, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REUSE, completed.status());
        assertEquals(16, completed.first());
        assertEquals(completed.first(), ranges.allocate(48));
        assertEquals(
                GroundLodArena.AllocationStatus.REJECTED,
                ranges.planAllocation(128, 0, 0, 0, 12).status());
    }

    @Test
    void plansAreSnapshotsAndMustBeQueriedAgainAfterAnotherAllocation() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(16);
        var before = ranges.planAllocation(12, 0, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REUSE, before.status());
        assertEquals(0, ranges.allocate(8));
        var after = ranges.planAllocation(12, 0, 0, 0, 12);
        assertEquals(GroundLodArena.AllocationStatus.REJECTED, after.status());
        assertEquals(-1, ranges.allocate(12));
        assertEquals(8, ranges.freeVertices());
        assertEquals(8, ranges.largestFreeRange());
        assertFalse(ranges.isEmpty());
        ranges.free(0, 8);
        assertTrue(ranges.isEmpty());
        assertEquals(16, ranges.freeVertices());
        assertEquals(16, ranges.largestFreeRange());
    }

    @Test
    void invalidRequestsAndCapacityOverflowCannotCorruptRangesOrWrapCosts() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(Integer.MAX_VALUE);
        for (int vertices : new int[] {0, -4}) {
            assertEquals(
                    GroundLodArena.AllocationStatus.INVALID,
                    ranges.planAllocation(vertices, 0, 0, Long.MAX_VALUE, 12).status());
            assertEquals(-1, ranges.allocate(vertices));
        }
        assertTrue(ranges.isEmpty());
        assertEquals(0, ranges.allocate(Integer.MAX_VALUE));
        var overflow = ranges.planAllocation(4, 0, 0, Long.MAX_VALUE, 12);
        assertEquals(GroundLodArena.AllocationStatus.REJECTED, overflow.status());
        assertEquals((long) Integer.MAX_VALUE + 4, overflow.capacityVertices());
        assertEquals(((long) Integer.MAX_VALUE + 4) * 12, overflow.additionalBytes());
        assertEquals(48, overflow.retainedBytesDelta());
        assertEquals(Integer.MAX_VALUE, ranges.capacity);
        assertTrue(ranges.free.isEmpty());

        var large = new GroundLodArena.Ranges();
        large.grow(200_000_000);
        large.allocate(200_000_000);
        var wideBytes = large.planAllocation(4, 0, 0, 3L * 1024 * 1024 * 1024, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, wideBytes.status());
        assertEquals(250_000_008, wideBytes.capacityVertices());
        assertEquals(3_000_000_096L, wideBytes.additionalBytes());
    }

    @Test
    void unallocatedOffsetsNeverBecomeGpuDrawRanges() {
        assertThrows(IllegalArgumentException.class, () -> new GroundLodArena.Range(null, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new GroundLodArena.Range(null, 0, 0));
        var ranges = new GroundLodArena.Ranges();
        new GroundLodArena.Relocation(java.util.List.of(), 24).commit(ranges);
        assertEquals(0, ranges.allocate(4), "first upload must reserve the new empty VBO before cleanup");
        assertEquals(20, ranges.freeVertices());
    }

    @Test
    void relocationPacksOnlyLiveDataAndPublishesOffsetsAfterCopying() {
        var a = new GroundLodArena.Range(null, 8, 8);
        var b = new GroundLodArena.Range(null, 24, 8);
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        var move = new GroundLodArena.Relocation(java.util.List.of(b, a), 28);
        assertEquals(8, a.first());
        assertEquals(24, b.first());
        assertEquals(0, move.moves.get(0).target());
        assertEquals(8, move.moves.get(1).target());
        int[] old = java.util.stream.IntStream.range(0, 32).toArray();
        int[] packed = new int[28];
        for (var m : move.moves)
            System.arraycopy(old, m.source(), packed, m.target(), m.range().vertices());
        assertEquals(24, b.first(), "old VBO remains drawable during partial copies");
        move.commit(ranges);
        assertEquals(0, a.first());
        assertEquals(8, b.first());
        for (int i = 0; i < 8; i++) {
            assertEquals(8 + i, packed[a.first() + i]);
            assertEquals(24 + i, packed[b.first() + i]);
        }
        assertEquals(28, ranges.capacity);
        assertEquals(16, ranges.allocate(12), "the upload fits without evicting either resident range");
        assertTrue(ranges.free.isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new GroundLodArena.Relocation(java.util.List.of(a, b), 12));
    }

    @Test
    void closingRangesDuringRelocationLeavesReusableHolesAndNeverResurrectsThem() {
        var a = new GroundLodArena.Range(null, 8, 8);
        var b = new GroundLodArena.Range(null, 24, 8);
        var c = new GroundLodArena.Range(null, 40, 8);
        var move = new GroundLodArena.Relocation(java.util.List.of(a, b, c), 32);
        b.close(); b.close();
        var ranges = new GroundLodArena.Ranges();
        move.commit(ranges);
        assertTrue(b.closed);
        assertEquals(0, a.first());
        assertEquals(16, c.first());
        assertEquals(16, ranges.freeVertices());
        assertEquals(8, ranges.allocate(8));
        a.close(); c.close();
        move.commit(ranges);
        assertTrue(ranges.isEmpty());
        assertEquals(32, ranges.freeVertices());
    }

    @Test
    void trimmingHasHysteresisAndChargesTheEntireReplacementBesideOldStorage() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(1_000_000);
        ranges.allocate(800_000);
        assertEquals(GroundLodArena.AllocationStatus.INVALID,
                ranges.planTrim(4096, Long.MAX_VALUE, 12).status());
        ranges.free(400_000, 400_000);
        var plan = ranges.planTrim(4096, 450_000L * 12, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, plan.status());
        assertEquals(450_000, plan.capacityVertices());
        assertEquals(450_000L * 12, plan.additionalBytes());
        assertEquals(-550_000L * 12, plan.retainedBytesDelta());
        assertEquals(GroundLodArena.AllocationStatus.REJECTED,
                ranges.planTrim(4096, plan.additionalBytes() - 1, 12).status());
        assertEquals(1_000_000, ranges.capacity, "planning never destroys old storage");
    }

    @Test
    void rangesClearResetsCapacityAndFreeList() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        assertEquals(0, ranges.allocate(8));
        assertEquals(8, ranges.allocate(8));
        ranges.free(0, 8);
        assertEquals(32, ranges.capacity);
        assertEquals(24, ranges.freeVertices());
        assertFalse(ranges.isEmpty());

        ranges.clear();

        assertEquals(0, ranges.capacity);
        assertEquals(0, ranges.freeVertices());
        assertTrue(ranges.free.isEmpty());
        assertTrue(ranges.isEmpty());
    }

    @Test
    void firstFitReusesAndCoalescesHolesWithoutMovingLiveRanges() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        assertEquals(0, ranges.allocate(8));
        assertEquals(8, ranges.allocate(12));
        assertEquals(20, ranges.allocate(12));
        assertEquals(-1, ranges.allocate(4));
        ranges.free(8, 12);
        assertEquals(8, ranges.allocate(4));
        ranges.free(0, 8);
        ranges.free(8, 4);
        assertEquals(0, ranges.allocate(20));
        ranges.grow(64);
        assertEquals(32, ranges.allocate(16));
        ranges.free(20, 12);
        ranges.free(0, 20);
        ranges.free(32, 16);
        assertEquals(0, ranges.allocate(64));
        assertTrue(ranges.free.isEmpty());
    }
}
