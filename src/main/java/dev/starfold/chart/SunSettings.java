package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;

/** New-world sun defaults and emissive surface colors; authored and saved radii stay authoritative. */
public final class SunSettings {
    public static final double DEFAULT_RADIUS = 768;

    /** Largest result of the generator's unchanged nextInt(257) draw. */
    public static final double MAX_GENERATED_RADIUS = generatedRadius(256);

    public static final Point SURFACE_LOW = new Point(1, .80, .42);
    public static final Point SURFACE_HIGH = new Point(1, .97, .82);

    /** Keep the original 257-way draw and seed mapping, including half-block radii after scaling. */
    public static double generatedRadius(int draw) {
        return (384 + draw) * 1.5;
    }

    private SunSettings() {}
}
