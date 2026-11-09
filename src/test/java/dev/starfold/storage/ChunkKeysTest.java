package dev.starfold.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChunkKeysTest {
    @Test
    void canonicalFaceKeysKeepEveryExistingStorageNumber() {
        for (int face = 0; face < 6; face++)
            for (int v = 0; v < 128; v++)
                for (int u = 0; u < 128; u++) {
                    long old = u + 128L * (v + 128L * face);
                    assertEquals(old, ChunkKeys.faceLocal(face, u, v));
                    assertEquals(old, ChunkKeys.local(face * 256 + u, v));
                    assertEquals(face * 256 + u, ChunkKeys.chunkX(old));
                    assertEquals(v, ChunkKeys.chunkZ(old));
                }
        assertEquals(ChunkKeys.CHUNKS_PER_PLANET - 1, ChunkKeys.faceLocal(5, 127, 127));
        for (int[] xy : new int[][] {{-1, 0}, {0, -1}, {128, 0}, {0, 128}, {1536, 0}})
            assertEquals(-1, ChunkKeys.local(xy[0], xy[1]));
        assertThrows(IllegalArgumentException.class, () -> ChunkKeys.chunkX(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkKeys.chunkZ(ChunkKeys.CHUNKS_PER_PLANET));
    }
}
