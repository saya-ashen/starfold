package dev.starfold.render;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Worker-safe node geometry. No GL, model, sprite, RenderType or live light access occurs here.
 *
 * <p>Integration: build() returns GC-managed 12-byte staging vertices in standard face-local
 * coordinates (voxel Y minus 128). The material word is a LOCAL descriptor index, NOT a
 * GroundCompact palette id. Tag bits 0..1 identify the corner, bit 2 selects the upper Y residual;
 * light is zero. Call resolve() on the render thread: its callback owns model/sprite/tint lookup,
 * material registration, water epsilon and live light sampling. Only the resulting per-layer
 * buffers may enter face arenas. No old tile-position encoding is applied.
 *
 * <p>Resource reload invalidates resolved buffers; staging can be resolved again. The scheduler
 * must reject stale planet/session/overlay versions before resolving or publishing. Neither Built
 * nor Resolved retains Quad objects. Built uses heap staging; Resolved uses JVM-managed direct
 * buffers suitable for the arena's LWJGL upload. All buffers are read-only independent views, with
 * native byte order; callers need no manual native free or GL cleanup on cancellation.
 */
public final class GroundQuadtreeMesher {
    public static final int STRIDE = 12;

    /**
     * Quad-wide descriptor, axis 0=X/1=Y/2=Z, normal follows emitted winding. RGB uses the same
     * -1/default, -2/coarse leaves, -3/coarse dry top hints as GroundFarMesh. Exact Y bounds and
     * quantization residuals preserve ninth-block fluid surfaces and let light sample a closure's
     * exposed rim. X/Z use the existing compact 1/16-block quantization (including crossed plants).
     */
    public record Material(
            BlockState block,
            int axis,
            boolean volume,
            int rgb,
            double normalX,
            double normalY,
            double normalZ,
            double bottomY,
            double topY,
            double lowerHeightOffset,
            double upperHeightOffset) {}

    /** Palette id is unsigned 24 bits; tag/light use existing compact 8/16-bit layouts. */
    public record ResolvedVertex(int material, int tag, int light, int layer) {
        public ResolvedVertex {
            if (material < 0
                    || material > 0xffffff
                    || (tag & ~255) != 0
                    || (light & ~65535) != 0
                    || layer < 0)
                throw new IllegalArgumentException("Compact material/tag/light/layer");
        }
    }

    @FunctionalInterface
    public interface Resolver {
        /**
         * Called on the calling thread, once per vertex, in quad order. Y is exact; X/Z are compact
         * quantized. For fluid register a palette material with y-round(y*16)/16 and vanilla water
         * epsilon as appropriate. Use Material.topY() for closure-wall rim light. Return one layer
         * for all four corners; returned tag must include the renderer's desired corner/UV
         * metadata.
         */
        ResolvedVertex resolve(Material material, int corner, double x, double y, double z);
    }

