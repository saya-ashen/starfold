package dev.starfold.storage;

/** Pure addressing for the persisted six-face layout; no world or derived-view dependencies. */
public final class ChunkKeys {
    public static final int FACE_COUNT = 6;
    public static final int FACE_WIDTH = 128;
    public static final int FACE_STRIDE = 256;
    public static final int CHUNKS_PER_FACE = FACE_WIDTH * FACE_WIDTH;
    public static final int CHUNKS_PER_PLANET = FACE_COUNT * CHUNKS_PER_FACE;

    /** Returns -1 for chart gaps, halos, and positions outside the canonical owners. */
    public static long local(int chunkX, int chunkZ) {
        return local(chunkX, chunkZ, FACE_WIDTH);
    }

    /** A planet whose faces are only width chunks across owns that corner of each face's storage; keys keep their numbering. */
    public static long local(int chunkX, int chunkZ, int width) {
        if (width <= 0 || width > FACE_WIDTH) {
            throw new IllegalArgumentException("Face width in chunks");
        }
        int face = Math.floorDiv(chunkX, FACE_STRIDE);
        int u = Math.floorMod(chunkX, FACE_STRIDE);
        if (face < 0 || face >= FACE_COUNT || u >= width || chunkZ < 0 || chunkZ >= width) {
            return -1;
        }
        return u + (long) FACE_WIDTH * (chunkZ + (long) FACE_WIDTH * face);
    }

    public static int chunkX(long key) {
        validate(key);
        return (int) (key / CHUNKS_PER_FACE) * FACE_STRIDE + (int) (key % FACE_WIDTH);
    }

    public static int chunkZ(long key) {
        validate(key);
        return (int) (key / FACE_WIDTH % FACE_WIDTH);
    }

    private static void validate(long key) {
        if (key < 0 || key >= CHUNKS_PER_PLANET) {
            throw new IllegalArgumentException("Planet-local chunk key");
        }
    }

    private ChunkKeys() {}
}
