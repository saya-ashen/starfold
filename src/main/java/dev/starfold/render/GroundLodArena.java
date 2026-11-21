package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.api.RenderWorkDiagnostics;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

/** One face/layer's vertices. Sliced replacement packs live ranges and commits offsets atomically. */
final class GroundLodArena implements AutoCloseable {
    // Render-thread-only scratch: heap staging must never reach LWJGL's memAddress overload.
    private static final ByteBuffer HEAP_UPLOAD =
            ByteBuffer.allocateDirect(GroundUploadBudget.SLICE_BYTES);

    static ByteBuffer uploadSource(ByteBuffer source) {
        if (source.isDirect()) return source;
        if (source.remaining() > HEAP_UPLOAD.capacity())
            throw new IllegalArgumentException("Upload slice exceeds native scratch");
        HEAP_UPLOAD.clear();
        HEAP_UPLOAD.put(source.duplicate());
        HEAP_UPLOAD.flip();
        return HEAP_UPLOAD;
    }

    static final class Range implements AutoCloseable {
        final GroundLodArena arena;
        final int vertices;
        int first;
        boolean closed;

        Range(GroundLodArena arena, int first, int vertices) {
            if (first < 0 || vertices <= 0) throw new IllegalArgumentException("Invalid GPU range");
            this.arena = arena;
            this.first = first;
            this.vertices = vertices;
        }

        int first() { return first; }
        int vertices() { return vertices; }
        int indices() { return vertices / 4 * 6; }

        public void close() {
            if (closed) return;
            closed = true;
            if (arena != null) {
                arena.live.remove(this);
                arena.ranges.free(first, vertices);
                arena.releaseIfEmpty();
            }
        }
    }

    /** Old offsets stay drawable until every live slice has reached the replacement buffer. */
    static final class Relocation {
        record Move(Range range, int source, int target) {}
        final List<Move> moves = new ArrayList<>();
        final int capacity;
        int index, copiedVertices;
        long remainingVertices;

        Relocation(Collection<Range> live, int capacity) {
            this.capacity = capacity;
            var sorted = new ArrayList<>(live);
            sorted.sort(Comparator.comparingInt(r -> r.first));
            int target = 0;
            for (Range range : sorted) {
                moves.add(new Move(range, range.first, target));
                target += range.vertices;
            }
            if (target > capacity) throw new IllegalArgumentException("Replacement cannot fit live ranges");
            remainingVertices = target;
        }

        void commit(Ranges ranges) {
            ranges.clear();
            ranges.capacity = capacity;
            int end = 0;
            for (Move move : moves) {
                if (move.range.closed) continue;
                if (move.target > end) ranges.free(end, move.target - end);
                move.range.first = move.target;
                end = move.target + move.range.vertices;
            }
            if (end < capacity) ranges.free(end, capacity - end);
        }
    }

    enum AllocationStatus {
        INVALID,
        REUSE,
        GROW,
        WAIT,
        REJECTED
    }

    /**
     * A render-thread snapshot, not a reservation. additionalBytes is the new GPU allocation at
     * peak, beside all storage already counted by bytes(); retainedBytesDelta applies only after
     * growth finishes. WAIT never promises a range: progress growth and query again. REJECTED
     * reports the smallest replacement's cost, even when it exceeds the budget or int capacity.
     */
    record AllocationPlan(
            AllocationStatus status,
            int first,
            long capacityVertices,
            long additionalBytes,
            long retainedBytesDelta) {}

    static final class Ranges {
        final TreeMap<Integer, Integer> free = new TreeMap<>();
        int capacity;

        int firstFit(int vertices) {
            if (vertices <= 0) return -1;
            for (var range : free.entrySet())
                if (range.getValue() >= vertices) return range.getKey();
            return -1;
        }

        int allocate(int vertices) {
            int first = firstFit(vertices);
            if (first < 0) return -1;
            int size = free.remove(first);
            if (size > vertices) free.put(first + vertices, size - vertices);
            return first;
        }

        int freeVertices() {
            int total = 0;
            for (int size : free.values()) total += size;
            return total;
        }

        int largestFreeRange() {
            int largest = 0;
            for (int size : free.values()) largest = Math.max(largest, size);
            return largest;
        }

        boolean isEmpty() {
            return capacity == 0
                    || (free.size() == 1 && Integer.valueOf(capacity).equals(free.get(0)));
        }

