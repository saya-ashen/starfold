package dev.starfold.api;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Optional render-thread CPU work diagnostics. No allocations or GPU queries in the hot path. */
public final class RenderWorkDiagnostics {
    public enum Metric {
        NATIVE_REQUIRED_NS,
        NATIVE_WATER_NS,
        NATIVE_MAP_NS,
        NATIVE_READY_NS,
        FAR_PREPARE_NS,
        FAR_COLLECT_NS,
        FAR_DRAW_NS,
        FAR_COMPACT_NS,
        NATIVE_VISIBLE_CALLS,
        NATIVE_DIRTY_COLUMNS,
        NATIVE_REQUIRED_SECTIONS,
        NATIVE_READY_TESTS,
        NATIVE_WATER_CALLS,
        NATIVE_WATER_PALETTE_SECTIONS,
        NATIVE_WATER_FAST_ACCEPTS,
        NATIVE_WATER_SURFACE_CELLS,
        NATIVE_WATER_FLUID_LOOKUPS,
        NATIVE_MAP_COLUMNS,
        NATIVE_COVERED_COLUMNS,
        NATIVE_SUBMITTED_SECTIONS,
        FAR_PREPARE_CALLS,
        FAR_TILES_VISITED,
        FAR_RANGES_VISIBLE,
        FAR_FULL_REBUILDS,
        FAR_CACHE_REUSES,
        FAR_DRAW_CALLS,
        FAR_VERTICES_SUBMITTED,
        FAR_COMPACTIONS,
        FAR_REASON_VIEW,
        FAR_REASON_GEOMETRY,
        FAR_REASON_FADE,
        FAR_REASON_COMPACTION,
        NATIVE_OPAQUE_CALLS,
        NATIVE_COMPLETE_CALLS,
        NATIVE_MISSING_COLUMNS,
        NATIVE_READY_SECTIONS,
        NATIVE_WATER_FALLBACKS,
        NATIVE_OPAQUE_DIRTY_COLUMNS,
        NATIVE_COMPLETE_DIRTY_COLUMNS,
        NATIVE_OPAQUE_COVERED_COLUMNS,
        NATIVE_COMPLETE_COVERED_COLUMNS,
        NATIVE_WATER_PALETTE_NS,
        NATIVE_WATER_SCAN_NS,
        FAR_COLD_MISSES,
        FAR_INVALIDATIONS_PUBLICATION,
        FAR_INVALIDATIONS_RETIREMENT,
        FAR_INVALIDATIONS_REMOVAL,
        FAR_INVALIDATIONS_FADE,
        FAR_INVALIDATIONS_COMPACTION,
        FAR_INVALIDATIONS_CLEAR,
        FAR_GROUPS_VISITED,
        FAR_PARTS_VISITED,
        FAR_ARENA_REUSES,
        FAR_ARENA_REBUILDS,
        FAR_ARENA_COMMANDS,
        FAR_REASON_PUBLICATION,
        FAR_REASON_RETIREMENT,
        FAR_REASON_REMOVAL,
        FAR_REASON_CLEAR,
        FAR_REASON_CULLING,
        FAR_VIEW_PREPARE_NS,
        FAR_COMMAND_ASSEMBLY_NS,
        FAR_INDICES_SUBMITTED,
        REMOTE_SURFACE_NS,
        REMOTE_IMPOSTOR_NS,
        NATIVE_RIM_BUILD_NS,
        NATIVE_RIM_DRAW_NS,
        NATIVE_RIM_ATTEMPTS,
        NATIVE_RIM_VERTICES,
        NATIVE_RIM_TRIANGLES,
        REMOTE_RIM_SAMPLES,
        NATIVE_RIM_SAMPLES,
        REMOTE_RIM_BUILD_NS,
        REMOTE_RIM_DRAW_NS,
        REMOTE_BODY_ATTEMPTS,
        REMOTE_PATCH_DRAWS,
        REMOTE_PATCH_CORE_REJECTS,
        REMOTE_BODY_REJECTS,
        REMOTE_IMPOSTOR_DRAWS,
        REMOTE_RIM_ATTEMPTS,
        REMOTE_RIM_VERTICES,
        REMOTE_RIM_TRIANGLES,
        FAR_REQUEST_FIRST,
        FAR_REQUEST_STEP,
        FAR_REQUEST_SURFACE,
        FAR_REQUEST_MIP,
        FAR_REQUEST_DIRTY,
        FAR_PUBLICATION_FIRST,
        FAR_PUBLICATION_REPLACEMENT,
        FAR_PUBLICATION_SAME_LOCAL_INPUTS,
        FAR_PUBLICATION_REPEAT_KEY,
        FAR_INDICES_SETTLED,
        FAR_INDICES_CURRENT,
        FAR_INDICES_PREVIOUS,
        FAR_INDICES_CHART,
        FAR_CHART_INDICES_TESTED,
        FAR_CHART_INDICES_REJECTED_VIEW,
        FAR_CHART_INDICES_REJECTED_COVER,
        FAR_CHART_INDICES_REJECTED_FADE,
        FAR_INDICES_MASK,
        FAR_INDICES_STITCH,
        FAR_CULL_GROUP_TESTS,
        FAR_CULL_GROUP_REJECTS,
        FAR_CULL_PART_TESTS,
        FAR_CULL_PART_REJECTS,
        FAR_INDICES_FACE_0,
        FAR_INDICES_FACE_1,
        FAR_INDICES_FACE_2,
        FAR_INDICES_FACE_3,
        FAR_INDICES_FACE_4,
        FAR_INDICES_FACE_5,
        FAR_INDICES_STEP_1,
        FAR_INDICES_STEP_2,
        FAR_INDICES_STEP_4,
        FAR_INDICES_STEP_8,
        FAR_INDICES_STEP_MIXED,
        FAR_INDICES_STEP_OTHER,
        FAR_CHART_PREPARE_NS,
        FAR_CHART_SELECT_NS,
        FAR_CHART_DRAW_NS,
        FAR_CHART_SELECT_CALLS,
        FAR_CHART_SELECT_REBUILDS,
        FAR_CHART_SELECT_REUSES,
        FAR_CHART_NODES_VISITED,
        FAR_CHART_LEAVES_VISITED,
        FAR_CHART_PARTS_SKIPPED,
        FAR_CHART_COVERAGE_TESTS,
        FAR_CHART_NODES_REJECTED,
        FAR_CHART_RANGES_BUILT,
        FAR_CHART_METADATA_PARTS_BUILT,
        FAR_CHART_PROJECTIONS_BUILT,
        FAR_LOD_UPDATE_NS,
        FAR_LOD_COMPLETE_NS,
        FAR_LOD_ADMIT_NS,
        FAR_LOD_DISCOVER_NS,
        FAR_LOD_PENDING_FIRST,
        FAR_LOD_PENDING_REBUILD,
        FAR_LOD_PENDING_DEMAND,
        FAR_LOD_JOBS,
        FAR_LOD_READY_RESIDENT_BYTES,
        FAR_LOD_READY_COVER_BYTES,
        FAR_LOD_ALLOCATED_BYTES,
        FAR_LOD_RETAINED_CPU_BYTES,
        FAR_LOD_ARENA_FREE_BYTES,
        FAR_LOD_ARENA_GROWING_BYTES,
        FAR_LOD_ADMITTED,
        FAR_LOD_DISCOVERED,
        FAR_LOD_RETRIED_FAILURE,
        FAR_LOD_MAINTAINED,
        FAR_LOD_BLOCK_DEADLINE,
        FAR_LOD_BLOCK_WORKER_READY,
        FAR_LOD_BLOCK_WORKER_QUEUE,
        FAR_LOD_BLOCK_MEMORY,
        FAR_LOD_BLOCK_INPUT,
        FAR_LOD_BLOCK_UPLOAD,
        FAR_LOD_BLOCK_ARENA_WAIT,
        FAR_LOD_LIGHT_NS,
        FAR_LOD_REMAP_NS,
        FAR_LOD_MASK_NS,
        FAR_LOD_GROWTH_NS,
        FAR_LOD_STORAGE_NS,
        FAR_LOD_UPLOAD_STEP_NS,
        FAR_LOD_RECOVERY_NS,
        FAR_LOD_RECOVERY_CHECKS,
        FAR_LOD_RECOVERED_GROUPS,
        FAR_LOD_TRANSFER_BYTES,
        FAR_LOD_TRANSFER_NS,
        FAR_LOD_RELOCATION_BYTES,
        FAR_LOD_READERS_ACTIVE,
        FAR_LOD_READERS_QUEUED,
        FAR_LOD_STAGING,
        // Worker gauges are cumulative; subtract endpoints, never sum them as frame CPU time.
        FAR_LOD_CACHE_LOOKUPS_TOTAL,
        FAR_LOD_CACHE_LOOKUP_TOTAL_NANOS,
        FAR_LOD_CACHE_DECODE_TOTAL_NANOS,
        FAR_LOD_CACHE_TOUCH_TOTAL_NANOS,
        FAR_LOD_CACHE_RESTORE_TOTAL_NANOS,
        FAR_LOD_ADMISSION_REJECTED,
        FAR_LOD_ADMISSION_RECLAIMED,
        FAR_LOD_GPU_BYTES,
        SHADER_APPLY_CALLS,
        SHADER_APPLY_NS,
        ENTITIES_RENDERED,
        ENTITY_SHADOW_CALLS,
        FAR_LOD_TARGET_REFRESH_NS;

