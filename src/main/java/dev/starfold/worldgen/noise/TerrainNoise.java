package dev.starfold.worldgen.noise;

import dev.starfold.geometry.BentStripGeometry.Point;

/** Seeded quintic 3D value noise, in physical blocks; shared version-one kernel arithmetic. */
public final class TerrainNoise {
    private final long seed;
    private static final class Cell {
        TerrainNoise owner;
        long salt;
        int a, b, c;
        final double[] values = new double[8];
    }
    private static final ThreadLocal<Cell[]> CELLS = ThreadLocal.withInitial(() -> {
        var cells = new Cell[64];
        for (int i = 0; i < cells.length; i++) cells[i] = new Cell();
        return cells;
    });

    public TerrainNoise(long seed) {
        this.seed = seed;
    }

    public double sample(Point p, double spacing, long salt) {
        double x = p.x() / spacing, y = p.y() / spacing, z = p.z() / spacing;
        int a = (int) Math.floor(x), b = (int) Math.floor(y), c = (int) Math.floor(z);
        double u = fade(x - a), v = fade(y - b), w = fade(z - c);
        var cell = CELLS.get()[(int) (salt & 63)];
        var n = cell.values;
        if (cell.owner != this || cell.salt != salt || cell.a != a || cell.b != b || cell.c != c) {
            cell.owner = this; cell.salt = salt; cell.a = a; cell.b = b; cell.c = c;
            n[0] = value(a,b,c,salt); n[1] = value(a+1,b,c,salt);
            n[2] = value(a,b+1,c,salt); n[3] = value(a+1,b+1,c,salt);
            n[4] = value(a,b,c+1,salt); n[5] = value(a+1,b,c+1,salt);
            n[6] = value(a,b+1,c+1,salt); n[7] = value(a+1,b+1,c+1,salt);
        }
        return lerp(lerp(lerp(n[0],n[1],u),lerp(n[2],n[3],u),v),
            lerp(lerp(n[4],n[5],u),lerp(n[6],n[7],u),v),w);
    }

    /** Weighted ridges modulate the next octave, producing spurs and interrupted saddles. */
    public double ridged(Point p, double spacing, long salt) {
        double sum = 0, weight = 1, amplitude = .57;
        for (int i = 0; i < 4; i++) {
            double ridge = 1 - Math.abs(sample(p, spacing, salt + i) * 1.7);
            ridge = Math.max(0, ridge);
            ridge *= ridge;
            sum += ridge * weight * amplitude;
            weight = Math.clamp(ridge * 1.8, 0, 1);
            spacing *= .48;
            amplitude *= .5;
        }
        return sum;
    }

    private double value(int x, int y, int z, long salt) {
        return (mix(
                                        seed
                                                ^ mix(x * 0x9e3779b97f4a7c15L)
                                                ^ mix(y * 0x632be59bd9b4e019L)
                                                ^ mix(z * 0x94d049bb133111ebL)
                                                ^ salt)
                                >>> 11)
                        * 0x1.0p-52
                - 1;
    }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static double fade(double x) {
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
