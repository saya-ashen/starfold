package dev.starfold.sky;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;

/** Per-disc visual parameters, independent of the single light used by sky scattering. */
public final class SunHalo {
    public record Parameters(double width, double strength, Point color) {}

    /** All positions are in universe axes, matching GroundClient.elevation's radial-up rule. */
    public static Parameters forSun(double air, Point center, Point eye, Point sun) {
        var up = PlanetSpace.unit(eye.sub(center));
        var delta = sun.sub(eye);
        double elevation =
                up.dot(delta.length() < 1e-9 ? GroundEphemeris.Y : delta.scale(1 / delta.length()));
        return parameters(air, elevation);
    }

    public static Parameters vacuum() {
        return parameters(0, 0);
    }

    private static Parameters parameters(double air, double elevation) {
        double low = 1 - Math.clamp(elevation, 0, 1);
        return new Parameters(
                AtmosphereSettings.HALO_VACUUM
                        + air
                                * (AtmosphereSettings.HALO_AIR
                                        + low * AtmosphereSettings.HALO_LOW_SUN),
                AtmosphereSettings.HALO_STRENGTH * (1 + air * AtmosphereSettings.HALO_AIR_STRENGTH),
                new Point(
                        1,
                        1 - AtmosphereSettings.HALO_WARM_GREEN * air * low,
                        1 - AtmosphereSettings.HALO_WARM_BLUE * air * low));
    }

    private SunHalo() {}
}