    public record Bounds(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    public static final class Built {
        private final Face face;
        private final ByteBuffer vertices;
        private final List<Material> materials;
        private final Bounds bounds;

        Built(Face face, ByteBuffer vertices, List<Material> materials, Bounds bounds) {
            this.face = face;
            this.vertices = view(vertices);
            this.materials = List.copyOf(materials);
            this.bounds = bounds;
        }

        public Face face() {
            return face;
        }

        public int vertexCount() {
            return vertices.remaining() / STRIDE;
        }

        public long bytes() {
            return vertices.remaining();
        }

        public ByteBuffer vertices() {
            return view(vertices);
        }

        public List<Material> materials() {
            return materials;
        }

        /** Empty meshes have all-zero bounds. Bounds include the exact fluid Y residual. */
        public Bounds bounds() {
            return bounds;
        }
    }

    public static final class Resolved {
        private final Map<Integer, ByteBuffer> layers;

        private Resolved(Map<Integer, ByteBuffer> layers) {
            var copy = new LinkedHashMap<Integer, ByteBuffer>();
            layers.forEach((layer, buffer) -> copy.put(layer, view(buffer)));
            this.layers = Collections.unmodifiableMap(copy);
        }

        /**
         * Layer ids are integration-defined; values contain standard GroundCompact 12-byte
         * vertices.
         */
        public Map<Integer, ByteBuffer> layers() {
            var copy = new LinkedHashMap<Integer, ByteBuffer>();
            layers.forEach((layer, buffer) -> copy.put(layer, view(buffer)));
            return Collections.unmodifiableMap(copy);
        }

        public int vertexCount() {
            int count = 0;
            for (var buffer : layers.values()) count += buffer.remaining() / STRIDE;
            return count;
        }
    }

    /**
     * Builds a single aligned square node. Columns must be an immutable multi-run snapshot
     * including the real, canonically wrapped one-step halo; callers may adapt GroundBaseCache or
     * edited captures here. No live level access is permitted in that function. neighborMinY is an
     * ABSOLUTE voxel edge (not sea-relative). The lowest solid skirt reaches
     * min(run.bottom,neighborMinY-4). Steps <=16 merge only inside global 16-block cells, steps
     * >=32 merge freely.
     */
    public static Built build(
            Face face,
            int x,
            int z,
            int size,
            int step,
            int neighborMinY,
            VoxelMesher.Columns columns) {
        return build(face, x, z, size, step, neighborMinY, 2048, columns);
    }

    /** A fixed tree square may straddle the end of a smaller, 16-aligned planet face. */
    public static Built build(
            Face face, int x, int z, int size, int step, int neighborMinY,
            int faceSize, VoxelMesher.Columns columns) {
        Objects.requireNonNull(face);
        Objects.requireNonNull(columns);
        if (x < 0
                || z < 0
                || size < 16
                || size > 256
                || (size & (size - 1)) != 0
                || x % size != 0
                || z % size != 0
                || step < 1
                || step > 64
                || (step & (step - 1)) != 0
                || step > size) throw new IllegalArgumentException("Aligned quadtree node/step");
        if (faceSize < 256 || faceSize > 2048 || faceSize % 16 != 0
                || x >= faceSize || z >= faceSize)
            throw new IllegalArgumentException("Planet face bounds");
        int floor = Math.subtractExact(neighborMinY, 4);
        // Temporary topology only: no Quad/Point graph survives this method.
        var quads = VoxelMesher.boxes(x, z, Math.min(faceSize, x + size),
                Math.min(faceSize, z + size), step, columns, step <= 16 ? 16 : 0, floor);
        var vertices =
                ByteBuffer.allocate(Math.multiplyExact(quads.size(), 4 * STRIDE))
                        .order(ByteOrder.nativeOrder());
        var ids = new HashMap<Material, Integer>();
        var materials = new ArrayList<Material>();
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var quad : quads) {
            var p = quad.points();
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (var point : p) {
                low = Math.min(low, point.y() - 128);
                high = Math.max(high, point.y() - 128);
            }
            var n = p.get(1).sub(p.getFirst()).cross(p.get(2).sub(p.getFirst()));
            double length = Math.sqrt(n.dot(n));
            int rgb =
                    step >= 4 && quad.material().is(net.minecraft.tags.BlockTags.LEAVES)
                            ? -2
                            : step >= 4
                                            && quad.axis() == 3
                                            && quad.material().getFluidState().isEmpty()
                                    ? -3
                                    : quad.rgb();
            var material =
                    new Material(
                            quad.material(),
                            quad.axis() / 2,
                            quad.volume(),
                            rgb,
                            n.x() / length,
                            n.y() / length,
                            n.z() / length,
                            low,
                            high,
                            residual(low),
                            residual(high));
            int id =
                    ids.computeIfAbsent(
                            material,
                            value -> {
                                if (materials.size() > 0xffffff)
                                    throw new IllegalArgumentException("Material cap");
                                materials.add(value);
                                return materials.size() - 1;
                            });
            for (int corner = 0; corner < 4; corner++) {
                var point = p.get(corner);
                double y = point.y() - 128;
                put(vertices, point.x(), y, point.z(), id, corner | (y == high ? 4 : 0), 0);
                minX = Math.min(minX, quantize(point.x()));
                maxX = Math.max(maxX, quantize(point.x()));
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, quantize(point.z()));
                maxZ = Math.max(maxZ, quantize(point.z()));
            }
        }
        vertices.flip();
        var bounds =
                quads.isEmpty()
                        ? new Bounds(0, 0, 0, 0, 0, 0)
                        : new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        return new Built(face, vertices, materials, bounds);
    }

    /**
     * Existing immutable base/edited Field adapter, including canonical cube-face halo sampling.
     */
    static Built build(
            dev.starfold.chart.PlanetShape shape,
            Face face,
            int x,
            int z,
            int size,
            int step,
            int neighborMinY,
            GroundFarMesh.Field field) {
        var cached = GroundFarMesh.cached(shape, field);
        return build(
                face,
                x,
                z,
                size,
                step,
                neighborMinY,
                shape.size(),
                (u, v) -> cached.cell(face, u, v, step).column());
    }

    /** CPU-only patching; caller must invoke on the render thread if resolver touches game APIs. */
    public static Resolved resolve(Built built, Resolver resolver) {
        try (var resolving = beginResolve(built, resolver)) {
            resolving.step(Integer.MAX_VALUE, () -> true);
            return resolving.finish();
        }
    }

    /**
     * Render-thread continuation for integration with existing vertex/CPU upload budgets. No
     * callback is invoked until step(). Keep one continuation per node, close on cancellation or
     * reload, and restart from Built after palette invalidation. Staged bytes must never be
     * uploaded directly.
     */
    public static Resolving beginResolve(Built built, Resolver resolver) {
        return new Resolving(Objects.requireNonNull(built), Objects.requireNonNull(resolver));
    }

    /** Single-caller continuation; only complete quads are processed or published. */
    public static final class Resolving implements AutoCloseable {
        private Built built;
        private Resolver resolver;
        private ByteBuffer input;
        private final Map<Integer, ByteBuffer> buffers = new LinkedHashMap<>();
        private int offset;
        private boolean closed;

        private Resolving(Built built, Resolver resolver) {
            this.built = built;
            this.resolver = resolver;
            input = built.vertices();
        }

        /**
         * Returns vertices processed, rounded down to complete quads. maxVertices <4 does no work;
         * a negative budget is invalid. cpuAvailable is checked before each quad, including the
         * first, so callers can share a deadline across nodes. Callback failure closes this job.
         */
        public int step(int maxVertices, BooleanSupplier cpuAvailable) {
            active();
            if (maxVertices < 0) throw new IllegalArgumentException("Negative vertex budget");
            Objects.requireNonNull(cpuAvailable);
            int processed = 0;
            try {
                while (offset < input.limit()
                        && maxVertices - processed >= 4
                        && cpuAvailable.getAsBoolean()) {
                    int quadLayer = -1;
                    for (int corner = 0; corner < 4; corner++, offset += STRIDE) {
                        int tag = Short.toUnsignedInt(input.getShort(offset + 8));
                        int id =
                                Short.toUnsignedInt(input.getShort(offset + 6)) | (tag >>> 8) << 16;
                        var material = built.materials.get(id);
                        double x = Short.toUnsignedInt(input.getShort(offset)) / 16.;
                        double y =
                                Short.toUnsignedInt(input.getShort(offset + 2)) / 16.
                                        - 2048
                                        + ((tag & 4) == 0
                                                ? material.lowerHeightOffset
                                                : material.upperHeightOffset);
                        double z = Short.toUnsignedInt(input.getShort(offset + 4)) / 16.;
                        var resolved =
                                Objects.requireNonNull(
                                        resolver.resolve(material, tag & 3, x, y, z));
                        if (corner == 0) quadLayer = resolved.layer;
                        else if (resolved.layer != quadLayer)
                            throw new IllegalArgumentException("Split quad layer");
                        var target = buffers.get(resolved.layer);
                        if (target == null) {
                            target =
                                    ByteBuffer.allocateDirect(
                                                    Math.min(input.limit(), 4096 * STRIDE))
                                            .order(ByteOrder.nativeOrder());
                        } else if (target.remaining() < STRIDE) {
                            var grown =
                                    ByteBuffer.allocateDirect(
                                                    (int)
                                                            Math.min(
                                                                    input.limit(),
                                                                    (long) target.capacity() * 2))
                                            .order(ByteOrder.nativeOrder());
                            target.flip();
                            grown.put(target);
                            target = grown;
                        }
                        buffers.put(resolved.layer, target);
                        put(target, x, y, z, resolved.material, resolved.tag, resolved.light);
                    }
                    processed += 4;
                }
                return processed;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        public boolean done() {
            active();
            return offset == input.limit();
        }

        /** Only completed jobs can expose arena-ready buffers. Returned views outlive close(). */
        public Resolved finish() {
            if (!done()) throw new IllegalStateException("Resolution incomplete");
            var complete = new LinkedHashMap<Integer, ByteBuffer>();
            buffers.forEach((layer, buffer) -> complete.put(layer, buffer.duplicate().flip()));
            return new Resolved(complete);
        }

        private void active() {
            if (closed) throw new IllegalStateException("Resolution closed");
        }

        @Override
        public void close() {
            closed = true;
            built = null;
            resolver = null;
            input = null;
            buffers.clear();
        }
    }

    private static double quantize(double value) {
        return Math.round(value * 16) / 16.;
    }

    private static double residual(double value) {
        return value - quantize(value);
    }

    private static ByteBuffer view(ByteBuffer buffer) {
        return buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private static void put(
            ByteBuffer buffer, double x, double y, double z, int material, int tag, int light) {
        buffer.putShort(packed(x))
                .putShort(packed(y + 2048))
                .putShort(packed(z))
                .putShort((short) material)
                .putShort((short) (tag | (material >>> 16) << 8))
                .putShort((short) light);
    }

    private static short packed(double value) {
        long scaled = Math.round(value * 16);
        if (!Double.isFinite(value) || scaled < 0 || scaled > 65535)
            throw new IllegalArgumentException("Vertex outside compact chart range");
        return (short) scaled;
    }

    private GroundQuadtreeMesher() {}
}
