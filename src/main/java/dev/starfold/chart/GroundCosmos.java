package dev.starfold.chart;

/** Shared planet identity and flight alignment limits. */
public final class GroundCosmos {
    public static final int COUNT = 2;
    public static final double DISTANCE = 16384, FREE = 1536, ALIGN_END = 4096, HYSTERESIS = 512;

    /** Only an installed surface slot is a planet. */
    public static int valid(int id) {
        if (id < 0 || id >= PlanetShape.count()) throw new IllegalArgumentException("Planet");
        return id;
    }

    private GroundCosmos() {}
}
