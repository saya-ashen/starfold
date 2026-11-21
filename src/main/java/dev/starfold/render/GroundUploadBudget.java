package dev.starfold.render;

import java.util.function.LongSupplier;

/** Shared render-thread admission for packing, uploads and arena copies. */
final class GroundUploadBudget {
    static final int FRAME_BYTES = 1024 * 1024;
    static final int BURST_BYTES = 4 * FRAME_BYTES;
    static final int BACKLOG_BYTES = 2 * FRAME_BYTES;
    static final long BURST_NANOS = 1_500_000;
    static final int SLICE_BYTES = 32 * 1024;
    static final int PACKING_SLICE_VERTICES = 256;
    private final LongSupplier clock;
    private final long deadline;
    private final boolean adaptive;
    private final int fixedBytes;
    private long pendingBytes, transferNanos;
    private int consumed, reserved;
    private long phaseDeadline;

    GroundUploadBudget(long deadline) {
        this(deadline, 0L);
    }

    /** pendingBytes includes ready geometry and remaining arena copies; only >2 MiB enables catch-up. */
    GroundUploadBudget(long deadline, long pendingBytes) {
        this(deadline, pendingBytes, System::nanoTime);
    }

    GroundUploadBudget(long deadline, long pendingBytes, LongSupplier clock) {
        this.deadline = phaseDeadline = deadline;
        this.pendingBytes = Math.max(0, pendingBytes);
        this.clock = clock;
        adaptive = true;
        fixedBytes = 0;
    }

    /** Explicit fixed allowance retained for bounded helpers and deterministic tests. */
    GroundUploadBudget(long deadline, int bytes, LongSupplier clock) {
        if (bytes < 0) throw new IllegalArgumentException("Negative upload allowance");
        this.deadline = phaseDeadline = deadline;
        this.clock = clock;
        fixedBytes = bytes;
        adaptive = false;
    }

    int byteLimit() {
        return !adaptive
                ? fixedBytes
                : pendingBytes > BACKLOG_BYTES && transferNanos < BURST_NANOS
                        ? BURST_BYTES
                        : FRAME_BYTES;
    }

    void backlog(long pendingBytes) {
        this.pendingBytes = Math.max(0, pendingBytes);
    }

    int consumedBytes() {
        return consumed;
    }

    long transferNanos() {
        return transferNanos;
    }

    /** Measure actual transfer/copy submission work, independently of queue age or CPU packing. */
    long startTransfer() {
        return clock.getAsLong();
    }

    void endTransfer(long started) {
        transferNanos += Math.max(0, clock.getAsLong() - started);
    }

    boolean available() {
        return byteLimit() - consumed > reserved && cpuAvailable();
    }

    /** CPU work shares the phase deadline, but does not consume GPU transfer bytes. */
    boolean cpuAvailable() {
        return clock.getAsLong() < phaseDeadline;
    }

    /** Admit a small, quad-aligned packing slice; callers also check time between quads. */
    int takePackingVertices(int requested) {
        if (requested <= 0 || !cpuAvailable()) return 0;
        int vertices = Math.min(requested, PACKING_SLICE_VERTICES);
        return vertices - vertices % 4;
    }

    int take(int requested, int alignment) {
        if (alignment <= 0) throw new IllegalArgumentException("Nonpositive transfer alignment");
        if (requested <= 0 || !available()) return 0;
        int bytes = Math.min(requested, Math.min(byteLimit() - consumed - reserved, SLICE_BYTES));
        bytes -= bytes % alignment;
        consumed += bytes;
        return bytes;
    }

    /** Indivisible storage allocation: admit its ENTIRE cost before issuing the GL call. */
    boolean takeWhole(int bytes) {
        if (bytes <= 0 || !cpuAvailable() || bytes > byteLimit() - consumed - reserved)
            return false;
        consumed += bytes;
        return true;
    }

    void limit(long until) {
        phaseDeadline = Math.min(deadline, until);
    }

    void reserve(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative reserved allowance");
        reserved = bytes;
    }

    long deadline() {
        return deadline;
    }
}
