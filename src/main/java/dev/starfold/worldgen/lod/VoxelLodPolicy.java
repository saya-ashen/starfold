package dev.starfold.worldgen.lod;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

/** Display-only, planet-centred distance policy. Zero denotes the existing coarse surface. */
public final class VoxelLodPolicy {
    // Near tiles are no longer packed into a patch: caves/foliage may use one patch's cap per tile.
    public static final int RESIDENT_TILE_QUADS = 16384;
    public static final int COLUMNS_PER_STEP = 16, CELLS_PER_STEP = 8192, QUADS_PER_STEP = 128;
    public static final double HYSTERESIS = 32;
    public static final long FADE_NS = 500_000_000L;
    public static final long RESIDENT_BYTES = 3L * 1024 * 1024 * 1024;
    public static final int RESIDENT_TILES = 98304;
    // One complete bounded candidate, including its temporary CPU output, is reserved for
    // admission.
    public static final long TILE_BYTES =
            RESIDENT_TILE_QUADS * 4L * 32 + 8704 + 4L * (257 + 2 * VoxelColumns.MAX_RUNS);

    public static double distance(
            PlanetShape shape, Point camera, Face face, int x, int z, double height) {
        return camera.sub(shape.direction(face, x + 8, z + 8).scale(shape.radius() + height))
                .length();
    }

    /** Nominal vertical projection, in physical window pixels rather than GUI-scaled pixels. */
    public static double pixels(int step, double distance, double fov, int height) {
        if (!Double.isFinite(distance) || distance < 0 || !Double.isFinite(fov) || fov <= 0 || fov >= 180 || height < 1)
            throw new IllegalArgumentException("LOD projection");
        return step * height / (2 * Math.tan(Math.toRadians(fov) / 2) * Math.max(.001, distance));
    }
    public static int screenStep(double distance, int previous, double fov, int height) {
        double unit = pixels(1, distance, fov, height);
        int selected = 1;
        while (selected < 64 && unit * selected * 2 <= 1.25) selected *= 2;
        if (previous > 0) {
            if (previous > 64 || Integer.bitCount(previous) != 1) throw new IllegalArgumentException("Previous LOD");
            if (selected < previous && unit * previous <= 1.5) return previous;
            if (selected > previous && unit * selected > 1.1) return previous;
        }
        return selected;
    }
    public static int step(double distance, int previous) { return step(distance, previous, 70, 720); }
    public static int step(double distance, int previous, double fov, int height) {
        int selected = screenStep(distance, previous == 0 ? 16 : previous, fov, height);
        return selected > 8 ? 0 : selected;
    }

    /** Conservative visibility over a nominal sphere minus 64 blocks, allowing 192-block peaks.
     * Hidden hemispheres keep the L16–L64 fallback instead of consuming resident mesh budget. */
    public static boolean horizonVisible(PlanetShape shape, Point camera, Face face,int x,int z) {
        double eye=camera.length(), inner=Math.max(1,shape.radius()-64), target=shape.radius()+192;
        if(eye<=inner) return true;
        double cosine=(inner*inner-Math.sqrt(eye*eye-inner*inner)*Math.sqrt(target*target-inner*inner))/(eye*target);
        return camera.dot(shape.direction(face,x+8,z+8))/eye >= cosine;
    }

    public static int coarser(int step) {
        return step == 8 ? 0 : step * 2;
    }

    public static float fade(long committed, long now) {
        return (float) Math.clamp((double) (now - committed) / FADE_NS, 0, 1);
    }

    public static boolean admits(long bytes, long candidate, int tiles) {
        return candidate > 0
                && candidate <= TILE_BYTES
                && tiles < RESIDENT_TILES
                && bytes <= RESIDENT_BYTES - candidate;
    }

    private VoxelLodPolicy() {}
}