        /** Shared by the GL allocator and headless tests; never changes the free list. */
        AllocationPlan planAllocation(
                int vertices, int minimumCapacity, int pendingCapacity, long budget, int stride) {
            if (vertices <= 0)
                return new AllocationPlan(AllocationStatus.INVALID, -1, capacity, 0, 0);
            if (pendingCapacity > 0)
                return new AllocationPlan(AllocationStatus.WAIT, -1, pendingCapacity, 0, 0);
            int first = firstFit(vertices);
            if (first >= 0)
                return new AllocationPlan(AllocationStatus.REUSE, first, capacity, 0, 0);
            long required = (long) capacity - freeVertices() + vertices;
            // Pack holes on replacement, and reserve 25%, not another entire buffer's capacity.
            long next = Math.max((long) minimumCapacity, (required + required / 4 + 3) & ~3L);
            if (next > Integer.MAX_VALUE || next * stride > budget) {
                // Near the cap, retain affordable spare space instead of copying the whole pool per mesh.
                long affordable = Math.min(Integer.MAX_VALUE & ~3L, budget / stride & ~3L);
                next = capacity == 0 ? required : Math.max(required, Math.min(next, affordable));
            }
            var status =
                    next > Integer.MAX_VALUE || next * stride > budget
                            ? AllocationStatus.REJECTED
                            : AllocationStatus.GROW;
            return new AllocationPlan(status, -1, next, next * stride, (next - capacity) * stride);
        }

        /** A page never moves live ranges. Near the cap, only an empty page may be smaller. */
        AllocationPlan planPageAllocation(int vertices, int pageCapacity, long budget, int stride) {
            if (vertices <= 0) return new AllocationPlan(AllocationStatus.INVALID, -1, capacity, 0, 0);
            int first = firstFit(vertices);
            if (first >= 0) return new AllocationPlan(AllocationStatus.REUSE, first, capacity, 0, 0);
            long next = Math.max(vertices, pageCapacity);
            next = Math.max(vertices, Math.min(next, Math.max(0, budget / stride) & ~3L));
            boolean fits = capacity == 0 && next <= Integer.MAX_VALUE && next * stride <= budget;
            return new AllocationPlan(fits ? AllocationStatus.GROW : AllocationStatus.REJECTED,
                    -1, next, next * stride, next * stride);
        }

        AllocationPlan planTrim(int minimumCapacity, long budget, int stride) {
            long used = (long) capacity - freeVertices();
            long next = Math.max((long) minimumCapacity, (used + used / 8 + 3) & ~3L);
            // Hysteresis: ordinary growth's 20% spare capacity never immediately triggers trimming.
            if (used == 0 || next >= capacity || (long) capacity * stride < 1024 * 1024
                    || next * 4 > (long) capacity * 3)
                return new AllocationPlan(AllocationStatus.INVALID, -1, capacity, 0, 0);
            return new AllocationPlan(next * stride <= budget ? AllocationStatus.GROW : AllocationStatus.REJECTED,
                    -1, next, next * stride, (next - capacity) * stride);
        }

        void grow(int size) {
            free(capacity, size - capacity);
            capacity = size;
        }

        void clear() {
            free.clear();
            capacity = 0;
        }

        void free(int first, int vertices) {
            var before = free.lowerEntry(first);
            if (before != null && before.getKey() + before.getValue() == first) {
                first = before.getKey();
                vertices += free.remove(first);
            }
            var after = free.ceilingEntry(first);
            if (after != null && first + vertices == after.getKey())
                vertices += free.remove(after.getKey());
            free.put(first, vertices);
        }
    }

    private final int minimumCapacity;

    GroundLodArena() {
        this(65536);
    }

    GroundLodArena(int minimumCapacity) {
        this(minimumCapacity, false);
    }

    GroundLodArena(int minimumCapacity, boolean fixedPage) {
        this.minimumCapacity = minimumCapacity;
        this.fixedPage = fixedPage;
    }

    private final boolean fixedPage;
    private final Ranges ranges = new Ranges();
    private int vao;
    private int buffer, maxIndices;
    private int pendingBuffer, pendingCapacity;
    private Relocation relocation;
    private final Set<Range> live = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Commands commands = new Commands();
    private long commandRevision;

    long commandRevision() { return commandRevision; }

