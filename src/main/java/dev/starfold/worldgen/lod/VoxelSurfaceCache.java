package dev.starfold.worldgen.lod;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.surface.SurfaceFunction;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntConsumer;
import java.util.zip.CRC32;

/** Packed L4–L64 run columns. Only one 64-square L1/L2 workspace per worker is retained. */
public final class VoxelSurfaceCache {
    private record Plane(int width, int[] offsets, long[] runs) {
        Plane(VoxelColumn[] columns, int width) {
            this(width, offsets(columns), pack(columns));
        }

        private static int[] offsets(VoxelColumn[] columns) {
            var offsets = new int[columns.length + 1];
            for (int i = 0; i < columns.length; i++)
                offsets[i + 1] = offsets[i] + columns[i].runs().size();
            return offsets;
        }

        private static long[] pack(VoxelColumn[] columns) {
            int count = 0;
            for (var c : columns) count += c.runs().size();
            var data = new long[count];
            int at = 0;
            for (var c : columns) {
                var p = c.packed();
                System.arraycopy(p, 0, data, at, p.length);
                at += p.length;
            }
            return data;
        }

        VoxelColumn column(int x, int z) {
            int i = x + width * z;
            return VoxelColumn.unpack(runs, offsets[i], offsets[i + 1]);
        }

        long bytes() {
            return offsets.length * 4L + runs.length * 8L;
        }
    }

    private final int size;
    private final int patchCount;
    private final AtomicInteger publishedPatches = new AtomicInteger();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicReferenceArray<Plane[]> patches = new AtomicReferenceArray<>(6144);

    public VoxelSurfaceCache(int size) {
        if (size < 16 || size > 2048 || size % 16 != 0)
            throw new IllegalArgumentException("Voxel cache size");
        this.size = size;
        int n = (size + 63) / 64;
        patchCount = 6 * n * n;
    }

    public VoxelColumn column(Face face, int x, int z, int step) {
        if (step < 4 || step > 64 || Integer.bitCount(step) != 1)
            throw new IllegalArgumentException("Retained voxel level");
        var patch = patches.get(face.ordinal() * 1024 + x / 64 + z / 64 * 32);
        if (patch == null) return null;
        return patch[Integer.numberOfTrailingZeros(step) - 2].column(
                (x % 64) / step, (z % 64) / step);
    }

    public long retainedBytes() {
        return retainedBytes.get();
    }

    /** Disk layout version; bump when the record encoding changes. */
    public static final int FORMAT_VERSION = 2;

    private static final int MAGIC = 0x53564643;
    private static final int MAX_PATCH_BYTES = 128 * 1024;

    public int[] patchKeys() {
        int n = (size + 63) / 64;
        var keys = new int[6 * n * n];
        int at = 0;
        for (int f = 0; f < 6; f++)
            for (int b = 0; b < n; b++)
                for (int a = 0; a < n; a++) keys[at++] = f * 1024 + a + b * 32;
        return keys;
    }

    private boolean validKey(int key) {
        return key >= 0
                && key < patches.length()
                && key % 32 * 64 < size
                && key % 1024 / 32 * 64 < size;
    }

    public boolean hasPatch(int key) {
        return patches.get(key) != null;
    }

    public boolean complete() {
        return publishedPatches.get() == patchCount;
    }

    private void publish(int key, Plane[] planes, IntConsumer publish) {
        if (!patches.compareAndSet(key, null, planes)) return;
        // Count only immutable CAS winners, including replay, before invoking caller code.
        long bytes = 0;
        for (var plane : planes) bytes += plane.bytes();
        retainedBytes.addAndGet(bytes);
        publishedPatches.incrementAndGet();
        publish.accept(key);
    }

    public void writeHeader(DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(size);
    }

    public boolean readHeader(DataInput in) throws IOException {
        return in.readInt() == MAGIC && in.readInt() == FORMAT_VERSION && in.readInt() == size;
    }

    /** A journal of independently checksummed, immutable patches. Partial caches are useful too. */
    public void write(DataOutputStream out) throws IOException {
        writeHeader(out);
        for (int key : patchKeys()) if (hasPatch(key)) writePatch(out, key);
    }

