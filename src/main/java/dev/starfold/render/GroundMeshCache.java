package dev.starfold.render;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.surface.SurfaceKey;
import net.minecraft.world.level.block.Block;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Finished, resource-independent geometry, NOT GL handles or atlas/palette/light state.
 * All disk access runs on existing mesh workers. IO failure is a cache miss, never a render failure.
 */
final class GroundMeshCache {
    static final String VERSION = "quadtree-staging-1";
    private static final int MAGIC = 0x53464d31, MAX_BYTES = 64 << 20;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundMeshCache.class);
    record Key(Path directory, int node, int step, String inputs) {
        Path file() { return directory.resolve(node + "-" + step + "-" + inputs + ".mesh"); }
    }
    private final long ramLimit;
    private final LinkedHashMap<Key, GroundQuadtreeMesher.Built> ram = new LinkedHashMap<>(128, .75f, true);
    private long ramBytes;
    private final AtomicLong ramHits = new AtomicLong(), diskHits = new AtomicLong(), builds = new AtomicLong(), ioFailures = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final Set<Path> maintained = new HashSet<>();

    GroundMeshCache(long ramLimit) {
        if (ramLimit < 0) throw new IllegalArgumentException("Mesh RAM limit");
        this.ramLimit = ramLimit;
    }

    static Path directory(Path root, SurfaceKey terrain, String registry) {
        var coarse = GroundBaseDisk.file(root, terrain, registry);
        return root.resolve("starfold-cache").resolve("mesh").resolve(
                coarse.getFileName().toString().replace(".bin", "") + "-" + VERSION + "-" + ByteOrder.nativeOrder());
    }

    static String fingerprint(Map<Long, GroundSurface> captures) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var word = ByteBuffer.allocate(Long.BYTES);
            for (long key : captures.keySet().stream().sorted().toList()) {
                digest.update(word.clear().putLong(key).array());
                var bytes = captures.get(key).packed();
                digest.update(word.clear().putLong(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    GroundQuadtreeMesher.Built get(Key key, Supplier<GroundQuadtreeMesher.Built> compute) {
        if (key == null) return compute.get();
        synchronized (this) {
            var hit = ram.get(key);
            if (hit != null) { ramHits.incrementAndGet(); return hit; }
        }
        GroundQuadtreeMesher.Built value = null;
        Path path = key.file();
        if (Files.isRegularFile(path)) {
            try (var in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
                value = read(in, key);
                diskHits.incrementAndGet();
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException | IllegalArgumentException corrupt) {
                failure(corrupt);
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        }
        if (value == null) {
            builds.incrementAndGet();
            value = Objects.requireNonNull(compute.get());
            save(key, value);
        }
        remember(key, value);
        return value;
    }

    private synchronized void remember(Key key, GroundQuadtreeMesher.Built value) {
        long bytes = cost(value);
        if (bytes > ramLimit) return;
        var old = ram.put(key, value);
        if (old != null) ramBytes -= cost(old);
        ramBytes += bytes;
        while (ramBytes > ramLimit) ramBytes -= cost(ram.pollFirstEntry().getValue());
    }
    private static long cost(GroundQuadtreeMesher.Built value) {
        return value.bytes() + (long) value.materials().size() * 128 + 256;
    }
    synchronized long[] statistics() {
        return new long[] {ramHits.get(), diskHits.get(), builds.get(), ramBytes, ioFailures.get()};
    }
    private void failure(Exception failure) {
        if (ioFailures.incrementAndGet() == 1) LOG.warn("Mesh disk cache unavailable or damaged; rebuilding geometry", failure);
    }

    private void save(Key key, GroundQuadtreeMesher.Built value) {
        Path temporary = null;
        try {
            Files.createDirectories(key.directory());
            // Check before EVERY mesh write, not only at pruning intervals.
            if (!GroundDiskBudget.writable(key.directory())) return;
            boolean first;
            synchronized (this) { first = maintained.add(key.directory()); }
            if (first || written.get() >= 64L << 20) {
                written.set(0);
                prune(key.directory().getParent());
            }
            temporary = Files.createTempFile(key.directory(), "mesh-", ".tmp");
            try (var out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))))) {
                write(out, key, value);
            }
            written.addAndGet(Files.size(temporary));
            Files.move(temporary, key.file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { failure(failure); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    /** Serialized across worker writers. Files being read remain valid through open descriptors. */
    static synchronized void prune(Path root) throws IOException {
        record Cached(Path path, long bytes, long modified) {}
        var files = new ArrayList<Cached>();
        try (var paths = Files.walk(root, 2)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".mesh") || p.toString().endsWith(".packed")).toList()) {
                try {
                    var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                    if (attributes.isRegularFile()) files.add(new Cached(path, attributes.size(), attributes.lastModifiedTime().toMillis()));
                } catch (NoSuchFileException concurrentlyRemoved) { /* Another cache writer pruned it. */ }
            }
        }
        long total = files.stream().mapToLong(Cached::bytes).sum();
        long allowance = GroundDiskBudget.allowance(root, total);
        if (total <= allowance) return;
        // Cache hits update mtime concurrently: sort immutable observations, never live file times.
        files.sort(Comparator.comparingLong(Cached::modified));
        for (var file : files) {
            if (total <= allowance) break;
            if (Files.deleteIfExists(file.path())) total -= file.bytes();
        }
    }

    static void write(DataOutputStream out, Key key, GroundQuadtreeMesher.Built value) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(key.node()); out.writeInt(key.step()); out.writeUTF(key.inputs());
        out.writeInt(value.face().ordinal());
        var b = value.bounds();
        for (double d : new double[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) out.writeDouble(d);
        out.writeInt(value.materials().size());
        for (var m : value.materials()) {
            out.writeInt(Block.BLOCK_STATE_REGISTRY.getId(m.block()));
            out.writeInt(m.axis()); out.writeBoolean(m.volume()); out.writeInt(m.rgb());
            for (double d : new double[] {m.normalX(), m.normalY(), m.normalZ(), m.bottomY(), m.topY(), m.lowerHeightOffset(), m.upperHeightOffset()}) out.writeDouble(d);
        }
        var vertices = value.vertices();
        out.writeInt(vertices.remaining());
        byte[] chunk = new byte[8192];
        while (vertices.hasRemaining()) {
            int size = Math.min(chunk.length, vertices.remaining());
            vertices.get(chunk, 0, size); out.write(chunk, 0, size);
        }
    }

    static GroundQuadtreeMesher.Built read(DataInputStream in, Key key) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != key.node() || in.readInt() != key.step() || !in.readUTF().equals(key.inputs()))
            throw new IOException("Mesh cache identity");
        int face = in.readInt();
        if (face != GroundQuadtree.face(key.node())) throw new IOException("Mesh cache face");
        var b = new double[6];
        for (int i = 0; i < b.length; i++) b[i] = finite(in.readDouble());
        if (b[0] > b[3] || b[1] > b[4] || b[2] > b[5]) throw new IOException("Mesh cache bounds");
        int count = in.readInt();
        if (count < 0 || count > MAX_BYTES / 128) throw new IOException("Mesh cache material count");
        var materials = new ArrayList<GroundQuadtreeMesher.Material>(count);
        for (int i = 0; i < count; i++) {
            var state = Block.BLOCK_STATE_REGISTRY.byId(in.readInt());
            int axis = in.readInt(); boolean volume = in.readBoolean(); int rgb = in.readInt();
            if (state == null || axis < 0 || axis > 2) throw new IOException("Mesh cache material");
            materials.add(new GroundQuadtreeMesher.Material(state, axis, volume, rgb,
                    finite(in.readDouble()), finite(in.readDouble()), finite(in.readDouble()), finite(in.readDouble()),
                    finite(in.readDouble()), finite(in.readDouble()), finite(in.readDouble())));
        }
        int bytes = in.readInt();
        if (bytes < 0 || bytes > MAX_BYTES || bytes % 48 != 0) throw new IOException("Mesh cache vertices");
        byte[] data = new byte[bytes]; in.readFully(data);
        if (in.read() != -1) throw new IOException("Mesh cache trailing bytes"); // Also validates gzip CRC/trailer.
        var vertices = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        for (int offset = 0; offset < bytes; offset += 12)
            if (GroundFaceArenas.materialId(vertices, offset) >= count) throw new IOException("Mesh cache palette index");
        return new GroundQuadtreeMesher.Built(Face.VALUES[face], vertices, materials,
                new GroundQuadtreeMesher.Bounds(b[0], b[1], b[2], b[3], b[4], b[5]));
    }
    private static double finite(double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("Nonfinite mesh cache value");
        return value;
    }
}
