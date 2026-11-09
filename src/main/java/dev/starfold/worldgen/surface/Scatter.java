package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;

/** Shared canonical addressing and deterministic generation hash. */
public final class Scatter {
    static Cell canonical(PlanetTerrain t, Face face, int u, int v) {
        return t.shape().topology().canonical(new Cell(face, u, 0, v)).cell();
    }

    public static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private Scatter() {}
}
