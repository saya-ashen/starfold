package dev.starfold.render;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Received edit surfaces only; optional near voxels are reduced to sparse surface runs. */
final class GroundSurfaceDisk {
    private static final int FORMAT = 1;

    static Map<Long, GroundSurface> load(Path file, BitSet changed) throws IOException {
        var images = new HashMap<Long, GroundSurface>();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FORMAT
                    || in.readInt() != dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION
                    || in.readInt() != GroundSurfaceIndex.SURFACE_FORM)
                throw new IOException("Surface cache format");
            int count = in.readInt();
            if (count < 0 || count > GroundViewPackets.CHUNKS)
                throw new IOException("Surface cache count");
            var seen = new BitSet();
            for (int i = 0; i < count; i++) {
                long key = in.readLong(), revision = in.readLong();
                int size = in.readInt();
                if (key < 0 || key >= GroundViewPackets.CHUNKS || seen.get((int) key)
                        || revision < 0 || size < 1542 || size > GroundSurfaceCodec.MAX_BYTES)
                    throw new IOException("Surface cache entry");
                seen.set((int) key);
                byte[] packed = new byte[size];
                in.readFully(packed);
                var surface = GroundSurfaceCodec.unpack(revision, packed);
                if (changed.get((int) key)) images.put(key, surface);
            }
            if (in.read() != -1) throw new IOException("Surface cache trailing bytes");
        }
        return images;
    }

    static void save(Path file, Map<Long, GroundSurface> images) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(FORMAT);
            out.writeInt(dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION);
            out.writeInt(GroundSurfaceIndex.SURFACE_FORM);
            out.writeInt(images.size());
            for (var entry : images.entrySet()) {
                out.writeLong(entry.getKey());
                out.writeLong(entry.getValue().revision());
                byte[] packed = GroundSurfaceCodec.pack(entry.getValue().surfaceOnly());
                out.writeInt(packed.length);
                out.write(packed);
            }
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private GroundSurfaceDisk() {}
}
