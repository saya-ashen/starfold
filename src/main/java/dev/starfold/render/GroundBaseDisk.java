package dev.starfold.render;

import dev.starfold.worldgen.lod.VoxelSurfaceCache;
import dev.starfold.worldgen.surface.SurfaceKey;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Resumable journal of deterministic coarse patches. Safe to delete. */
final class GroundBaseDisk implements AutoCloseable {
    // Bump for changes to VoxelColumn.visible/reduce or other display-content rules not already
    // represented by SurfaceKey. Unrelated rebuilds must not invalidate expensive terrain work.
    static final String CONTENT_VERSION = "voxel-material-shape-3";
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GroundBaseDisk.class);
    private static final Set<Path> openFiles = new HashSet<>();
    private static String registryStamp;

    private final Path path;
    private final RandomAccessFile journal;
    private boolean loading = true, closed;
    private long validEnd, pruneAt;
    private int loadedPatches;

    static GroundBaseDisk open(SurfaceKey key, VoxelSurfaceCache cache) {
        try {
            return open(
                    file(Minecraft.getInstance().gameDirectory.toPath(), key, registryStamp()),
                    cache);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Could not open surface cache {}", key, failure);
            return null;
        }
    }

    /** Also used by focused tests without a Minecraft client. */
    static GroundBaseDisk open(Path path, VoxelSurfaceCache cache) throws IOException {
        synchronized (GroundBaseDisk.class) {
            if (!openFiles.add(path))
                throw new IOException("Surface journal already open: " + path);
        }
        RandomAccessFile journal = null;
        try {
            Files.createDirectories(path.getParent());
            journal = new RandomAccessFile(path.toFile(), "rw");
            boolean header;
            try {
                header = journal.length() != 0 && cache.readHeader(journal);
            } catch (EOFException truncated) {
                header = false;
            }
            if (!header) {
                journal.setLength(0);
                journal.seek(0);
                cache.writeHeader(journal);
            }
            var disk = new GroundBaseDisk(path, journal);
            LOG.info("Opened surface cache {} bytes {}", path.getFileName(), journal.length());
            Files.setLastModifiedTime(
                    path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return disk;
        } catch (IOException | RuntimeException failure) {
            if (journal != null)
                try {
                    journal.close();
                } catch (IOException closing) {
                    failure.addSuppressed(closing);
                }
            synchronized (GroundBaseDisk.class) {
                openFiles.remove(path);
            }
            throw failure;
        }
    }

    private GroundBaseDisk(Path path, RandomAccessFile journal) throws IOException {
        this.path = path;
        this.journal = journal;
        validEnd = journal.getFilePointer();
        pruneAt = journal.length() + (8L << 20);
    }

    /** One scheduling quantum. A damaged tail is removed so later appends remain readable. */
    synchronized boolean loadNext(VoxelSurfaceCache cache, IntConsumer publish) throws IOException {
        if (closed || !loading) return false;
        try {
            if (cache.readPatch(journal, publish) != -1) {
                loadedPatches++;
                validEnd = journal.getFilePointer();
                return true;
            }
        } catch (IOException corrupt) {
            LOG.warn("Discarding damaged surface cache tail {} at {}", path, validEnd, corrupt);
            journal.setLength(validEnd);
        }
        journal.seek(validEnd);
        loading = false;
        LOG.info("Loaded surface cache {} patches {}", path.getFileName(), loadedPatches);
        return false;
    }

    /** Called as soon as a patch is complete, including before cancellation or eviction. */
    synchronized void savePatch(VoxelSurfaceCache cache, int key) throws IOException {
        if (closed) return;
        if (loading) throw new IllegalStateException("Surface journal still loading");
        if (!GroundDiskBudget.writable(path)) return;
        journal.seek(validEnd);
        try {
            cache.writePatch(journal, key);
            validEnd = journal.getFilePointer();
        } catch (IOException failure) {
            journal.setLength(validEnd);
            throw failure;
        }
        if (validEnd >= pruneAt || cache.complete()) {
            pruneAt = validEnd + (8L << 20);
            prune(path);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            journal.close();
        } catch (IOException failure) {
            LOG.warn("Could not close surface cache {}", path, failure);
        } finally {
            synchronized (GroundBaseDisk.class) {
                openFiles.remove(path);
            }
        }
    }

    /** Active journals are protected; the budget can be exceeded until those entries finish. */
    private static synchronized void prune(Path kept) throws IOException {
        String planet = kept.getFileName().toString().substring(0, 16);
        List<Path> files;
        try (var listing = Files.list(kept.getParent())) {
            files = new ArrayList<>(listing.filter(p -> p.toString().endsWith(".bin")).toList());
        }
        for (var it = files.iterator(); it.hasNext(); ) {
            var p = it.next();
            if (!p.equals(kept)
                    && !openFiles.contains(p)
                    && p.getFileName().toString().startsWith(planet)) {
                Files.deleteIfExists(p);
                it.remove();
            }
        }
        files.sort(
                Comparator.comparing(
                                (Path p) -> {
                                    try {
                                        return Files.getLastModifiedTime(p);
                                    } catch (IOException e) {
                                        return java.nio.file.attribute.FileTime.fromMillis(0);
                                    }
                                })
                        .reversed());
        long total = 0;
        for (var p : files) total += Files.size(p);
        long allowance = GroundDiskBudget.allowance(kept, total);
        for (var it = files.reversed().iterator(); it.hasNext() && total > allowance; ) {
            var p = it.next();
            if (!p.equals(kept) && !openFiles.contains(p)) {
                long bytes = Files.size(p);
                if (Files.deleteIfExists(p)) total -= bytes;
            }
        }
    }

    static Path file(Path root, SurfaceKey key, String registry) {
        return root.resolve("starfold-cache")
                .resolve("surface")
                .resolve(
                        hash(key.toString())
                                + "-"
                                + hash(
                                        CONTENT_VERSION
                                                + "/format-"
                                                + VoxelSurfaceCache.FORMAT_VERSION
                                                + "/registry-"
                                                + registry)
                                + ".bin");
    }

    /** Packed runs contain numeric state IDs. Include their ordered meanings, not jar metadata. */
    static synchronized String registryStamp() {
        if (registryStamp == null) {
            var text = new StringBuilder();
            for (var state : Block.BLOCK_STATE_REGISTRY) text.append(state).append('\n');
            registryStamp = hash(text.toString());
        }
        return registryStamp;
    }

    private static String hash(String text) {
        try {
            var digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
