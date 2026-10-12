package dev.starfold.chart;

import dev.starfold.planet.PlanetShape;

/** Shared planet identity and flight alignment limits. */
public final class GroundCosmos {
    public static final int COUNT = 2;
    public static final double DISTANCE = 16384, FREE = 1536, ALIGN_END = 4096, HYSTERESIS = 512;

    /** The old two, or as many as the system now running has. */
    public static int valid(int id) {
        if (id < 0 || id >= Math.max(COUNT, PlanetShape.count()))
            throw new IllegalArgumentException("Planet");
        return id;
    }

    private GroundCosmos() {}
}
