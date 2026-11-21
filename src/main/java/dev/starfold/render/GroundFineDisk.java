package dev.starfold.render;

import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.surface.SurfaceKey;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/** Deterministic step-one base columns, never edits, lighting, atlas IDs or GPU meshes. Safe to delete. */
final class GroundFineDisk implements AutoCloseable {
    static final int OPEN_REGIONS = 32, MAGIC = 0x53464631, VERSION = 1, HEADER = 12;
    private static final int COLUMNS = 64 * 64, MAX_RECORD = 8 + 8 * VoxelColumn.MAX_RUNS;
    private static final Set<Path> owners = new HashSet<>(), active = new HashSet<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundFineDisk.class);
    private final Path directory;
    private final long limit;
    private final int maxRegions;
    private final LinkedHashMap<Integer, Region> regions = new LinkedHashMap<>(32, .75f, true);
    private boolean closed;
    private long written, hits, misses, nextSpaceCheck;
    private boolean spaceAvailable;

    static Path directory(Path root, SurfaceKey key, String registry) {
        Path coarse = GroundBaseDisk.file(root, key, registry);
        String name = coarse.getFileName().toString().replace(".bin", "");
        return coarse.getParent().resolve("fine").resolve(name + "-v" + VERSION);
    }

    static GroundFineDisk open(SurfaceKey key) {
        try {
            return new GroundFineDisk(directory(Minecraft.getInstance().gameDirectory.toPath(),
                    key, GroundBaseDisk.registryStamp()), Long.MAX_VALUE, OPEN_REGIONS);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Could not open fine surface cache {}", key, failure);
            return null;
        }
    }

    GroundFineDisk(Path directory, long limit, int maxRegions) throws IOException {
        if (limit < 0 || maxRegions < 1) throw new IllegalArgumentException("Invalid fine disk allowance");
        this.directory = directory.toAbsolutePath().normalize();
        this.limit = limit;
        this.maxRegions = maxRegions;
        synchronized (GroundFineDisk.class) {
            if (owners.contains(this.directory)) throw new IOException("Fine cache already open: " + directory);
            Files.createDirectories(this.directory);
            owners.add(this.directory);
        }
    }

    /** Called on mesh workers, outside RAM-cache locks. Expensive generation never holds the IO lock. */
    long[] get(int face, int u, int v, Supplier<long[]> compute) {
        if (face < 0 || face >= 6 || u < 0 || u >= 2048 || v < 0 || v >= 2048)
            throw new IllegalArgumentException("Invalid fine column coordinates");
        int region = face * 1024 + v / 64 * 32 + u / 64;
        int column = v % 64 * 64 + u % 64;
        synchronized (this) {
            if (!closed) try {
                long[] value = region(region).read(column);
                if (value != null) { hits++; return value; }
            } catch (IOException failure) { disable(failure); }
            misses++;
        }
        long[] value = Objects.requireNonNull(compute.get());
        if (value.length > VoxelColumn.MAX_RUNS) throw new IllegalArgumentException("Too many packed runs");
        synchronized (this) {
            if (!closed) try {
                if (limit == Long.MAX_VALUE) {
                    long now = System.nanoTime();
                    if (now >= nextSpaceCheck) {
                        spaceAvailable = GroundDiskBudget.writable(directory);
                        nextSpaceCheck = now + 1_000_000_000L;
                    }
                    if (!spaceAvailable) return value;
                }
                var stored = region(region);
                var existing = stored.read(column);
                if (existing != null) return existing;
                written += stored.append(column, value);
                if (written >= 64L << 20) {
                    written = 0;
                    prune();
                }
            } catch (IOException failure) { disable(failure); }
        }
        return value;
    }

    private Region region(int id) throws IOException {
        Region region = regions.get(id);
        if (region != null) return region;
        if (regions.size() >= maxRegions) regions.pollFirstEntry().getValue().close();
        region = new Region(directory.resolve(id + ".bin"), id);
        regions.put(id, region);
        return region;
    }

    synchronized int openRegions() { return regions.size(); }
    synchronized long[] statistics() { return new long[] {hits, misses, regions.size()}; }

    private void disable(IOException failure) {
        LOG.warn("Disabling fine surface cache {}", directory, failure);
        close();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var region : regions.values()) region.close();
        regions.clear();
        synchronized (GroundFineDisk.class) { owners.remove(directory); }
        try { prune(); }
        catch (IOException failure) { LOG.warn("Could not prune fine surface cache {}", directory, failure); }
    }

    /** One global soft allowance for fine columns across planets/content versions, protecting open files. */
    private void prune() throws IOException {
        synchronized (GroundFineDisk.class) {
            List<Path> files;
            try (var paths = Files.walk(directory.getParent(), 2)) {
                files = new ArrayList<>(paths.filter(p -> p.toString().endsWith(".bin") && Files.isRegularFile(p)).toList());
            }
            files.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            long allBytes = 0;
            for (Path path : files) allBytes += Files.size(path);
            long allowance = limit == Long.MAX_VALUE ? GroundDiskBudget.allowance(directory, allBytes) : limit;
            long total = 0;
            for (Path path : files) {
                long bytes = Files.size(path);
                if (total + bytes > allowance && !active.contains(path)) Files.deleteIfExists(path);
                else total += bytes;
            }
        }
    }

    private static final class Region implements AutoCloseable {
        final Path path;
        final RandomAccessFile file;
        final int[] offsets = new int[COLUMNS];
        final byte[] counts = new byte[COLUMNS];
        long end;

        Region(Path path, int id) throws IOException {
            this.path = path;
            file = new RandomAccessFile(path.toFile(), "rw");
            try {
                if (file.length() < HEADER || file.readInt() != MAGIC || file.readInt() != VERSION
                        || file.readInt() != id) {
                    file.setLength(0);
                    file.seek(0);
                    file.writeInt(MAGIC); file.writeInt(VERSION); file.writeInt(id);
                }
                end = HEADER;
                // Buffered sequential indexing: bounded to this 64x64 patch, not the whole planet.
                try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                    input.skipNBytes(HEADER);
                    byte[] record = new byte[MAX_RECORD];
                    while (end < file.length() && end < HEADER + (long) COLUMNS * MAX_RECORD) {
                        try {
                            int column = input.readUnsignedShort(), count = input.readUnsignedShort();
                            if (column >= COLUMNS || count > VoxelColumn.MAX_RUNS) break;
                            ByteBuffer.wrap(record).putShort((short) column).putShort((short) count);
                            input.readFully(record, 4, count * 8 + 4);
                            int length = 8 + count * 8;
                            if (!valid(record, length)) break;
                            offsets[column] = (int) end;
                            counts[column] = (byte) count;
                            end += length;
                        } catch (EOFException truncated) { break; }
                    }
                }
                if (end != file.length()) file.setLength(end);
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
                synchronized (GroundFineDisk.class) { active.add(path); }
            } catch (IOException | RuntimeException failure) {
                file.close();
                throw failure;
            }
        }

        long[] read(int column) throws IOException {
            if (offsets[column] == 0) return null;
            int count = Byte.toUnsignedInt(counts[column]);
            byte[] bytes = new byte[8 + count * 8];
            file.seek(offsets[column]); file.readFully(bytes);
            if (!valid(bytes, bytes.length)) throw new IOException("Fine cache checksum mismatch: " + path);
            var buffer = ByteBuffer.wrap(bytes); buffer.position(4);
            long[] value = new long[count];
            for (int i = 0; i < count; i++) value[i] = buffer.getLong();
            return value;
        }

        int append(int column, long[] value) throws IOException {
            byte[] bytes = new byte[8 + value.length * 8];
            var buffer = ByteBuffer.wrap(bytes).putShort((short) column).putShort((short) value.length);
            for (long packed : value) buffer.putLong(packed);
            var crc = new CRC32(); crc.update(bytes, 0, bytes.length - 4);
            buffer.putInt((int) crc.getValue());
            file.seek(end); file.write(bytes);
            offsets[column] = (int) end;
            counts[column] = (byte) value.length;
            end += bytes.length;
            return bytes.length;
        }

        static boolean valid(byte[] bytes, int length) {
            var crc = new CRC32(); crc.update(bytes, 0, length - 4);
            return (int) crc.getValue() == ByteBuffer.wrap(bytes).getInt(length - 4);
        }

        @Override public void close() {
            try { file.close(); }
            catch (IOException failure) { LOG.warn("Could not close fine region {}", path, failure); }
            finally { synchronized (GroundFineDisk.class) { active.remove(path); } }
        }
    }
}
