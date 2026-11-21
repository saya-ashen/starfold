package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

class GroundUploadBudgetTest {
    @Test
    void packingDoesNotChargeTransfersAndCanProceedAfterTransfersAreExhausted() {
        var clock = new AtomicLong(10);
        var budget = new GroundUploadBudget(100, 48, clock::get);
        // Packing far more than the old 44-byte-per-vertex allowance leaves all 48 bytes.
        for (int i = 0; i < 1000; i++)
            assertEquals(
                    GroundUploadBudget.PACKING_SLICE_VERTICES,
                    budget.takePackingVertices(Integer.MAX_VALUE));
        assertEquals(48, budget.take(48, 12));
        assertFalse(budget.available());
        assertTrue(budget.cpuAvailable());
        assertEquals(4, budget.takePackingVertices(4));
    }

    @Test
    void packingSlicesRemainSmallAndQuadAligned() {
        var budget = new GroundUploadBudget(100, 0, () -> 10);
        assertEquals(0, budget.takePackingVertices(3));
        assertEquals(4, budget.takePackingVertices(7));
        assertEquals(
                GroundUploadBudget.PACKING_SLICE_VERTICES,
                budget.takePackingVertices(Integer.MAX_VALUE));
        assertEquals(0, GroundUploadBudget.PACKING_SLICE_VERTICES % 4);
        assertTrue(GroundUploadBudget.PACKING_SLICE_VERTICES <= 256);
    }

    @Test
    void packingHonorsCoverDeadlineAndLeavesTheReservedResidentTransferShare() {
        var clock = new AtomicLong(10);
        var budget = new GroundUploadBudget(100, 96, clock::get);
        budget.reserve(48);
        budget.limit(20);
        assertEquals(256, budget.takePackingVertices(1000));
        assertEquals(48, budget.take(96, 12));
        assertFalse(budget.available());
        assertEquals(256, budget.takePackingVertices(1000));
        clock.set(20);
        assertFalse(budget.cpuAvailable());
        assertEquals(0, budget.takePackingVertices(4));
        budget.limit(100);
        budget.reserve(0);
        assertEquals(4, budget.takePackingVertices(4));
        assertEquals(48, budget.take(96, 12));
        budget.limit(200);
        clock.set(100);
        assertFalse(budget.cpuAvailable());
        assertEquals(0, budget.takePackingVertices(4));
    }

    @Test
    void coverDeadlineCanYieldAndRestoreTheResidentShare() {
        var clock = new AtomicLong(10);
        var budget = new GroundUploadBudget(100, 128, clock::get);
        budget.limit(20);
        clock.set(20);
        assertEquals(0, budget.take(32, 4));
        budget.limit(100);
        assertEquals(32, budget.take(32, 4));
        budget.limit(200);
        clock.set(100);
        assertEquals(0, budget.take(32, 4));
    }

    @Test
    void sharedSlicesStopAtDeadlineAndPreserveReservedResidentWork() {
        var clock = new AtomicLong(10);
        var budget = new GroundUploadBudget(100, 96 * 1024, clock::get);
        budget.reserve(32 * 1024);
        assertEquals(32 * 1024, budget.take(96 * 1024, 4));
        assertEquals(32 * 1024, budget.take(96 * 1024, 4));
        assertFalse(budget.available());
        budget.reserve(0);
        assertTrue(budget.available());
        assertEquals(32760, budget.take(96 * 1024, 12));
        assertEquals(0, budget.take(12, 12));
        clock.set(100);
        assertEquals(0, budget.take(8, 4));
    }

    @Test
    void adaptiveBudgetHasExactBacklogAndActualTransferTimeBoundaries() {
        var clock = new AtomicLong(0);
        var budget =
                new GroundUploadBudget(
                        Long.MAX_VALUE, (long) GroundUploadBudget.BACKLOG_BYTES, clock::get);
        assertEquals(1024 * 1024, budget.byteLimit());
        budget.backlog(GroundUploadBudget.BACKLOG_BYTES + 1L);
        assertEquals(4 * 1024 * 1024, budget.byteLimit());
        // CPU packing and queue age do not masquerade as GL upload submission cost.
        clock.set(10_000_000);
        budget.takePackingVertices(256);
        assertEquals(0, budget.transferNanos());
        long start = budget.startTransfer();
        clock.addAndGet(GroundUploadBudget.BURST_NANOS - 1);
        budget.endTransfer(start);
        assertEquals(GroundUploadBudget.BURST_BYTES, budget.byteLimit());
        start = budget.startTransfer();
        clock.incrementAndGet();
        budget.endTransfer(start);
        assertEquals(GroundUploadBudget.FRAME_BYTES, budget.byteLimit());
    }

    @Test
    void catchUpStopsAtFourMiBAndSlowingDownNeverCreatesNegativeAllowance() {
        var clock = new AtomicLong(0);
        var budget =
                new GroundUploadBudget(
                        Long.MAX_VALUE, GroundUploadBudget.BACKLOG_BYTES + 1L, clock::get);
        int bytes = 0, slice;
        while ((slice = budget.take(Integer.MAX_VALUE, 12)) != 0) bytes += slice;
        assertTrue(bytes <= 4 * 1024 * 1024);
        assertTrue(4 * 1024 * 1024 - bytes < 12);
        assertEquals(bytes, budget.consumedBytes());
        long start = budget.startTransfer();
        clock.addAndGet(GroundUploadBudget.BURST_NANOS);
        budget.endTransfer(start);
        assertFalse(budget.available());
        assertEquals(0, budget.take(48, 12));
        // CPU material resolution remains independent of exhausted transfer bytes.
        assertEquals(4, budget.takePackingVertices(4));
    }

    @Test
    void ordinaryFramesStopAtOneMiBAndCatchUpCanResumeWithinTheSameFrame() {
        var budget = new GroundUploadBudget(Long.MAX_VALUE, 0L, () -> 0);
        int bytes = 0, slice;
        while ((slice = budget.take(Integer.MAX_VALUE, 4)) != 0) bytes += slice;
        assertEquals(1024 * 1024, bytes);
        budget.backlog(GroundUploadBudget.BACKLOG_BYTES + 1L);
        assertTrue(budget.available());
        assertEquals(GroundUploadBudget.SLICE_BYTES, budget.take(Integer.MAX_VALUE, 4));
        assertEquals(1024 * 1024 + GroundUploadBudget.SLICE_BYTES, budget.consumedBytes());
    }

    @Test
    void indivisibleAllocationCannotPartiallyConsumeBudgetOrBorrowReservedBytes() {
        var clock = new AtomicLong(0);
        var budget = new GroundUploadBudget(100, 65536, clock::get);
        budget.reserve(4096);
        assertFalse(budget.takeWhole(65536));
        assertEquals(0, budget.consumedBytes());
        assertTrue(budget.takeWhole(49152)); // Storage allocations may exceed a transfer slice.
        assertEquals(49152, budget.consumedBytes());
        assertEquals(12288, budget.take(65536, 16));
        assertFalse(budget.takeWhole(16));
        budget.reserve(0);
        clock.set(100);
        assertFalse(budget.takeWhole(4096));
    }
}