        public boolean timing() {
            return name().endsWith("_NS");
        }
    }

    public interface Listener {
        /** Borrowed enum-indexed values, valid only during this synchronous callback. */
        void frame(long[] values);
    }

    private static final long[] values = new long[Metric.values().length];
    private static Listener listener;
    private static boolean open;
    private static final int TIMER_CAPACITY = 256;
    private static final long[] handles = new long[TIMER_CAPACITY];
    private static final long[] starts = new long[TIMER_CAPACITY];
    private static final int[] next = new int[TIMER_CAPACITY];
    private static int free;
    private static long serial, generation;
    private static LongSupplier clock = System::nanoTime;

    private static void invalidateTimers() {
        Arrays.fill(handles, 0);
        for (int i = 0; i < TIMER_CAPACITY; i++) next[i] = i + 1;
        free = 0;
    }

    /** Render-thread only. Registration never activates a partial frame. */
    public static void register(Listener value) {
        open = false;
        invalidateTimers();
        if (value != null) {
            if (generation == Long.MAX_VALUE)
                throw new IllegalStateException("Capture generation exhausted");
            generation++;
        }
        listener = value;
        Arrays.fill(values, 0);
    }

    public static boolean active() {
        return open;
    }

    /** Stable within one recording; zero outside its open frames. */
    public static long captureGeneration() {
        return open ? generation : 0;
    }

