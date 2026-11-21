package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import dev.starfold.api.RenderWorkDiagnostics;

/**
 * Bounded, render-thread-only resident lifecycle counters. Call request only after enqueue succeeds
 * and publication only after a resident is actually published. A zero old step means no resident.
 * No events, surface references, or per-key input history are retained or exported.
 */
public final class GroundLodLifecycleDiagnostics {
    private static final int TILE_COUNT = 98_304;

    /** Loaded only by an active, valid publication; even first inactive use allocates no table. */
    private static final class Publications {
        private static final long[] generations = new long[TILE_COUNT];
    }

    /**
     * FIRST means absent resident; STEP means an existing resident changes step. SURFACE, MIP and
     * DIRTY independently count the supplied reason flags, including alongside FIRST or STEP, so
     * reason totals can exceed enqueued requests. Invalid canonical keys are silently ignored.
     */
    public static void request(
            long key,
            int oldStep,
            int newStep,
            boolean surfaceChanged,
            boolean mipChanged,
            boolean forcedDirty) {
        if (!RenderWorkDiagnostics.active() || key < 0 || key >= TILE_COUNT) return;
        if (oldStep == 0) RenderWorkDiagnostics.add(FAR_REQUEST_FIRST, 1);
        else if (oldStep != newStep) RenderWorkDiagnostics.add(FAR_REQUEST_STEP, 1);
        if (surfaceChanged) RenderWorkDiagnostics.add(FAR_REQUEST_SURFACE, 1);
        if (mipChanged) RenderWorkDiagnostics.add(FAR_REQUEST_MIP, 1);
        if (forcedDirty) RenderWorkDiagnostics.add(FAR_REQUEST_DIRTY, 1);
    }

    /**
     * FIRST and REPLACEMENT partition publications by resident presence. SAME_LOCAL_INPUTS overlaps
     * when old/new step and mip match and local surfaces compare equal; halo sources are not
     * compared; forcedDirty does not change that classification (it is a request reason only).
     * REPEAT_KEY overlaps every publication after the first for its canonical key in this capture,
     * including across frame boundaries and intervening retirements. It establishes recurrence, not
     * an ordered per-key event history. Generation tags lazily reset identity tracking without
     * clearing the table each capture.
     */
    public static void publication(
            long key,
            int oldStep,
            int newStep,
            long oldMip,
            long newMip,
            boolean surfaceEqual,
            boolean forcedDirty) {
        long generation = RenderWorkDiagnostics.captureGeneration();
        if (generation == 0 || key < 0 || key >= TILE_COUNT) return;
        RenderWorkDiagnostics.add(
                oldStep == 0 ? FAR_PUBLICATION_FIRST : FAR_PUBLICATION_REPLACEMENT, 1);
        if (oldStep == newStep && oldMip == newMip && surfaceEqual)
            RenderWorkDiagnostics.add(FAR_PUBLICATION_SAME_LOCAL_INPUTS, 1);
        int index = (int) key;
        if (Publications.generations[index] == generation)
            RenderWorkDiagnostics.add(FAR_PUBLICATION_REPEAT_KEY, 1);
        else Publications.generations[index] = generation;
    }

    /** Separate identity domain: node IDs must never alias canonical 16-block tile keys. */
    private static final class NodePublications {
        private static final long[] generations = new long[GroundQuadtree.NODE_COUNT];
    }

    /** Only known reasons are counted. Tree dirtiness does not identify surface vs mip input. */
    public static void nodeRequest(int node, int oldStep, int newStep, boolean dirty) {
        if (!RenderWorkDiagnostics.active() || node < 0 || node >= GroundQuadtree.NODE_COUNT)
            return;
        if (oldStep == 0) RenderWorkDiagnostics.add(FAR_REQUEST_FIRST, 1);
        else if (oldStep != newStep) RenderWorkDiagnostics.add(FAR_REQUEST_STEP, 1);
        if (dirty) RenderWorkDiagnostics.add(FAR_REQUEST_DIRTY, 1);
    }

    /** A node version is a target/invalidation token, never a mip or local-input equality proof. */
    public static void nodePublication(int node, int oldStep) {
        long generation = RenderWorkDiagnostics.captureGeneration();
        if (generation == 0 || node < 0 || node >= GroundQuadtree.NODE_COUNT) return;
        RenderWorkDiagnostics.add(
                oldStep == 0 ? FAR_PUBLICATION_FIRST : FAR_PUBLICATION_REPLACEMENT, 1);
        if (NodePublications.generations[node] == generation)
            RenderWorkDiagnostics.add(FAR_PUBLICATION_REPEAT_KEY, 1);
        else NodePublications.generations[node] = generation;
    }

    /**
     * Completed CPU vertex payloads retained until publication/cancellation, not GPU residents.
     * Each renderer session owns a new instance so late workers cannot affect its successor.
     * Workers update only these atomics, never the render-thread diagnostics API.
     */
    static final class ReadyBytes {
        private final java.util.concurrent.atomic.AtomicLong detail =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong cover =
                new java.util.concurrent.atomic.AtomicLong();

        void add(boolean root, long bytes) {
            (root ? cover : detail).addAndGet(bytes);
        }

        long detail() {
            return detail.get();
        }

        long cover() {
            return cover.get();
        }
    }

    private GroundLodLifecycleDiagnostics() {}
}
