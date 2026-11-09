package dev.starfold.sky;

/** Visual-only R1 controls. Distances are blocks; optical coefficients are per scale height. */
public final class AtmosphereSettings {
    public static final int VIEW_SAMPLES = 6, SUN_SAMPLES = 4;
    public static final int SKY_LONGITUDES = 48,
            SKY_LATITUDES = 24,
            RIM_SEGMENTS = 96,
            RIM_BANDS = 4;
    public static final double SCALE_HEIGHT = 48, SHELL_HEIGHTS = 6;
    // Calibrated for the small planets' H/R: enough slant extinction for a warm sunset.
    public static final double RAYLEIGH = 1.05, MIE = .012, MIE_G = .60, EXPOSURE = 20;

    /** Squared RGB tint approximates wavelength-dependent Rayleigh coefficients. */
    public static final double TINT_POWER = 2;

    /** Luminance at which the cached star field is almost completely washed out. */
    public static final double STAR_EXTINCTION = 18;

    public static final double FOG_DENSITY_FLOOR = .02;
    public static final double GRADING_STRENGTH = .075;

    /** Halo radius relative to sun disc: vacuum + atmosphere + low-sun widening. */
    public static final double HALO_VACUUM = .16, HALO_AIR = 1.5, HALO_LOW_SUN = 1.5;

    public static final double HALO_STRENGTH = .24, HALO_AIR_STRENGTH = .45;

    /** Rim-only visual shell: independent of the shared sky/fog optical layer. */
    public static final double RIM_HEIGHT = 36, RIM_MAX_RADIUS_FRACTION = .035;

    /** Peak coverage, not extra additive radiance over the already scattered background. */
    public static final double RIM_OPACITY = .28;

    /** Current-body visual handover only, starting outside the 288-block optical shell.
     * Ground sky/fog coefficients and the region/remote-body rim are unaffected. */
    public static final double RIM_SKY_FADE_START = 288, RIM_SKY_FADE_END = 576;

    public static final double HALO_WARM_GREEN = .35, HALO_WARM_BLUE = .70;
    public static final double REMOTE_HAZE = .65;

    /** Distant sub-pixel rims need no separate draw or CPU integration. */
    public static final double MIN_RIM_ANGULAR_RADIUS = .002;

    public static final int MIN_RIM_SEGMENTS = 12;
    public static final double RIM_ANGULAR_RESOLUTION = 4;

    private AtmosphereSettings() {}
}
