package dev.starfold.chart;

import dev.starfold.geometry.OrbitAttitude;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/** Native chart +Z forward is Minecraft's displayed ray, not its negation. */
public final class GroundCamera {
    public static Quaterniond capture(double yaw, double pitch) {
        return OrbitAttitude.upright(yaw, pitch);
    }

    public static double yaw(Quaterniondc chart, double fallback) {
        return OrbitAttitude.yaw(chart, fallback);
    }

    public static double pitch(Quaterniondc chart) {
        return OrbitAttitude.pitch(chart);
    }

    public static Quaterniond landing(
            GroundEphemeris.Model model, PlanetShape.Pose pose, Quaterniondc live, double time) {
        return landing(model, 0, pose, live, time);
    }

    public static Quaterniond landing(
            GroundEphemeris.Model model,
            int planet,
            PlanetShape.Pose pose,
            Quaterniondc live,
            double time) {
        return GroundView.upright(
                PlanetOrbit.frame(PlanetShape.of(planet), pose)
                        .rotation()
                        .conjugate()
                        .mul(model.at(planet, time).rotation().conjugate())
                        .mul(live)
                        .normalize());
    }

    private GroundCamera() {}
}
