package dev.starfold.chart;

import dev.starfold.geometry.OrbitAttitude;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Upright ground navigation (+Z look, +X screen-left). Orbital six-axis controls stay unchanged. */
public final class GroundView {
    public static final double PITCH_LIMIT = 89.5;

    /** Remove existing roll while keeping the forward ray, except for the half-degree pole margin. */
    public static Quaterniond upright(Quaterniondc attitude) {
        if (attitude == null
                || !Double.isFinite(attitude.lengthSquared())
                || attitude.lengthSquared() < 1e-12)
            throw new IllegalArgumentException("Ground attitude");
        var q = new Quaterniond(attitude).normalize();
        var left = q.transform(new Vector3d(1, 0, 0));
        double fallback = Math.toDegrees(Math.atan2(left.z, left.x));
        return OrbitAttitude.upright(
                OrbitAttitude.yaw(q, fallback),
                Math.clamp(OrbitAttitude.pitch(q), -PITCH_LIMIT, PITCH_LIMIT));
    }

    /** Degrees: yaw about chart +Y, pitch bounded about the upright horizontal left axis. */
    public static Quaterniond turn(Quaterniondc attitude, double yaw, double pitch) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch))
            throw new IllegalArgumentException("Mouse input");
        var q = upright(attitude);
        return OrbitAttitude.upright(
                OrbitAttitude.yaw(q, 0) + yaw,
                Math.clamp(OrbitAttitude.pitch(q) + pitch, -PITCH_LIMIT, PITCH_LIMIT));
    }

    /** Minecraft camera uses -Z look and -X left; this conversion does not add roll. */
    public static Quaterniond camera(Quaterniondc attitude) {
        return new Quaterniond(attitude).rotateY(Math.PI);
    }

    private GroundView() {}
}
