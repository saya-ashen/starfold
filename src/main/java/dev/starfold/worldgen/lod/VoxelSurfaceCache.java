package dev.starfold.worldgen.lod;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntConsumer;

/** Packed L4–L64 run columns. Only one 64-square L1/L2 workspace per worker is retained. */
public final class VoxelSurfaceCache {
    private record Plane(int width, int[] offsets, long[] runs) {
        Plane(VoxelColumn[] columns, int width) {
            this(width, offsets(columns), pack(columns));
        }
        private static int[] offsets(VoxelColumn[] columns) {
            var offsets = new int[columns.length + 1];
            for (int i = 0; i < columns.length; i++) offsets[i + 1] = offsets[i] + columns[i].runs().size();
            return offsets;
        }
        private static long[] pack(VoxelColumn[] columns) {
            int count = 0; for (var c : columns) count += c.runs().size();
            var data = new long[count]; int at = 0;
            for (var c : columns) { var p = c.packed(); System.arraycopy(p, 0, data, at, p.length); at += p.length; }
            return data;
        }
        VoxelColumn column(int x, int z) {
            int i = x + width * z;
            return VoxelColumn.unpack(runs, offsets[i], offsets[i + 1]);
        }
        long bytes() { return offsets.length * 4L + runs.length * 8L; }
    }
    private final int size;
    private final AtomicReferenceArray<Plane[]> patches = new AtomicReferenceArray<>(6144);
    public VoxelSurfaceCache(int size) {
        if (size < 16 || size > 2048 || size % 16 != 0) throw new IllegalArgumentException("Voxel cache size");
        this.size = size;
    }
    public VoxelColumn column(Face face, int x, int z, int step) {
        if (step < 4 || step > 64 || Integer.bitCount(step) != 1) throw new IllegalArgumentException("Retained voxel level");
        var patch = patches.get(face.ordinal() * 1024 + x / 64 + z / 64 * 32);
        if (patch == null) return null;
        return patch[Integer.numberOfTrailingZeros(step) - 2].column((x % 64) / step, (z % 64) / step);
    }
    public long retainedBytes() {
        long bytes = 0;
        for (int i = 0; i < patches.length(); i++) {
            var p = patches.get(i); if (p != null) for (var plane : p) bytes += plane.bytes();
        }
        return bytes;
    }

    private int patchCount() { int n = (size + 63) / 64; return 6 * n * n; }

    /** Every patch of every face is built; only a complete cache is worth saving. */
    public boolean complete() {
        int n = (size + 63) / 64;
        for (int f = 0; f < 6; f++) for (int b = 0; b < n; b++) for (int a = 0; a < n; a++)
            if (patches.get(f * 1024 + a + b * 32) == null) return false;
        return true;
    }

    public void write(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(size);
        out.writeInt(patchCount());
        for (int i = 0; i < patches.length(); i++) {
            var p = patches.get(i);
            if (p == null) continue;
            out.writeInt(i);
            for (var plane : p) {
                out.writeInt(plane.width());
                out.writeInt(plane.offsets().length);
                for (int o : plane.offsets()) out.writeInt(o);
                out.writeInt(plane.runs().length);
                for (long r : plane.runs()) out.writeLong(r);
            }
        }
    }

    /** Loads a complete cache written by {@link #write}, publishing each patch; false leaves it empty. */
    public boolean read(java.io.DataInputStream in, IntConsumer publish) throws java.io.IOException {
        if (in.readInt() != size) return false;
        int count = in.readInt();
        if (count != patchCount()) return false;
        var loaded = new Plane[patches.length()][];
        for (int n = 0; n < count; n++) {
            int key = in.readInt();
            if (key < 0 || key >= loaded.length) return false;
            var planes = new Plane[5];
            for (int j = 0; j < planes.length; j++) {
                int width = in.readInt();
                var offsets = new int[in.readInt()];
                for (int o = 0; o < offsets.length; o++) offsets[o] = in.readInt();
                var runs = new long[in.readInt()];
                for (int r = 0; r < runs.length; r++) runs[r] = in.readLong();
                planes[j] = new Plane(width, offsets, runs);
            }
            loaded[key] = planes;
        }
        for (int i = 0; i < loaded.length; i++)
            if (loaded[i] != null) { patches.set(i, loaded[i]); publish.accept(i); }
        return true;
    }
    public void fill(SurfaceFunction function, int threads, Face face, int x, int z, IntConsumer publish)
            throws InterruptedException {
        record Patch(Face face, int x, int z, double distance) {}
        var near = function.terrain().unit(face, x + .5, z + .5);
        var order = new ArrayList<Patch>();
        for (var f : Face.VALUES) for (int b = 0; b < size; b += 64) for (int a = 0; a < size; a += 64)
            order.add(new Patch(f, a, b, 1 - near.dot(function.terrain().unit(f, a + 32., b + 32.))));
        order.sort(Comparator.comparingDouble(Patch::distance));
        var next = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < threads; i++) futures.add(pool.submit(() -> {
                int index;
                while (!Thread.currentThread().isInterrupted() && (index = next.getAndIncrement()) < order.size()) {
                    var p = order.get(index);
                    int key = p.face().ordinal() * 1024 + p.x() / 64 + p.z() / 64 * 32;
                    if (patches.get(key) != null) continue; // Retry only unpublished patches.
                    int width = Math.min(64, size - p.x()), depth = Math.min(64, size - p.z());
                    var columns = new VoxelColumn[width * depth];
                    for (int b = 0; b < depth; b++) {
                        if (Thread.currentThread().isInterrupted()) return;
                        for (int a = 0; a < width; a++) {
                            try {
                                columns[a + width * b] = VoxelColumn.visible(function, p.face(), p.x() + a, p.z() + b);
                            } catch (RuntimeException failure) {
                                throw new IllegalStateException("Voxel column: seed=" + function.terrain().seed()
                                        + " plan=" + function.terrain().plan().identity() + " face=" + p.face()
                                        + " x=" + (p.x() + a) + " z=" + (p.z() + b), failure);
                            }
                        }
                    }
                    var planes = new Plane[5];
                    for (int step = 2; step <= 64; step *= 2) {
                        int w = (width + 1) / 2, d = (depth + 1) / 2;
                        var parents = new VoxelColumn[w * d];
                        for (int b = 0; b < d; b++) for (int a = 0; a < w; a++) {
                            var children = new ArrayList<VoxelColumn>(4);
                            for (int dv = 0; dv < 2; dv++) for (int du = 0; du < 2; du++)
                                if (a * 2 + du < width && b * 2 + dv < depth)
                                    children.add(columns[a * 2 + du + width * (b * 2 + dv)]);
                            parents[a + w * b] = VoxelColumn.reduce(children, step);
                        }
                        columns = parents; width = w; depth = d;
                        if (step >= 4) planes[Integer.numberOfTrailingZeros(step) - 2] = new Plane(columns, width);
                    }
                    patches.set(key, planes); publish.accept(key);
                }
            }));
            for (var future : futures) future.get();
        } catch (ExecutionException e) { throw new IllegalStateException("Voxel surface build", e.getCause()); }
        finally {
            futures.forEach(f -> f.cancel(true)); pool.shutdownNow();
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) throw new IllegalStateException("Voxel workers did not stop");
        }
    }
}
