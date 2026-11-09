package dev.starfold.api;

import dev.starfold.geometry.CubeSurfaceTopology;

/** CPU-only face transport for map layout and exploration at seams. */
public final class PlanetFaces {
    public record Adjacent(int face, int u, int v, int turns) {}
    public static Adjacent adjacent(int faceSize, int face, int u, int v) {
        var r = new CubeSurfaceTopology(faceSize).canonical(new CubeSurfaceTopology.Cell(
                CubeSurfaceTopology.Face.VALUES[face], u, 0, v));
        return new Adjacent(r.cell().face().ordinal(), r.cell().u(), r.cell().v(), r.turns());
    }
    private PlanetFaces() {}
}
