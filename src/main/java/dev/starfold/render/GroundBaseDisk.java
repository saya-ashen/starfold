package dev.starfold.render;

import dev.starfold.worldgen.lod.VoxelSurfaceCache;
import dev.starfold.worldgen.surface.SurfaceKey;
import net.minecraft.client.Minecraft;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Client disk copy of a planet's coarse voxel base: deterministic per planet key and mod jar, so a
 *  revisit skips the whole-planet fill. Safe to delete; any new jar starts a fresh file. */
final class GroundBaseDisk {
    static final long LIMIT = 1L << 30;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundBaseDisk.class);

    static boolean load(SurfaceKey key, VoxelSurfaceCache cache, IntConsumer publish) {
        var file = file(key);
        if (file == null || !Files.isRegularFile(file)) return false;
        long start = System.nanoTime();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (!cache.read(in, publish)) return false;
            LOG.info("Loaded surface cache {} in {} ms", file.getFileName(), (System.nanoTime() - start) / 1_000_000);
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return true;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Ignoring unreadable surface cache {}", file, failure);
            return false;
        }
    }

    static void save(SurfaceKey key, VoxelSurfaceCache cache) {
        var file = file(key);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary), 1 << 16))) {
                cache.write(out);
            }
            // A same-directory rename: readers see the old file or the complete new one.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            prune(file);
            LOG.info("Saved surface cache {} ({} MiB)", file.getFileName(), Files.size(file) >> 20);
        } catch (IOException failure) {
            LOG.warn("Could not save surface cache {}", file, failure);
        }
    }

    /** Drops this planet's files from older jars, then the least recently used beyond {@link #LIMIT}. */
    private static void prune(Path kept) throws IOException {
        String planet = kept.getFileName().toString().substring(0, 16);
        List<Path> files;
        try (var listing = Files.list(kept.getParent())) {
            files = new ArrayList<>(listing.filter(p -> p.toString().endsWith(".bin")).toList());
        }
        for (var it = files.iterator(); it.hasNext(); ) {
            var p = it.next();
            if (!p.equals(kept) && p.getFileName().toString().startsWith(planet)) { Files.deleteIfExists(p); it.remove(); }
        }
        files.sort(Comparator.comparing((Path p) -> {
            try { return Files.getLastModifiedTime(p); } catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
        }).reversed());
        long total = 0;
        for (var p : files) {
            total += Files.size(p);
            if (total > LIMIT && !p.equals(kept)) Files.deleteIfExists(p);
        }
    }

    private static Path file(SurfaceKey key) {
        var jar = jarStamp();
        if (jar == null) return null;
        return Minecraft.getInstance().gameDirectory.toPath().resolve("starfold-cache").resolve("surface")
                .resolve(hash(key.toString()) + "-" + hash(jar) + ".bin");
    }

    private static String jarStamp;

    /** Size and mtime of the loaded mod file: a new build or update never reads an old algorithm's data. */
    private static synchronized String jarStamp() {
        if (jarStamp == null) {
            try {
                var path = net.neoforged.fml.ModList.get().getModFileById("starfold").getFile().getFilePath();
                jarStamp = Files.size(path) + "/" + Files.getLastModifiedTime(path).toMillis();
            } catch (IOException | RuntimeException failure) {
                return null;
            }
        }
        return jarStamp;
    }

    private static String hash(String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private GroundBaseDisk() {}
}