    static final class Commands implements AutoCloseable {
        int draws, end, maxIndices;
        long vertices, indices;
        private long epoch = -1, revision = -1;
        private boolean prepared;
        IntBuffer counts = MemoryUtil.memAllocInt(16), bases = MemoryUtil.memAllocInt(16);
        PointerBuffer offsets = MemoryUtil.memCallocPointer(16);

        boolean begin(int tiles, long epoch) {
            return begin(tiles, epoch, 0);
        }

        boolean begin(int tiles, long epoch, long revision) {
            if (this.epoch == epoch && this.revision == revision) {
                RenderWorkDiagnostics.add(FAR_ARENA_REUSES, 1);
                return false;
            }
            RenderWorkDiagnostics.add(FAR_ARENA_REBUILDS, 1);
            this.epoch = epoch;
            this.revision = revision;
            prepared = false;
            if (counts.capacity() < tiles) {
                int size = Integer.highestOneBit(tiles - 1) * 2;
                counts = MemoryUtil.memRealloc(counts, size);
                bases = MemoryUtil.memRealloc(bases, size);
                MemoryUtil.memFree(offsets);
                offsets = MemoryUtil.memCallocPointer(size);
            }
            counts.clear();
            bases.clear();
            offsets.clear();
            draws = 0;
            vertices = indices = 0;
            return true;
        }

        void add(Range range) {
            // Logical quad vertices and exact index elements, summed across input ranges.
            vertices += range.vertices;
            indices += range.indices();
            // Adjacent quad ranges share the sequential index buffer and one sub-draw.
            if (draws > 0 && end == range.first) {
                int merged = counts.get(draws - 1) + range.indices();
                counts.put(draws - 1, merged);
                maxIndices = Math.max(maxIndices, merged);
            } else {
                counts.put(range.indices());
                bases.put(range.first);
                maxIndices = Math.max(maxIndices, range.indices());
                draws++;
            }
            end = range.first + range.vertices;
        }

        void prepare() {
            RenderWorkDiagnostics.add(FAR_ARENA_COMMANDS, draws);
            if (prepared) return;
            counts.flip();
            bases.flip();
            offsets.limit(draws);
            prepared = true;
        }

        public void close() {
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(bases);
            MemoryUtil.memFree(offsets);
        }
    }

    /** Includes both allocations during growth; callers account for the delta on every retry. */
    long bytes() {
        return ((long) ranges.capacity + pendingCapacity) * GroundCompact.FORMAT.getVertexSize();
    }

    boolean isGrowing() {
        return pendingBuffer != 0;
    }

    /** Retained capacity and holes exclude the replacement until growth commits. */
    int capacityVertices() {
        return ranges.capacity;
    }

    int freeVertices() {
        return ranges.freeVertices();
    }

    int largestFreeRange() {
        return ranges.largestFreeRange();
    }

    /** No live or reserved ranges; an upload may still hold an arena reference while waiting. */
    boolean isEmpty() {
        return ranges.isEmpty();
    }

    /**
     * Pure admission query for allocate(vertices, budget, frame), independent of frame time/bytes.
     * Budget is additional GPU headroom after accounting for bytes(), including pending growth. A
     * reusable hole costs zero; growth charges the entire replacement, not just its final delta.
     * Requery immediately before allocating; other uploads/compactions can consume holes meanwhile.
     */
    AllocationPlan planAllocation(int vertices, long budget) {
        if (fixedPage)
            return ranges.planPageAllocation(vertices, minimumCapacity, budget, GroundCompact.FORMAT.getVertexSize());
        return ranges.planAllocation(
                vertices,
                minimumCapacity,
                pendingCapacity,
                budget,
                GroundCompact.FORMAT.getVertexSize());
    }

    /**
     * Advances pending growth without allocating a range, even if its original caller was canceled.
     * Returns true when no growth remains, including an idle arena. Account for the bytes() delta.
     */
    long pendingCopyBytes() {
        return relocation == null ? 0 : relocation.remainingVertices * GroundCompact.FORMAT.getVertexSize();
    }

    boolean progressGrowth(GroundUploadBudget frame) {
        return !isGrowing() || advanceGrowth(frame);
    }

    /** Discards a replacement; callers must account for the bytes() delta, as with allocate. */
    void cancelGrowth() {
        if (pendingBuffer != 0) GlStateManager._glDeleteBuffers(pendingBuffer);
        pendingBuffer = pendingCapacity = 0;
        relocation = null;
        releaseIfEmpty();
    }

