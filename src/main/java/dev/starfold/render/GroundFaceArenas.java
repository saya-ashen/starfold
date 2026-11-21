package dev.starfold.render;

import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

/** Render-thread owner of six faces' shared compact vertex storage and transient native masks. */
final class GroundFaceArenas implements AutoCloseable {
    static final int FACES = 6, LAYERS = 4;
    static final int PAGE_VERTICES = (8 * 1024 * 1024 / 48) * 4;
    static final int MASK_WIDTH = 128, MASK_HEIGHT = FACES * 128;
    static final long MAX_BYTES = GroundGpuBudget.SELECTED.arenaBytes;
    static final String MASK_UNIFORM = "CPNativeMaskTex";

    /** cutout and cutout_mipped keep their own render state; tripwire joins translucent batches. */
    static int layerIndex(RenderType layer) {
        if (layer == RenderType.solid()) return 0;
        if (layer == RenderType.cutoutMipped()) return 1;
        if (layer == RenderType.cutout()) return 2;
        if (layer == RenderType.translucent() || layer == RenderType.tripwire()) return 3;
        throw new IllegalArgumentException("Unsupported terrain layer: " + layer);
    }

    static RenderType layer(int index) {
        return switch (index) {
            case 0 -> RenderType.solid();
            case 1 -> RenderType.cutoutMipped();
            case 2 -> RenderType.cutout();
            case 3 -> RenderType.translucent();
            default -> throw new IllegalArgumentException("Terrain layer: " + index);
        };
    }

    /**
     * Called once per nonempty (face, layer, pure/masked) batch, before shader.apply(). Bind all
     * projection, season, light, fog, texture and default matrix inputs here. The arena binds the
     * material palette and mask sampler. No per-node uniforms are needed.
     */
    @FunctionalInterface
    interface FaceUniformBinder {
        void bind(int face, RenderType layer, boolean masked, ShaderInstance shader);
    }

    /** A fully uploaded independent generation; close only after it leaves the selected cut. */
    static final class Slot implements AutoCloseable {
        private final GroundFaceArenas owner;
        final int face, layer;
        final GroundLodArena.Range range;
        private final int materialCount;
        private boolean closed;

        Slot(
                GroundFaceArenas owner,
                int face,
                int layer,
                GroundLodArena.Range range,
                int materialCount) {
            this.owner = owner;
            this.face = face;
            this.layer = layer;
            this.range = range;
            this.materialCount = materialCount;
        }

        long bytes() {
            return (long) range.vertices() * 12;
        }

        int vertices() {
            return range.vertices();
        }

        boolean closed() {
            return closed;
        }

        public void close() {
            if (!closed) {
                closed = true;
                if (!owner.closed) range.close();
            }
        }
    }

    /**
     * Source bytes remain caller-owned and immutable until finish or close. step() reserves a NEW
     * range and uploads bounded slices. The previous Slot stays drawable until finish succeeds.
     */
    final class Upload implements AutoCloseable {
        private final int face, layer;
        private final ByteBuffer source;
        private GroundLodArena.Range range;
        private GroundLodArena arena;
        private int offset, checkedBytes, materialCount;
        private boolean closed, allocationRejected;

        private Upload(int face, int layer, ByteBuffer source, int requiredMaterials) {
            this.face = face;
            this.layer = layer;
            this.source = source.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());
            if (requiredMaterials >= 0) {
                materialCount = requiredMaterials;
                checkedBytes = source.remaining();
            }
        }

        boolean step(GroundUploadBudget budget) {
            checkOpen();
            if (closed) throw new IllegalStateException("Closed upload");
            // Resolve the highest referenced 24-bit id in bounded CPU slices before allocating.
            while (checkedBytes < source.remaining()) {
                if (!budget.cpuAvailable()) return false;
                int end = Math.min(source.remaining(), checkedBytes + 256 * 12);
                while (checkedBytes < end) {
                    materialCount =
                            Math.max(
                                    materialCount,
                                    materialId(source, source.position() + checkedBytes) + 1);
                    checkedBytes += 12;
                }
            }
            allocationRejected = false;
            if (!materialsReady(materialCount)) {
                allocationRejected = reservedBytes() > maxBytes;
                if (allocationRejected) return false;
                prepareMaterials(budget);
                if (!materialsReady(materialCount)) return false;
            }
            if (range == null) {
                int vertices = source.remaining() / 12;
                arena = page(face, layer, vertices);
                range =
                        arena.allocate(
                                source.remaining() / 12,
                                Math.max(0, maxBytes - reservedBytes()),
                                budget);
                allocationRejected = arena.allocationRejected();
                if (range == null) return false;
            }
            if (!arena.progressGrowth(budget)) return false;
            while (offset < source.remaining()) {
                int copied = arena.uploadSlice(range, source, offset, budget);
                if (copied == 0) return false;
                offset += copied;
            }
            return true;
        }