    public void writePatch(DataOutput out, int key) throws IOException {
        var planes = patches.get(key);
        if (planes == null) throw new IllegalStateException("Unpublished voxel patch");
        var bytes = new ByteArrayOutputStream();
        try (var payload = new DataOutputStream(bytes)) {
            payload.writeInt(key);
            for (var plane : planes) {
                payload.writeInt(plane.width());
                payload.writeInt(plane.offsets().length);
                for (int o : plane.offsets()) payload.writeInt(o);
                payload.writeInt(plane.runs().length);
                for (long r : plane.runs()) payload.writeLong(r);
            }
        }
        var data = bytes.toByteArray();
        var crc = new CRC32();
        crc.update(data);
        out.writeInt(data.length);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    /** Returns -1 at a clean record boundary. A bad tail leaves earlier valid patches published. */
    public int readPatch(DataInput in, IntConsumer publish) throws IOException {
        int first;
        try {
            first = in.readUnsignedByte();
        } catch (EOFException end) {
            return -1;
        }
        int length =
                first << 24
                        | in.readUnsignedByte() << 16
                        | in.readUnsignedByte() << 8
                        | in.readUnsignedByte();
        if (length < 4 || length > MAX_PATCH_BYTES) throw new IOException("Voxel patch length");
        var data = new byte[length];
        in.readFully(data);
        int checksum = in.readInt();
        var crc = new CRC32();
        crc.update(data);
        if ((int) crc.getValue() != checksum) throw new IOException("Voxel patch checksum");
        var payload = new DataInputStream(new ByteArrayInputStream(data));
        int key = payload.readInt();
        if (!validKey(key)) throw new IOException("Voxel patch key");
        int width = Math.min(64, size - key % 32 * 64);
        int depth = Math.min(64, size - key % 1024 / 32 * 64);
        var planes = new Plane[5];
        for (int j = 0, step = 4; j < planes.length; j++, step *= 2) {
            int w = (width + step - 1) / step, d = (depth + step - 1) / step;
            if (payload.readInt() != w || payload.readInt() != w * d + 1)
                throw new IOException("Voxel plane dimensions");
            var offsets = new int[w * d + 1];
            for (int o = 0; o < offsets.length; o++) {
                offsets[o] = payload.readInt();
                if (o == 0
                        ? offsets[o] != 0
                        : offsets[o] < offsets[o - 1]
                                || offsets[o] - offsets[o - 1] > VoxelColumn.MAX_RUNS)
                    throw new IOException("Voxel plane offsets");
            }
            int count = payload.readInt();
            if (count != offsets[offsets.length - 1]) throw new IOException("Voxel plane runs");
            var runs = new long[count];
            for (int r = 0; r < count; r++) runs[r] = payload.readLong();
            try {
                for (int c = 0; c < w * d; c++) {
                    // Validate heights, ordering, run cap and registry IDs before publishing.
                    var column = VoxelColumn.unpack(runs, offsets[c], offsets[c + 1]);
                    var packed = column.packed();
                    for (int r = 0; r < packed.length; r++)
                        if (packed[r] != runs[offsets[c] + r])
                            throw new IllegalArgumentException("Voxel block state ID");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid voxel column", invalid);
            }
            planes[j] = new Plane(w, offsets, runs);
        }
        if (payload.available() != 0) throw new IOException("Trailing voxel patch data");
        publish(key, planes, publish);
        return key;
    }

    /** Streams valid records directly into the cache, retaining a valid prefix on corruption. */
    public boolean read(DataInputStream in, IntConsumer publish) throws IOException {
        if (!readHeader(in)) return false;
        while (readPatch(in, publish) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        }
        return true;
    }

    /** Builds exactly the same recursive L1 -> L64 reduction, with one bounded workspace. */
    public void fillPatch(SurfaceFunction function, int key, IntConsumer publish)
            throws InterruptedException {
        if (!validKey(key)) throw new IllegalArgumentException("Voxel patch key");
        if (hasPatch(key)) return;
        var face = Face.VALUES[key / 1024];
        int x = key % 32 * 64, z = key % 1024 / 32 * 64;
        int width = Math.min(64, size - x), depth = Math.min(64, size - z);
        var columns = new VoxelColumn[width * depth];
        for (int b = 0; b < depth; b++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            for (int a = 0; a < width; a++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    columns[a + width * b] = VoxelColumn.visible(function, face, x + a, z + b);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException(
                            "Voxel column: seed="
                                    + function.terrain().seed()
                                    + " plan="
                                    + function.terrain().plan().identity()
                                    + " face="
                                    + face
                                    + " x="
                                    + (x + a)
                                    + " z="
                                    + (z + b),
                            failure);
                }
            }
        }
        var planes = new Plane[5];
        for (int step = 2; step <= 64; step *= 2) {
            int w = (width + 1) / 2, d = (depth + 1) / 2;
            var parents = new VoxelColumn[w * d];
            for (int b = 0; b < d; b++)
                for (int a = 0; a < w; a++) {
                    var children = new ArrayList<VoxelColumn>(4);
                    for (int dv = 0; dv < 2; dv++)
                        for (int du = 0; du < 2; du++)
                            if (a * 2 + du < width && b * 2 + dv < depth)
                                children.add(columns[a * 2 + du + width * (b * 2 + dv)]);
                    parents[a + w * b] = VoxelColumn.reduce(children, step);
                }
            columns = parents;
            width = w;
            depth = d;
            if (step >= 4)
                planes[Integer.numberOfTrailingZeros(step) - 2] = new Plane(columns, width);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        publish(key, planes, publish);
    }

    public void fill(
            SurfaceFunction function, int threads, Face face, int x, int z, IntConsumer publish)
            throws InterruptedException {
        record Patch(Face face, int x, int z, double distance) {}
        var near = function.terrain().unit(face, x + .5, z + .5);
        var order = new ArrayList<Patch>();
        for (var f : Face.VALUES)
            for (int b = 0; b < size; b += 64)
                for (int a = 0; a < size; a += 64)
                    order.add(
                            new Patch(
                                    f,
                                    a,
                                    b,
                                    1 - near.dot(function.terrain().unit(f, a + 32., b + 32.))));
        order.sort(Comparator.comparingDouble(Patch::distance));
        var next = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < threads; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    int index;
                                    while (!Thread.currentThread().isInterrupted()
                                            && (index = next.getAndIncrement()) < order.size()) {
                                        var p = order.get(index);
                                        int key =
                                                p.face().ordinal() * 1024
                                                        + p.x() / 64
                                                        + p.z() / 64 * 32;
                                        if (patches.get(key) != null)
                                            continue; // Retry only unpublished patches.
                                        try {
                                            fillPatch(function, key, publish);
                                        } catch (InterruptedException cancelled) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                    }
                                }));
            for (var future : futures) future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Voxel surface build", e.getCause());
        } finally {
            futures.forEach(f -> f.cancel(true));
            pool.shutdownNow();
            if (!pool.awaitTermination(1, TimeUnit.MINUTES))
                throw new IllegalStateException("Voxel workers did not stop");
        }
    }
}