    public static void beginFrame() {
        open = false;
        invalidateTimers();
        Arrays.fill(values, 0);
        if (listener != null) open = true;
    }

    public static void endFrame() {
        if (!open) return;
        open = false;
        invalidateTimers();
        try {
            listener.frame(values);
        } finally {
            Arrays.fill(values, 0);
        }
    }

    /** Nonnegative deltas; aggregate hot-loop counts locally before calling. */
    public static void add(Metric metric, long amount) {
        if (open && amount > 0) values[metric.ordinal()] += amount;
    }

    /** Last nonnegative occupancy snapshot in this frame; refresh frequency does not add bytes. */
    public static void gauge(Metric metric, long amount) {
        if (open && amount >= 0) values[metric.ordinal()] = amount;
    }

    /** Opaque single-use handle, or zero when inactive or 256 concurrent timers are full. */
    public static long start() {
        if (!open || free == TIMER_CAPACITY || serial == (Long.MAX_VALUE >>> 8)) return 0;
        int slot = free;
        free = next[slot];
        long handle = (++serial << 8) | slot;
        starts[slot] = clock.getAsLong();
        handles[slot] = handle;
        return handle;
    }

    /** Reject stale/consumed handles; calculate elapsed using unmodified clock readings. */
    public static void finish(Metric metric, long handle) {
        if (!open || handle == 0) return;
        int slot = (int) (handle & 255);
        if (handles[slot] != handle) return;
        long elapsed = clock.getAsLong() - starts[slot];
        handles[slot] = 0;
        next[slot] = free;
        free = slot;
        add(metric, Math.max(0, elapsed));
    }

    /** Package-private deterministic clock seam; changes discard outstanding timers. */
    static void clockForTests(LongSupplier value) {
        invalidateTimers();
        clock = value == null ? System::nanoTime : value;
    }

    private RenderWorkDiagnostics() {}
}
