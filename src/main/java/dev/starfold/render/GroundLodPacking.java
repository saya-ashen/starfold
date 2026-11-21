package dev.starfold.render;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.lod.VoxelMesher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

/** CPU-only handoff: client callbacks supply numeric appearances; workers own all buffer writes. */
final class GroundLodPacking implements Runnable, AutoCloseable {
    record Quad(GroundQuadtreeMesher.Material material, List<Point> points, List<BlockPos> lights,
            BlockPos appearancePosition) {}
    record Geometry(GroundQuadtreeMesher.Built built, List<Quad> quads, Map<BlockState, Integer> verticesByState) {
        long bytes() { return (long) quads.size() * (4 * (3 * Double.BYTES + 3 * Integer.BYTES) + 3 * Integer.BYTES); }
    }
    record Appearance(float u, float v, int bounds, int color, int albedo,
            int calibrationAlbedo, int layer, boolean translucent) {}

    /** Runs alongside meshing, not inside the render-thread snapshot loop. */
    static Geometry prepare(GroundQuadtreeMesher.Built built, BooleanSupplier current) {
        var data = built.vertices();
        var quads = new ArrayList<Quad>(built.vertexCount() / 4);
        int face = built.face().ordinal();
        var verticesByState = new HashMap<BlockState, Integer>();
        for (int off = 0; off < data.limit(); off += 48) {
            if (!current.getAsBoolean()) throw new CancellationException();
            var material = built.materials().get(GroundFaceArenas.materialId(data, off));
            var points = new ArrayList<Point>(4);
            var lights = new ArrayList<BlockPos>(4);
            for (int q = 0; q < 4; q++) {
                int vertex = off + q * 12;
                int tag = Short.toUnsignedInt(data.getShort(vertex + 8));
                double x = Short.toUnsignedInt(data.getShort(vertex)) / 16.;
                double y = Short.toUnsignedInt(data.getShort(vertex + 2)) / 16. - 2048
                        + ((tag & 4) == 0 ? material.lowerHeightOffset() : material.upperHeightOffset());
                double z = Short.toUnsignedInt(data.getShort(vertex + 4)) / 16.;
                points.add(new Point(x, y, z));
                lights.add(GroundMeshContext.lightPosition(face, material, x, y, z));
            }
            quads.add(new Quad(material, List.copyOf(points), List.copyOf(lights), GroundAlbedo.position(face, points)));
            verticesByState.merge(material.block(), 4, Integer::sum);
        }
        return new Geometry(built, List.copyOf(quads), Map.copyOf(verticesByState));
    }

    final Geometry geometry;
    final Appearance[] appearances;
    int[] lights;
    private final AtomicLong owner;
    private final java.util.function.Consumer<Result> beforePublish;
    private long retained;
    private boolean running, closed, done;
    private Throwable failure;
    private Result result;

    GroundLodPacking(Geometry geometry, AtomicLong owner) {
        this(geometry, owner, ignored -> {});
    }

    GroundLodPacking(Geometry geometry, AtomicLong owner, java.util.function.Consumer<Result> beforePublish) {
        this.beforePublish = beforePublish;
        this.geometry = geometry;
        this.owner = owner;
        appearances = new Appearance[geometry.quads().size()];
        lights = new int[geometry.built().vertexCount()];
        // Numeric payload estimate; object headers, like the mesh descriptor list, are excluded.
        retained = (long) appearances.length * (2 * Float.BYTES + 5 * Integer.BYTES + 1)
                + (long) lights.length * Integer.BYTES;
        owner.addAndGet(retained);
    }

    private synchronized boolean current() { return !closed && !Thread.currentThread().isInterrupted(); }
    synchronized boolean done() { return done; }
    synchronized String outcome() {
        return closed || failure instanceof CancellationException ? "cancelled"
                : failure == null ? "finished" : "failure";
    }
    synchronized Result take() {
        if (!done) throw new IllegalStateException("Packing still running");
        if (failure != null) throw new IllegalStateException("Worker packing failed", failure);
        var packed = result;
        result = null;
        return packed;
    }