    /** Shrinking is optional and shares the upload deadline, bandwidth and true peak allowance. */
    void trim(long headroom, GroundUploadBudget frame) {
        if (fixedPage || isGrowing() || !frame.available()) return;
        var plan = ranges.planTrim(minimumCapacity, headroom, GroundCompact.FORMAT.getVertexSize());
        if (plan.status == AllocationStatus.GROW) startReplacement(plan, frame);
    }

    void releaseIfEmpty() {
        if (buffer == 0 || isGrowing() || !ranges.isEmpty()) return;
        GlStateManager._glDeleteBuffers(buffer);
        buffer = 0;
        ranges.clear();
        maxIndices = 0;
    }

    /** Compatibility path: reuse holes or create an empty arena, but never copy live storage. */
    Range allocate(int vertices, long budget) {
        return allocate(vertices, budget, null);
    }

    /**
     * Budget is additional GPU headroom, so the entire replacement must fit beside the old buffer.
     * Returns null while growth is pending or cannot be admitted. Retry with a shared frame budget.
     * Growth belongs to the arena, not a requested range: any subsequent request can advance it,
     * including one with a different vertex count. No caller allocation is reserved before return.
     */
    private boolean allocationRejected;

    boolean allocationRejected() {
        return allocationRejected;
    }

    Range allocate(int vertices, long budget, GroundUploadBudget frame) {
        allocationRejected = false;
        if (vertices <= 0) return null;
        if (isGrowing() && (frame == null || !progressGrowth(frame))) return null;
        var plan = planAllocation(vertices, budget);
        int first;
        if (plan.status == AllocationStatus.REUSE) first = ranges.allocate(vertices);
        else {
            if (ranges.capacity > 0 && frame == null) return null;
            if (frame != null && !frame.available()) return null;
            if (plan.status != AllocationStatus.GROW) {
                allocationRejected = plan.status == AllocationStatus.REJECTED;
                return null;
            }
            startReplacement(plan, frame);
            if (!progressGrowth(frame)) return null;
            first = ranges.allocate(vertices);
        }
        var range = new Range(this, first, vertices);
        live.add(range);
        maxIndices = Math.max(maxIndices, range.indices());
        return range;
    }

