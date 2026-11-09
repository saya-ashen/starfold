package dev.starfold.sky;

/** Pure geometry and coverage for the optional thin limb overlay, not the sky's optical shell. */
public final class AtmosphereRim {
    public record Section(double axial, double radial) {}

    public static double height(double radius) {
        return Math.min(
                AtmosphereSettings.RIM_HEIGHT, radius * AtmosphereSettings.RIM_MAX_RADIUS_FRACTION);
    }

    /** Each contour is tangent to its own shell radius, so width measures physical altitude. */
    public static Section section(double radius, double distance, double height) {
        double shell = radius + height;
        return new Section(
                shell * shell / distance,
                shell * Math.sqrt(Math.max(0, 1 - shell * shell / (distance * distance))));
    }

    /** Smooth visual responsibility transfer after leaving the optical shell, not a density change. */
    public static double highAltitudeWeight(double altitude) {
        double x =
                Math.clamp(
                        (altitude - AtmosphereSettings.RIM_SKY_FADE_START)
                                / (AtmosphereSettings.RIM_SKY_FADE_END
                                        - AtmosphereSettings.RIM_SKY_FADE_START),
                        0,
                        1);
        return x * x * (3 - 2 * x);
    }

    /** Native current-body atmosphere is owned by AFTER_SKY, independent of terrain mesh readiness. */
    public static double currentPassWeight(double altitude, boolean skyPass) {
        return skyPass ? highAltitudeWeight(altitude) : 0;
    }

    /** Zero value and slope at both edges avoid a bright inner seam and an outer cutoff. */
    public static double opacity(double fraction) {
        double x = Math.clamp(fraction, 0, 1);
        double edge = x * (1 - x);
        return AtmosphereSettings.RIM_OPACITY * 16 * edge * edge;
    }

    private AtmosphereRim() {}
}