    @Override
    public void run() {
        synchronized (this) {
            if (closed || running || done) return;
            running = true;
        }
        Result packed = null;
        Throwable failed = null;
        try {
            packed = pack(geometry, appearances, lights, this::current, owner);
            if (current()) beforePublish.accept(packed);
        } catch (Throwable ex) {
            failed = ex;
        } finally {
            synchronized (this) {
                running = false;
                done = true;
                if (closed) {
                    if (packed != null) packed.close();
                } else {
                    result = packed;
                    failure = failed;
                }
                releaseInputs();
            }
        }
    }

    private void releaseInputs() {
        owner.addAndGet(-retained);
        retained = 0;
        Arrays.fill(appearances, null);
        lights = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (!running) releaseInputs();
        if (result != null) { result.close(); result = null; }
    }

    static Result pack(Geometry geometry, Appearance[] appearances, int[] lights,
            BooleanSupplier current, AtomicLong owner) {
        var ids = new HashMap<GroundCompact.Material, Integer>();
        var materials = new ArrayList<GroundCompact.Material>();
        var counts = new int[GroundFaceArenas.LAYERS];
        for (int q = 0; q < appearances.length; q++) {
            if ((q & 63) == 0 && !current.getAsBoolean()) throw new CancellationException();
            counts[appearances[q].layer()] += 4;
        }
        var buffers = new LinkedHashMap<Integer, ByteBuffer>();
        long bytes = 0;
        boolean success = false;
        try {
            for (int layer = 0; layer < counts.length; layer++) {
                if (!current.getAsBoolean()) throw new CancellationException();
                if (counts[layer] == 0) continue;
                int size = Math.multiplyExact(counts[layer], 12);
                buffers.put(layer, ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()));
                bytes += size;
                owner.addAndGet(size);
            }
            var source = geometry.built().vertices();
            for (int q = 0; q < appearances.length; q++) {
                if (!current.getAsBoolean()) throw new CancellationException();
                var quad = geometry.quads().get(q);
                var m = quad.material();
                var a = appearances[q];
                boolean plant = VoxelMesher.plant(m.block());
                boolean average = m.rgb() >= 0 || m.rgb() == -2;
                int albedo = m.rgb() >= 0 ? GroundAlbedo.calibrate(m.rgb(),
                        dev.starfold.worldgen.surface.SurfaceColor.color(m.block()), a.calibrationAlbedo())
                        : a.albedo();
                int color = average ? albedo : a.color();
                var direction = net.minecraft.core.Direction.getNearest(
                        (float) m.normalX(), (float) m.normalY(), (float) m.normalZ());
                float shade = plant ? 1 : switch (direction) {
                    case UP -> 1f;
                    case DOWN -> .5f;
                    case NORTH, SOUTH -> .8f;
                    case EAST, WEST -> .6f;
                };
                color = shade(color, shade);
                albedo = shade(albedo, shade);
                int tint = color >> 16 & 255 | (color >> 8 & 255) << 8 | (color & 255) << 16;
                int normal = packedAlbedoNormal(albedo);
                int tag = plant ? 234 : Math.abs(m.normalY()) > .0001 ? 250
                        : m.normalX() < -.5 ? 240 : m.normalX() > .5 ? 241 : m.normalZ() < 0 ? 242 : 243;
                var out = buffers.get(a.layer());
                for (int corner = 0; corner < 4; corner++) {
                    double y = quad.points().get(corner).y();
                    boolean translucent = a.translucent();
                    float offset = translucent ? waterHeightOffset(y, a.v(), true)
                            : (float) (y - Math.round(y * 16) / 16.);
                    var material = new GroundCompact.Material(average ? -1 : a.u(), a.v(), a.bounds(),
                            tint, normal, translucent ? .7f : -1f, offset);
                    int id = ids.computeIfAbsent(material, key -> {
                        int next = materials.size();
                        if (next > 0xffffff) throw new IllegalArgumentException("Compact material id");
                        materials.add(key);
                        // Material numeric fields (28 bytes) and the final remap entry (4 bytes).
                        owner.addAndGet(32);
                        return next;
                    });
                    int off = q * 48 + corner * 12;
                    out.putShort(source.getShort(off)).putShort(source.getShort(off + 2))
                            .putShort(source.getShort(off + 4)).putShort((short) id)
                            .putShort((short) (tag | (id >>> 16) << 8)).putShort((short) lights[q * 4 + corner]);
                }
            }
            buffers.values().forEach(ByteBuffer::flip);
            var result = new Result(buffers, List.copyOf(materials), owner, bytes + 32L * materials.size());
            success = true;
            return result;
        } finally {
            if (!success) owner.addAndGet(-bytes - 32L * materials.size());
        }
    }

    static final class Result implements AutoCloseable {
        final Map<Integer, ByteBuffer> layers;
        final List<GroundCompact.Material> materials;
        private final int[] palette;
        private final AtomicLong owner;
        private long bytes;
        private int materialCursor, layerCursor, vertexCursor, requiredMaterials;
        private final List<ByteBuffer> buffers;

        Result(Map<Integer, ByteBuffer> layers, List<GroundCompact.Material> materials,
                AtomicLong owner, long bytes) {
            this.layers = Collections.unmodifiableMap(layers);
            this.materials = materials;
            this.owner = owner;
            this.bytes = bytes;
            palette = new int[materials.size()];
            buffers = List.copyOf(layers.values());
        }

        /** Only the local-to-global material word is patched here; all other encoding is finished. */
        boolean remap(GroundUploadBudget budget, ToIntFunction<GroundCompact.Material> register) {
            while (materialCursor < materials.size() && budget.cpuAvailable()) {
                int id = register.applyAsInt(materials.get(materialCursor));
                if (id < 0 || id > 0xffffff) throw new IllegalArgumentException("Compact material id");
                palette[materialCursor++] = id;
                requiredMaterials = Math.max(requiredMaterials, id + 1);
            }
            if (materialCursor != materials.size()) return false;
            while (layerCursor < buffers.size() && budget.cpuAvailable()) {
                var data = buffers.get(layerCursor);
                int count = budget.takePackingVertices((data.limit() - vertexCursor) / 12);
                if (count == 0) return false;
                int until = vertexCursor + count * 12;
                for (; vertexCursor < until; vertexCursor += 12) {
                    int id = palette[GroundFaceArenas.materialId(data, vertexCursor)];
                    int tag = Short.toUnsignedInt(data.getShort(vertexCursor + 8)) & 255;
                    data.putShort(vertexCursor + 6, (short) id);
                    data.putShort(vertexCursor + 8, (short) (tag | (id >>> 16) << 8));
                }
                if (vertexCursor == data.limit()) { layerCursor++; vertexCursor = 0; }
            }
            return layerCursor == buffers.size();
        }

        int requiredMaterials() {
            if (layerCursor != buffers.size()) throw new IllegalStateException("Material mapping incomplete");
            return requiredMaterials;
        }

        public void close() { owner.addAndGet(-bytes); bytes = 0; }
    }

    static int packedAlbedoNormal(int rgb) {
        int packed = 0;
        for (int channel = 0; channel < 3; channel++) {
            int value = rgb >> (16 - channel * 8) & 255;
            int signed = (byte) ((value / 255f * 2 - 1) * 127f);
            packed |= (signed & 255) << (channel * 8);
        }
        return packed;
    }

    private static int shade(int rgb, float shade) {
        return (int) ((rgb >> 16 & 255) * shade) << 16
                | (int) ((rgb >> 8 & 255) * shade) << 8 | (int) ((rgb & 255) * shade);
    }

    static float waterHeightOffset(double y, float v, boolean translucent) {
        if (!translucent || (int) Math.floor(v / 2) != dev.starfold.api.SurfaceClass.WATER) return 0;
        double base = Math.floor(y), fraction = y - base;
        double ninth = Math.rint(fraction * 9);
        double exact = ninth > 0 && ninth < 9 && Math.abs(fraction - ninth / 9) < .0001
                ? base + ninth / 9 - .001 : y;
        return (float) (exact - (Math.round((y + 2048) * 16) / 16.0 - 2048));
    }

}