    private void startReplacement(AllocationPlan plan, GroundUploadBudget frame) {
        var moves = new Relocation(live, (int) plan.capacityVertices);
        int next = GlStateManager._glGenBuffers();
        long storageWork = RenderWorkDiagnostics.start();
        long started = frame == null ? 0 : frame.startTransfer();
        try {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, next);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, plan.additionalBytes, GL15.GL_DYNAMIC_DRAW);
        } catch (RuntimeException | Error failure) {
            GlStateManager._glDeleteBuffers(next);
            throw failure;
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            if (frame != null) frame.endTransfer(started);
            RenderWorkDiagnostics.finish(FAR_LOD_STORAGE_NS, storageWork);
        }
        pendingBuffer = next;
        pendingCapacity = (int) plan.capacityVertices;
        relocation = moves;
    }

    private boolean advanceGrowth(GroundUploadBudget frame) {
        long started = RenderWorkDiagnostics.start();
        try { return advanceGrowthImpl(frame); }
        finally { RenderWorkDiagnostics.finish(FAR_LOD_GROWTH_NS, started); }
    }

    private boolean advanceGrowthImpl(GroundUploadBudget frame) {
        int stride = GroundCompact.FORMAT.getVertexSize();
        try {
            while (relocation.index < relocation.moves.size()) {
                var move = relocation.moves.get(relocation.index);
                if (move.range.closed || relocation.copiedVertices == move.range.vertices) {
                    relocation.remainingVertices -= move.range.vertices - relocation.copiedVertices;
                    relocation.index++;
                    relocation.copiedVertices = 0;
                    continue;
                }
                int slice = frame.take((int) Math.min(
                        (long) (move.range.vertices - relocation.copiedVertices) * stride,
                        GroundUploadBudget.SLICE_BYTES), stride);
                if (slice == 0) return false;
                long started = frame.startTransfer();
                try {
                    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
                    GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, pendingBuffer);
                    GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                            ((long) move.source + relocation.copiedVertices) * stride,
                            ((long) move.target + relocation.copiedVertices) * stride, slice);
                    relocation.copiedVertices += slice / stride;
                    relocation.remainingVertices -= slice / stride;
                    RenderWorkDiagnostics.add(FAR_LOD_RELOCATION_BYTES, slice);
                } finally {
                    frame.endTransfer(started);
                }
            }
        } catch (RuntimeException | Error failure) {
            cancelGrowth();
            throw failure;
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        }
        bind();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, pendingBuffer);
        GroundCompact.FORMAT.setupBufferState();
        VertexBuffer.unbind();
        int old = buffer;
        buffer = pendingBuffer;
        relocation.commit(ranges);
        pendingBuffer = pendingCapacity = 0;
        relocation = null;
        commandRevision++;
        commands.epoch = -1; // Cached base-vertex offsets changed, even if the draw topology did not.
        // On Linux MC rebinds the buffer to orphan it first; buffer 0 raises a GL error.
        if (old != 0) GlStateManager._glDeleteBuffers(old);
        // allocate() may still need to claim the first range in this new, empty VBO.
        return true;
    }

    /**
     * Uploads one bounded slice, returning bytes written (zero while growing or out of frame
     * budget). byteOffset is relative to data.position(); neither position nor limit is changed.
     * Retry with the accumulated byte offset. The source size and offset must be whole compact
     * vertices.
     */
    int uploadSlice(Range range, ByteBuffer data, int byteOffset, GroundUploadBudget frame) {
        int stride = GroundCompact.FORMAT.getVertexSize();
        if (range.arena != this
                || byteOffset < 0
                || byteOffset > data.remaining()
                || byteOffset % stride != 0
                || data.remaining() % stride != 0
                || data.remaining() > (long) range.vertices * stride)
            throw new IllegalArgumentException("Invalid arena upload range or byte offset");
        if (isGrowing()) return 0;
        int slice = frame.take(data.remaining() - byteOffset, stride);
        if (slice == 0) return 0;
        var source = data.duplicate();
        source.position(data.position() + byteOffset);
        source.limit(source.position() + slice);
        long started = frame.startTransfer();
        try {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            GL15.glBufferSubData(
                    GL31.GL_COPY_WRITE_BUFFER,
                    (long) range.first * stride + byteOffset,
                    uploadSource(source));
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            frame.endTransfer(started);
        }
        return slice;
    }

    boolean begin(int tiles, long epoch) {
        return commands.begin(tiles, epoch);
    }

    void add(Range range) {
        commands.add(range);
    }

    void draw() {
        draw(commands);
    }

    /** Independent chart command storage must not replace resident command generations. */
    void draw(Commands commands) {
        if (commands.draws == 0) return;
        maxIndices = Math.max(maxIndices, commands.maxIndices);
        int type = bindIndices();
        commands.prepare();
        long start = System.nanoTime();
        GL32.glMultiDrawElementsBaseVertex(
                GL11.GL_TRIANGLES, commands.counts, type, commands.offsets, commands.bases);
        GroundRenderer.probeMultiNanos += System.nanoTime() - start;
        GroundRenderer.probeDraws++;
        GroundRenderer.probeSubDraws += commands.draws;
        recordSubmission(commands.indices);
    }

    void draw(Range range) {
        maxIndices = Math.max(maxIndices, range.indices());
        int type = bindIndices();
        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, range.indices(), type, 0L, range.first);
        GroundRenderer.probeDraws++;
        recordSubmission(range.indices());
    }

    /** Called after each GL submission; counts logical quad vertices, not unique vertices. */
    static void recordSubmission(long indices) {
        if (RenderWorkDiagnostics.active()) {
            RenderWorkDiagnostics.add(FAR_DRAW_CALLS, 1);
            RenderWorkDiagnostics.add(FAR_VERTICES_SUBMITTED, indices / 6 * 4);
            RenderWorkDiagnostics.add(FAR_INDICES_SUBMITTED, indices);
        }
    }

    private void bind() {
        if (vao == 0) vao = GlStateManager._glGenVertexArrays();
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(vao);
    }

    private int bindIndices() {
        bind();
        var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        indices.bind(maxIndices);
        return indices.type().asGLType;
    }

    public void close() {
        cancelGrowth();
        if (buffer != 0) GlStateManager._glDeleteBuffers(buffer);
        if (vao != 0) GlStateManager._glDeleteVertexArrays(vao);
        commands.close();
    }
}
