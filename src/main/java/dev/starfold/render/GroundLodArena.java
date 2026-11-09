package dev.starfold.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.IntBuffer;
import java.util.TreeMap;

/** One face/layer's vertices. Buffer growth preserves offsets; group compaction replaces ranges. */
final class GroundLodArena implements AutoCloseable {
    record Range(GroundLodArena arena, int first, int vertices) implements AutoCloseable {
        int indices() { return vertices / 4 * 6; }
        Range slice(int offset, int size) { return new Range(arena, first + offset, size); }
        public void close() { arena.ranges.free(first, vertices); }
    }

    static final class Ranges {
        final TreeMap<Integer, Integer> free = new TreeMap<>();
        int capacity;

        int allocate(int vertices) {
            for (var it = free.entrySet().iterator(); it.hasNext();) {
                var range = it.next();
                int first = range.getKey(), size = range.getValue();
                if (size < vertices) continue;
                it.remove();
                if (size > vertices) free.put(first + vertices, size - vertices);
                return first;
            }
            return -1;
        }

        void grow(int size) {
            free(capacity, size - capacity);
            capacity = size;
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

    private final Ranges ranges = new Ranges();
    private final int vao = GlStateManager._glGenVertexArrays();
    private int buffer, maxIndices;
    private final Commands commands = new Commands();

    static final class Commands implements AutoCloseable {
        int draws, end, maxIndices;
        private long epoch = -1;
        private boolean prepared;
        IntBuffer counts = MemoryUtil.memAllocInt(16), bases = MemoryUtil.memAllocInt(16);
        PointerBuffer offsets = MemoryUtil.memCallocPointer(16);

        boolean begin(int tiles, long epoch) {
            if (this.epoch == epoch) return false;
            this.epoch = epoch;
            prepared = false;
            if (counts.capacity() < tiles) {
                int size = Integer.highestOneBit(tiles - 1) * 2;
                counts = MemoryUtil.memRealloc(counts, size);
                bases = MemoryUtil.memRealloc(bases, size);
                MemoryUtil.memFree(offsets);
                offsets = MemoryUtil.memCallocPointer(size);
            }
            counts.clear(); bases.clear(); offsets.clear();
            draws = 0;
            return true;
        }

        void add(Range range) {
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
            if (prepared) return;
            counts.flip(); bases.flip(); offsets.limit(draws);
            prepared = true;
        }

        public void close() {
            MemoryUtil.memFree(counts); MemoryUtil.memFree(bases); MemoryUtil.memFree(offsets);
        }
    }

    long bytes() { return (long)ranges.capacity * GroundCompact.FORMAT.getVertexSize(); }

    Range allocate(int vertices, long budget) {
        int first = ranges.allocate(vertices);
        if (first < 0) {
            int capacity = Math.max(65536, Math.max(ranges.capacity * 2, ranges.capacity + vertices));
            int stride = GroundCompact.FORMAT.getVertexSize();
            if ((long)(capacity - ranges.capacity) * stride > budget) capacity = ranges.capacity + vertices;
            if ((long)(capacity - ranges.capacity) * stride > budget) return null;
            int next = GlStateManager._glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, next);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                    (long)capacity * GroundCompact.FORMAT.getVertexSize(), GL15.GL_DYNAMIC_DRAW);
            if (ranges.capacity > 0) {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, bytes());
            }
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            // On Linux MC rebinds the buffer to orphan it first; buffer 0 raises a GL error.
            if (buffer != 0) GlStateManager._glDeleteBuffers(buffer);
            buffer = next;
            ranges.grow(capacity);
            first = ranges.allocate(vertices);
            bind();
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GroundCompact.FORMAT.setupBufferState();
            VertexBuffer.unbind();
        }
        var range = new Range(this, first, vertices);
        maxIndices = Math.max(maxIndices, range.indices());
        return range;
    }

    Range upload(MeshData data, long budget) {
        var range = allocate(data.drawState().vertexCount(), budget);
        if (range == null) return null;
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER,
                (long)range.first * GroundCompact.FORMAT.getVertexSize(), data.vertexBuffer());
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        return range;
    }

    void copy(Range source, Range target) {
        int stride = GroundCompact.FORMAT.getVertexSize();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                (long)source.first * stride, (long)target.first * stride, (long)source.vertices * stride);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    boolean begin(int tiles, long epoch) { return commands.begin(tiles, epoch); }

    void add(Range range) { commands.add(range); }

    void draw() {
        if (commands.draws == 0) return;
        maxIndices = Math.max(maxIndices, commands.maxIndices);
        int type = bindIndices();
        commands.prepare();
        long start = System.nanoTime();
        GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, commands.counts, type, commands.offsets, commands.bases);
        GroundRenderer.probeMultiNanos += System.nanoTime() - start;
        GroundRenderer.probeDraws++;
        GroundRenderer.probeSubDraws += commands.draws;
    }

    void draw(Range range) {
        int type = bindIndices();
        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, range.indices(), type, 0L, range.first);
        GroundRenderer.probeDraws++;
    }

    private void bind() {
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
        if (buffer != 0) GlStateManager._glDeleteBuffers(buffer);
        GlStateManager._glDeleteVertexArrays(vao);
        commands.close();
    }
}
