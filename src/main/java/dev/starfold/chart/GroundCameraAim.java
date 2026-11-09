package dev.starfold.chart;

import dev.starfold.planet.GroundCoordinates;
import dev.starfold.worldgen.PlanetTerrain;

/** Free flight and its camera begin above generated terrain and the saved corner columns. */
public final class GroundCameraAim {
    public static final double LOOK_START = start(GroundSpace.DEFAULT_MASK);

    public static double start(GroundSpace.Config mask) {
        // Terrain bounds use storage Y; columns use local height. Poses are .9 above the feet.
        return Math.max(PlanetTerrain.MAX_Y - GroundCoordinates.FLOOR, mask.height()) + 16 + .9;
    }

    private GroundCameraAim() {}
}
