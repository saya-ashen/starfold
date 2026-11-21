package dev.starfold.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import java.util.function.ToIntFunction;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;

/** Immutable, pre-global-palette GPU encoding. Disk work and buffer copies stay on mesh workers.
 * Persist terrain fallback light, never a snapshot of another session's loaded vanilla light.
 */
final class GroundPackedCache {
    static final String VERSION = "packed-lod-2-lz4";
    private static final int MAGIC = 0x53465031, MAX_BYTES = 64 << 20;
    record Key(GroundMeshCache.Key geometry, String appearance) {
        Path directory() { return geometry.directory().resolveSibling(geometry.directory().getFileName()
                + "-" + VERSION + "-" + appearance); }
        Path file() { return directory().resolve(geometry.file().getFileName().toString().replace(".mesh", ".packed")); }
    }
    record LightGroup(long chunk, int[] vertices) {}
    record Layer(int index, ByteBuffer vertices, long[] lights, List<LightGroup> groups) {
        Layer(int index, ByteBuffer vertices, long[] lights) {
            this(index, vertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), lights, groups(lights));
        }
        private static List<LightGroup> groups(long[] positions) {
            var groups = new LinkedHashMap<Long, List<Integer>>();
            for (int v = 0; v < positions.length; v++) {
                var p = BlockPos.of(positions[v]);
                long chunk = ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
                groups.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(v);
            }
            return groups.entrySet().stream().map(e -> new LightGroup(e.getKey(),
                    e.getValue().stream().mapToInt(Integer::intValue).toArray())).toList();
        }
    }
    record Entry(GroundQuadtreeMesher.Built built, List<GroundCompact.Material> materials, List<Layer> layers) {
        long bytes() {
            return built.bytes() + (long) built.materials().size() * 128 + (long) materials.size() * 32 + 256
                    + layers.stream().mapToLong(l -> l.vertices().remaining() + (long) l.lights().length * 12
                            + (long) l.groups().size() * 64).sum();
        }
        GroundLodPacking.Result restore(AtomicLong owner) {
            var buffers = new LinkedHashMap<Integer, ByteBuffer>();
            long bytes = (long) materials.size() * 32;
            for (var layer : layers) {
                var source = layer.vertices().duplicate();
                var copy = ByteBuffer.allocate(source.remaining()).order(ByteOrder.nativeOrder());
                copy.put(source).flip(); buffers.put(layer.index(), copy); bytes += copy.remaining();
            }
            owner.addAndGet(bytes);
            return new GroundLodPacking.Result(buffers, materials, owner, bytes);
        }
    }
    /** Render-thread cursor: skip whole unloaded columns; touch only nearby loaded light. */
    static final class LightRefresh {
        private int layer, group, vertex;
        boolean step(Entry entry, GroundLodPacking.Result packed, GroundUploadBudget budget,
                LongPredicate loaded, ToIntFunction<BlockPos> sample) {
            while (layer < entry.layers().size() && budget.cpuAvailable()) {
                var l = entry.layers().get(layer);
                var target = packed.layers.get(l.index());
                while (group < l.groups().size() && budget.cpuAvailable()) {
                    var g = l.groups().get(group);
                    if (vertex == 0 && !loaded.test(g.chunk())) { group++; continue; }
                    while (vertex < g.vertices().length && budget.cpuAvailable()) {
                        int v = g.vertices()[vertex++];
                        int light = sample.applyAsInt(BlockPos.of(l.lights()[v]));
                        if (light >= 0) target.putShort(v * 12 + 10, (short) light);
                    }
                    if (vertex != g.vertices().length) return false;
                    vertex = 0; group++;
                }
                if (group != l.groups().size()) return false;
                group = 0; layer++;
            }
            return layer == entry.layers().size();
        }
    }

    private final long limit;
    private final LinkedHashMap<Key, Entry> ram = new LinkedHashMap<>(128, .75f, true);
    private long bytes;
    private final AtomicLong ramHits = new AtomicLong(), diskHits = new AtomicLong(), misses = new AtomicLong(),
            saves = new AtomicLong(), failures = new AtomicLong(), written = new AtomicLong();
    private final Set<Path> maintained = new HashSet<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundPackedCache.class);
    GroundPackedCache(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Packed RAM limit");
        this.limit = limit;
    }
    private final AtomicLong lookupCount = new AtomicLong(), lookupNanos = new AtomicLong(),
            decodeNanos = new AtomicLong(), touchNanos = new AtomicLong(), restoreNanos = new AtomicLong();

    /** Monotonic worker totals; elapsed sums may overlap across reader threads. */
    long[] stages() {
        return new long[] {lookupCount.get(), lookupNanos.get(), decodeNanos.get(), touchNanos.get(), restoreNanos.get()};
    }

    GroundLodPacking.Result restore(Entry entry, AtomicLong owner) {
        long started = System.nanoTime();
        try { return entry.restore(owner); }
        finally { restoreNanos.addAndGet(System.nanoTime() - started); }
    }

    Entry get(Key key) {
        long started = System.nanoTime();
        try { return lookup(key); }
        finally {
            lookupNanos.addAndGet(System.nanoTime() - started);
            lookupCount.incrementAndGet();
        }
    }

    private Entry lookup(Key key) {
        if (key == null) return null;
        synchronized (this) {
            var hit = ram.get(key);
            if (hit != null) { ramHits.incrementAndGet(); return hit; }
        }
        Path path = key.file();
        if (Files.isRegularFile(path)) {
            try {
                Entry hit;
                long decode = System.nanoTime();
                try (var in = new DataInputStream(new LZ4BlockInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
                    hit = read(in, key);
                } finally { decodeNanos.addAndGet(System.nanoTime() - decode); }
                long touch = System.nanoTime();
                try { Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis())); }
                finally { touchNanos.addAndGet(System.nanoTime() - touch); }
                remember(key, hit); diskHits.incrementAndGet(); return hit;
            } catch (IOException | IllegalArgumentException invalid) {
                failure(invalid);
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        }
        misses.incrementAndGet(); return null;
    }
    void put(Key key, Entry value) {
        if (key == null) return;
        remember(key, value);
        Path temporary = null;
        try {
            Files.createDirectories(key.directory());
            if (!GroundDiskBudget.writable(key.directory())) return;
            boolean first;
            synchronized (this) { first = maintained.add(key.directory()); }
            if (first || written.get() >= 64L << 20) {
                written.set(0); GroundMeshCache.prune(key.directory().getParent());
            }
            temporary = Files.createTempFile(key.directory(), "packed-", ".tmp");
            try (var out = new DataOutputStream(new LZ4BlockOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))))) {
                write(out, key, value);
            }
            written.addAndGet(Files.size(temporary));
            Files.move(temporary, key.file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            saves.incrementAndGet();
        } catch (IOException failure) { failure(failure); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private synchronized void remember(Key key, Entry value) {
        if (value.bytes() > limit) return;
        var old = ram.put(key, value);
        if (old != null) bytes -= old.bytes();
        bytes += value.bytes();
        while (bytes > limit) bytes -= ram.pollFirstEntry().getValue().bytes();
    }
    synchronized long[] statistics() {
        return new long[] {ramHits.get(), diskHits.get(), misses.get(), saves.get(), bytes, failures.get()};
    }
    private void failure(Exception failure) {
        if (failures.incrementAndGet() == 1) LOG.warn("Packed mesh cache unavailable or damaged; using geometry cache", failure);
    }

    /** Called by packing worker before Result publication/remapping. No client/resource reads. */
    static Entry capture(GroundLodPacking.Result packed, GroundLodPacking.Geometry geometry,
            GroundLodPacking.Appearance[] appearances, GroundMeshContext.Sky sky) {
        var positions = new LinkedHashMap<Integer, long[]>();
        var buffers = new LinkedHashMap<Integer, ByteBuffer>();
        for (var e : packed.layers.entrySet()) {
            var source = e.getValue().duplicate();
            var copy = ByteBuffer.allocate(source.remaining()).order(ByteOrder.nativeOrder());
            copy.put(source).flip(); buffers.put(e.getKey(), copy);
            positions.put(e.getKey(), new long[copy.remaining() / 12]);
        }
        int[] cursors = new int[GroundFaceArenas.LAYERS];
        int face = geometry.built().face().ordinal();
        for (int q = 0; q < geometry.quads().size(); q++) {
            int layer = appearances[q].layer();
            var data = buffers.get(layer);
            for (var pos : geometry.quads().get(q).lights()) {
                int v = cursors[layer]++;
                positions.get(layer)[v] = pos.asLong();
                int water = sky.level(sky.terrain(), face, pos.getX() - face * 4096, pos.getZ());
                int light = GroundFarTexture.sky(water == Integer.MIN_VALUE ? 0 : water - pos.getY()) * 4096;
                data.putShort(v * 12 + 10, (short) light);
            }
        }
        var layers = new ArrayList<Layer>();
        buffers.forEach((index, data) -> layers.add(new Layer(index, data, positions.get(index))));
        return new Entry(geometry.built(), List.copyOf(packed.materials), List.copyOf(layers));
    }

    static void write(DataOutputStream out, Key key, Entry value) throws IOException {
        out.writeInt(MAGIC); out.writeUTF(key.appearance());
        var header = new ByteArrayOutputStream();
        GroundMeshCache.write(new DataOutputStream(header), key.geometry(), value.built());
        out.writeInt(header.size()); header.writeTo(out);
        out.writeInt(value.materials().size());
        for (var m : value.materials()) {
            out.writeFloat(m.u()); out.writeFloat(m.v()); out.writeInt(m.bounds()); out.writeInt(m.tint());
            out.writeInt(m.normal()); out.writeFloat(m.alpha()); out.writeFloat(m.heightOffset());
        }
        out.writeInt(value.layers().size());
        for (var l : value.layers()) {
            out.writeInt(l.index()); out.writeInt(l.vertices().remaining());
            var data = l.vertices().duplicate();
            byte[] chunk = new byte[8192];
            while (data.hasRemaining()) { int n = Math.min(chunk.length, data.remaining()); data.get(chunk, 0, n); out.write(chunk, 0, n); }
            for (long p : l.lights()) out.writeLong(p);
        }
    }
    static Entry read(DataInputStream in, Key key) throws IOException {
        if (in.readInt() != MAGIC || !in.readUTF().equals(key.appearance())) throw new IOException("Packed cache identity");
        int length = bounded(in.readInt(), MAX_BYTES, "geometry");
        byte[] header = new byte[length]; in.readFully(header);
        var built = GroundMeshCache.read(new DataInputStream(new ByteArrayInputStream(header)), key.geometry());
        int count = bounded(in.readInt(), MAX_BYTES / 32, "materials");
        var materials = new ArrayList<GroundCompact.Material>(count);
        for (int i = 0; i < count; i++) materials.add(new GroundCompact.Material(finite(in.readFloat()), finite(in.readFloat()),
                in.readInt(), in.readInt(), in.readInt(), finite(in.readFloat()), finite(in.readFloat())));
        int layers = bounded(in.readInt(), GroundFaceArenas.LAYERS, "layers");
        var result = new ArrayList<Layer>(layers);
        int seen = 0, total = 0;
        for (int l = 0; l < layers; l++) {
            int index = bounded(in.readInt(), GroundFaceArenas.LAYERS - 1, "layer");
            if ((seen & 1 << index) != 0) throw new IOException("Duplicate packed layer");
            seen |= 1 << index;
            int size = bounded(in.readInt(), MAX_BYTES - total, "vertices"); total += size;
            if (size % 48 != 0) throw new IOException("Packed quad alignment");
            byte[] data = new byte[size]; in.readFully(data);
            var vertices = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
            for (int off = 0; off < size; off += 12)
                if (GroundFaceArenas.materialId(vertices, off) >= count) throw new IOException("Packed palette index");
            long[] lights = new long[size / 12];
            for (int v = 0; v < lights.length; v++) lights[v] = in.readLong();
            result.add(new Layer(index, vertices, lights));
        }
        if (total != built.bytes() || in.read() != -1) throw new IOException("Packed size or trailer");
        return new Entry(built, List.copyOf(materials), List.copyOf(result));
    }
    private static int bounded(int n, int max, String what) throws IOException {
        if (n < 0 || n > max) throw new IOException("Invalid packed " + what);
        return n;
    }
    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Nonfinite packed material");
        return value;
    }
}