        /** True only for GPU-cap rejection; a deadline/growth yield is retryable next frame. */
        boolean allocationRejected() {
            return allocationRejected;
        }

        int uploadedBytes() {
            return offset;
        }

        int remainingBytes() {
            return source.remaining() - offset;
        }

        Slot finish() {
            checkOpen();
            if (closed
                    || range == null
                    || offset != source.remaining()
                    || !materialsReady(materialCount))
                throw new IllegalStateException("Incomplete upload");
            closed = true;
            uploads.remove(this);
            return new Slot(GroundFaceArenas.this, face, layer, range, materialCount);
        }

        public void close() {
            if (!closed) {
                closed = true;
                uploads.remove(this);
                if (range != null && !GroundFaceArenas.this.closed) range.close();
            }
        }
    }

    record Draw(Slot slot, double distance, int order) {
        Draw(Slot slot, double distance) {
            this(slot, distance, 0);
        }
    }

    /** Opaque pages may regroup; translucent ranges retain global distance order across VBOs. */
    static final class PageCommands implements AutoCloseable {
        record Run(GroundLodArena arena, GroundLodArena.Commands commands, long revision,
                GroundLodArena.Range[] ranges, int[] firsts) {
            boolean matches(GroundLodArena page, List<Draw> group) {
                if (arena != page || revision != page.commandRevision() || ranges.length != group.size()) return false;
                for (int i = 0; i < ranges.length; i++) {
                    var range = group.get(i).slot.range;
                    if (ranges[i] != range || firsts[i] != range.first) return false;
                }
                return true;
            }
        }
        final List<Run> runs = new ArrayList<>();
        private long epoch = -1;

        boolean prepare(List<Draw> batch, long epoch, boolean translucent) {
            if (this.epoch == epoch && runs.stream().allMatch(run -> run.revision == run.arena.commandRevision())) {
                dev.starfold.api.RenderWorkDiagnostics.add(
                        dev.starfold.api.RenderWorkDiagnostics.Metric.FAR_ARENA_REUSES, runs.size());
                return false;
            }
            this.epoch = epoch;
            batch.sort(translucent ? FAR_FIRST : NEAR_FIRST);
            if (runs.size() == 1 && !batch.isEmpty()
                    && runs.getFirst().matches(batch.getFirst().slot.range.arena, batch)) {
                dev.starfold.api.RenderWorkDiagnostics.add(
                        dev.starfold.api.RenderWorkDiagnostics.Metric.FAR_ARENA_REUSES, 1);
                return false;
            }
            List<List<Draw>> groups = new ArrayList<>();
            if (translucent) {
                GroundLodArena previous = null;
                for (var draw : batch) {
                    var page = draw.slot.range.arena;
                    if (groups.isEmpty() || page != previous) groups.add(new ArrayList<>());
                    groups.getLast().add(draw);
                    previous = page;
                }
            } else {
                var pages = new LinkedHashMap<GroundLodArena, List<Draw>>();
                for (var draw : batch)
                    pages.computeIfAbsent(draw.slot.range.arena, ignored -> new ArrayList<>()).add(draw);
                groups.addAll(pages.values());
            }
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                var page = group.getFirst().slot.range.arena;
                if (i < runs.size() && runs.get(i).matches(page, group)) {
                    dev.starfold.api.RenderWorkDiagnostics.add(
                            dev.starfold.api.RenderWorkDiagnostics.Metric.FAR_ARENA_REUSES, 1);
                    continue;
                }
                var ranges = new GroundLodArena.Range[group.size()];
                var firsts = new int[group.size()];
                for (int j = 0; j < group.size(); j++) {
                    ranges[j] = group.get(j).slot.range;
                    firsts[j] = ranges[j].first;
                }
                var next = new Run(page, i == runs.size() ? new GroundLodArena.Commands() : runs.get(i).commands,
                        page.commandRevision(), ranges, firsts);
                if (i == runs.size()) runs.add(next);
                else runs.set(i, next);
                var commands = next.commands;
                if (commands.begin(group.size(), epoch, page.commandRevision()))
                    for (var draw : group) commands.add(draw.slot.range);
            }
            while (runs.size() > groups.size()) runs.removeLast().commands.close();
            return true;
        }

        public void close() {
            for (var run : runs) run.commands.close();
            runs.clear();
        }
    }

    private static final Comparator<Draw> BY_DISTANCE = Comparator.comparingDouble(Draw::distance);
    private static final Comparator<Draw> NEAR_FIRST = BY_DISTANCE.thenComparingInt(Draw::order);
    private static final Comparator<Draw> FAR_FIRST = BY_DISTANCE.reversed().thenComparingInt(Draw::order);
    private final List<GroundLodArena>[][] arenas = pages();

    @SuppressWarnings("unchecked")
    private static List<GroundLodArena>[][] pages() {
        List<GroundLodArena>[][] result = (List<GroundLodArena>[][]) new List<?>[FACES][LAYERS];
        for (int f = 0; f < FACES; f++) for (int l = 0; l < LAYERS; l++) result[f][l] = new ArrayList<>();
        return result;
    }
    static final class DrawBatch implements AutoCloseable {
        private final Map<Slot, Draw> draws = new LinkedHashMap<>();
        private final List<Draw> sorted = new ArrayList<>();
        final PageCommands commands = new PageCommands();
        private long revision, sortedRevision = -1;
        private boolean membershipChanged = true;

        boolean put(Slot slot, double distance, int order) {
            var previous = draws.get(slot);
            if (previous != null && previous.distance() == distance && previous.order() == order)
                return false;
            draws.put(slot, new Draw(slot, distance, order));
            membershipChanged |= previous == null;
            revision++;
            return true;
        }

        boolean remove(Slot slot) {
            if (draws.remove(slot) == null) return false;
            membershipChanged = true;
            revision++;
            return true;
        }

        boolean prepare(boolean translucent) {
            if (sortedRevision != revision) {
                if (membershipChanged) {
                    sorted.clear();
                    sorted.addAll(draws.values());
                    membershipChanged = false;
                } else {
                    // Keep the previous order as the input to the stable sort when only distance changes.
                    for (int i = 0; i < sorted.size(); i++)
                        sorted.set(i, draws.get(sorted.get(i).slot));
                }
                sortedRevision = revision;
            }
            return commands.prepare(sorted, revision, translucent);
        }

        public void close() {
            commands.close();
        }
    }

    static final class LayerBatches implements AutoCloseable {
        final Map<GroundLodArena, DrawBatch> pages = new LinkedHashMap<>();
        private final List<DrawBatch> ordered = new ArrayList<>();
        private final boolean translucent;
        private boolean orderDirty;

        LayerBatches(boolean translucent) {
            this.translucent = translucent;
        }

        boolean put(Slot slot, double distance, int order) {
            // Transparent draws share one sorted sequence across all of this layer's pages.
            var page = translucent ? null : slot.range.arena;
            boolean changed = pages.computeIfAbsent(page, ignored -> new DrawBatch()).put(slot, distance, order);
            orderDirty |= changed;
            return changed;
        }

        void remove(Slot slot) {
            var batch = pages.get(translucent ? null : slot.range.arena);
            if (batch != null) orderDirty |= batch.remove(slot);
        }

        List<DrawBatch> prepare() {
            if (orderDirty) {
                ordered.clear();
                var iterator = pages.values().iterator();
                while (iterator.hasNext()) {
                    var batch = iterator.next();
                    if (batch.draws.isEmpty()) {
                        batch.close();
                        iterator.remove();
                    } else {
                        batch.prepare(translucent);
                        ordered.add(batch);
                    }
                }
                if (!translucent)
                    ordered.sort((a, b) -> NEAR_FIRST.compare(a.sorted.getFirst(), b.sorted.getFirst()));
                orderDirty = false;
            } else {
                for (var batch : ordered) batch.prepare(translucent);
            }
            return ordered;
        }

        public void close() {
            for (var batch : pages.values()) batch.close();
        }
    }

    private static final class Submissions implements AutoCloseable {
        final LayerBatches[][][] batches = new LayerBatches[FACES][LAYERS][2];

        LayerBatches batch(Slot slot, boolean masked) {
            int pass = masked ? 1 : 0;
            var batch = batches[slot.face][slot.layer][pass];
            if (batch == null)
                batches[slot.face][slot.layer][pass] = batch = new LayerBatches(slot.layer == LAYERS - 1);
            return batch;
        }

        public void close() {
            for (var face : batches) for (var layer : face) for (var batch : layer)
                if (batch != null) batch.close();
        }
    }
    private final Submissions[] passes = {new Submissions(), new Submissions()};
    private Submissions submissions = passes[0];
    private final Set<Upload> uploads = Collections.newSetFromMap(new IdentityHashMap<>());
    private final long maxBytes;
    private final NativeMask nativeMask;
    private boolean closed;

    GroundFaceArenas() {
        this(MAX_BYTES);
    }

    GroundFaceArenas(long maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative GPU allowance");
        this.maxBytes = Math.min(MAX_BYTES, maxBytes);
        nativeMask = new NativeMask();
    }

    private GroundLodArena page(int face, int layer, int vertices) {
        // Translucent order alternates pages thousands of times; keep its single multidraw VBO.
        if (layer == LAYERS - 1) {
            if (arenas[face][layer].isEmpty()) arenas[face][layer].add(new GroundLodArena());
            return arenas[face][layer].getFirst();
        }
        for (var arena : arenas[face][layer])
            if (arena.largestFreeRange() >= vertices) return arena;
        for (var arena : arenas[face][layer])
            if (arena.capacityVertices() == 0) return arena;
        var arena = new GroundLodArena(PAGE_VERTICES, true);
        arenas[face][layer].add(arena);
        return arena;
    }

    /** Exact layer sizes, including old/new coexistence for the contiguous translucent pool. */
    GroundLodArena.AllocationStatus admission(int face, int[] vertices) {
        long headroom = Math.max(0, maxBytes - reservedBytes());
        boolean waiting = false;
        for (int layer = 0; layer < LAYERS; layer++) {
            if (vertices[layer] == 0) continue;
            if (layer == LAYERS - 1) {
                var plan = page(face, layer, vertices[layer]).planAllocation(vertices[layer], headroom);
                if (plan.status() == GroundLodArena.AllocationStatus.REJECTED) return plan.status();
                waiting |= plan.status() == GroundLodArena.AllocationStatus.WAIT;
                headroom -= plan.additionalBytes();
                continue;
            }
            boolean reuse = false;
            for (var arena : arenas[face][layer])
                if (arena.largestFreeRange() >= vertices[layer]) { reuse = true; break; }
            if (reuse) continue;
            var plan = new GroundLodArena.Ranges().planPageAllocation(vertices[layer], PAGE_VERTICES, headroom, 12);
            if (plan.status() == GroundLodArena.AllocationStatus.REJECTED) return plan.status();
            headroom -= plan.additionalBytes();
        }
        return waiting ? GroundLodArena.AllocationStatus.WAIT : GroundLodArena.AllocationStatus.REUSE;
    }

    Upload beginUpload(int face, RenderType layer, ByteBuffer source) {
        return beginUpload(face, layer, source, -1);
    }

    /** Trusted completed palette mapping supplies its global boundary; legacy callers scan. */
    Upload beginUpload(int face, RenderType layer, ByteBuffer source, int requiredMaterials) {
        checkOpen();
        if (requiredMaterials < -1 || requiredMaterials > 0x1000000)
            throw new IllegalArgumentException("Material boundary");
        if (face < 0 || face >= FACES || source.remaining() == 0 || source.remaining() % 48 != 0)
            throw new IllegalArgumentException("Expected nonempty face-local compact quads");
        Upload upload = new Upload(face, layerIndex(layer), source, requiredMaterials);
        uploads.add(upload);
        return upload;
    }

    /** Keep the old slot live until the replacement finishes; only affected ranges coexist. */
    Slot replace(Slot previous, Slot replacement) {
        validate(replacement);
        if (previous != null) {
            validate(previous);
            if (previous.face != replacement.face || previous.layer != replacement.layer)
                throw new IllegalArgumentException("Replacement face/layer differs");
            if (previous != replacement) previous.close();
        }
        return replacement;
    }

    /** Compact ids are stored as low 16 bits plus the upper byte of Packed.y. */
    static int materialId(ByteBuffer source, int offset) {
        return Short.toUnsignedInt(source.getShort(offset + 6))
                | (Short.toUnsignedInt(source.getShort(offset + 8)) >>> 8) << 16;
    }

    boolean materialsReady(int requiredCount) {
        checkOpen();
        return GroundCompact.materialsReady(requiredCount);
    }

    /** Call after CPU material resolution, sharing the same budget as VBO uploads/copies. */
    boolean prepareMaterials(GroundUploadBudget budget) {
        checkOpen();
        long nonPalette =
                bytes()
                        - GroundCompact.gpuBytes()
                        - nativeMask.bytes()
                        + (long) MASK_WIDTH * MASK_HEIGHT;
        return GroundCompact.prepareMaterials(budget, Math.max(0, maxBytes - nonPalette));
    }

    long pendingCopyBytes() {
        long bytes = 0;
        for (var face : arenas) for (var arena : face[LAYERS - 1]) bytes += arena.pendingCopyBytes();
        return bytes;
    }

    void progressGrowth(GroundUploadBudget budget) {
        checkOpen();
        for (var face : arenas) for (var arena : face[LAYERS - 1]) {
            arena.progressGrowth(budget);
            arena.trim(Math.max(0, maxBytes - reservedBytes()), budget);
        }
    }

    /** Opaque fixed pages, translucent VBOs, compact palette and native-mask texture. */
    long bytes() {
        long bytes = GroundCompact.gpuBytes() + nativeMask.bytes();
        for (var face : arenas) for (var pages : face) for (var arena : pages) bytes += arena.bytes();
        return bytes;
    }

    /** Current VBO peak plus the palette and mask storage required for the next draw. */
    long reservedBytes() {
        return bytes()
                - GroundCompact.gpuBytes()
                - nativeMask.bytes()
                + GroundCompact.requiredGpuBytes()
                + (long) MASK_WIDTH * MASK_HEIGHT;
    }

    /** Holes are an optimistic allowance only: pools/layers and growth peaks still gate admission. */
    long reusableBytes() {
        long bytes = Math.max(0, maxBytes - reservedBytes());
        for (var face : arenas) for (var pages : face) for (var arena : pages)
            bytes += (long) arena.freeVertices() * 12;
        return bytes;
    }

    void selectPass(boolean translucent) {
        checkOpen();
        submissions = passes[translucent ? 1 : 0];
    }

    boolean putDraw(Slot slot, double distance, boolean masked, int order) {
        validate(slot);
        return submissions.batch(slot, masked).put(slot, distance, order);
    }

    void removeDraw(Slot slot, boolean masked) {
        // A replacement may have already released its old slot when the cut is reconciled.
        submissions.batch(slot, masked).remove(slot);
    }

    /** Native tile index is face*16384 + z*128 + x; use the current opaque/water pass set. */
    boolean updateNativeMask(BitSet visible) {
        checkOpen();
        return nativeMask.update(visible);
    }

    int nativeMaskTexture() {
        checkOpen();
        nativeMask.ensureTexture();
        return nativeMask.texture;
    }

    /**
     * Returns actual MultiDraw calls. Pure batches precede the transient masked subpass. Tripwire
     * geometry is submitted with translucent, so looping all vanilla chunk layers never draws twice.
     */
    int drawLayer(RenderType layer, FaceUniformBinder binder) {
        checkOpen();
        if (layer == RenderType.tripwire()) return 0;
        // Pending CPU registrations cannot invalidate drawing from an already published palette.
        if (bytes() - nativeMask.bytes() + (long) MASK_WIDTH * MASK_HEIGHT > maxBytes)
            throw new IllegalStateException("Compact GPU allowance exceeded");
        int l = layerIndex(layer), draws = 0;
        boolean wasClipping = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
        layer.setupRenderState();
        try {
            GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            for (int pass = 0; pass < 2; pass++) {
                boolean isMasked = pass != 0;
                for (int f = 0; f < FACES; f++) {
                    var batch = submissions.batches[f][l][pass];
                    if (batch == null) continue;
                    var pages = batch.prepare();
                    if (pages.isEmpty()) continue;
                    ShaderInstance shader = GroundCompact.shader(layer, isMasked);
                    if (isMasked) {
                        nativeMask.ensureTexture();
                        shader.setSampler(MASK_UNIFORM, nativeMask.texture);
                    }
                    binder.bind(f, layer, isMasked, shader);
                    try {
                        shader.apply();
                        for (var page : pages)
                            for (var run : page.commands.runs) {
                                run.arena.draw(run.commands);
                                draws++;
                            }
                    } finally {
                        shader.clear();
                    }
                }
            }
        } finally {
            try {
                VertexBuffer.unbind();
                layer.clearRenderState();
            } finally {
                if (wasClipping) GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
                else GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            }
        }
        return draws;
    }

    private void validate(Slot slot) {
        checkOpen();
        if (slot == null || slot.owner != this || slot.closed)
            throw new IllegalArgumentException("Foreign or released slot");
        if (!materialsReady(slot.materialCount))
            throw new IllegalStateException("Slot references unpublished compact materials");
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Closed face arenas");
    }

    /** Pure CPU image comparison is shared with offline tests. A single byte marks a native tile. */
    static final class NativeMask implements AutoCloseable {
        private final BitSet visible = new BitSet(MASK_WIDTH * MASK_HEIGHT);
        private final ByteBuffer image = MemoryUtil.memCalloc(MASK_WIDTH * MASK_HEIGHT);
        private int texture;
        private boolean dirty = true, closed;

        boolean update(BitSet next) {
            if (closed) throw new IllegalStateException("Closed native mask");
            if (next.length() > MASK_WIDTH * MASK_HEIGHT)
                throw new IllegalArgumentException("Native tile outside six faces");
            if (visible.equals(next)) return false;
            visible.clear();
            visible.or(next);
            for (int i = 0; i < image.capacity(); i++) image.put(i, (byte) (next.get(i) ? 1 : 0));
            dirty = true;
            return true;
        }

        int value(int face, int x, int z) {
            return image.get(face * 16384 + z * 128 + x);
        }

        long bytes() {
            return texture == 0 ? 0 : (long) MASK_WIDTH * MASK_HEIGHT;
        }

        void ensureTexture() {
            if (closed) throw new IllegalStateException("Closed native mask");
            if (!dirty) return;
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int[] fields = {
                GL11.GL_UNPACK_ALIGNMENT,
                GL11.GL_UNPACK_ROW_LENGTH,
                GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS
            };
            int[] saved = new int[fields.length];
            int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            try {
                for (int i = 0; i < fields.length; i++) {
                    saved[i] = GL11.glGetInteger(fields[i]);
                    GL11.glPixelStorei(fields[i], i == 0 ? 1 : 0);
                }
                if (texture == 0) {
                    texture = GL11.glGenTextures();
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                    GL11.glTexImage2D(
                            GL11.GL_TEXTURE_2D,
                            0,
                            GL30.GL_R8UI,
                            MASK_WIDTH,
                            MASK_HEIGHT,
                            0,
                            GL30.GL_RED_INTEGER,
                            GL11.GL_UNSIGNED_BYTE,
                            image);
                    GL11.glTexParameteri(
                            GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(
                            GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(
                            GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(
                            GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                } else {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                    GL11.glTexSubImage2D(
                            GL11.GL_TEXTURE_2D,
                            0,
                            0,
                            0,
                            MASK_WIDTH,
                            MASK_HEIGHT,
                            GL30.GL_RED_INTEGER,
                            GL11.GL_UNSIGNED_BYTE,
                            image);
                }
                dirty = false;
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
                for (int i = 0; i < fields.length; i++) GL11.glPixelStorei(fields[i], saved[i]);
            }
        }

        public void close() {
            if (closed) return;
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = 0;
            MemoryUtil.memFree(image);
            closed = true;
        }
    }

    public void close() {
        if (closed) return;
        for (Upload upload : List.copyOf(uploads)) upload.close();
        for (var face : arenas) for (var pages : face) for (var arena : pages) arena.close();
        nativeMask.close();
        for (var pass : passes) pass.close();
        closed = true;
    }
}
