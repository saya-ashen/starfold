package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.storage.ChunkKeys;

/** Canonical face tile addressing shared by captured and generated surface readers. */
public final class GroundTileKeys {
    public static long key(int face, int x, int z) {
        return key(PlanetShape.STANDARD, face, x, z);
    }

    public static long key(PlanetShape shape, int face, int x, int z) {
        var c =
                shape.topology()
                        .canonical(
                                new CubeSurfaceTopology.Cell(
                                        CubeSurfaceTopology.Face.VALUES[face], x, 0, z))
                        .cell();
        return ChunkKeys.faceLocal(c.face().ordinal(), c.u() / 16, c.v() / 16);
    }

    static boolean owned(PlanetShape shape, long key) {
        if (key < 0 || key >= ChunkKeys.CHUNKS_PER_PLANET) return false;
        int width = shape.chunks();
        return ChunkKeys.chunkX(key) % ChunkKeys.FACE_STRIDE < width
                && ChunkKeys.chunkZ(key) < width;
    }

    private GroundTileKeys() {}
}
