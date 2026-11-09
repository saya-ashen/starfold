package dev.starfold.space;

import dev.starfold.geometry.BentStripGeometry.Point;

import java.util.function.DoubleUnaryOperator;

/** Space velocities are blocks/tick; only the rendering/physics safety ceiling remains. */
public final class SpaceSpeed {
    public static final double DEEP_MAX = 20000;

    /** Inverse of Sable's v / (1 + drag * step), not a vacuum thrust bonus. */
    static Point undamped(Point velocity, double dragStep) {
        return velocity.scale(1 + dragStep);
    }

    static Point limit(Point velocity) {
        double speed = velocity.length();
        return speed > DEEP_MAX / 20 ? velocity.scale(DEEP_MAX / 20 / speed) : velocity;
    }

    /** Charge every change of the velocity vector, including sideways guidance and stopping. */
    static Point maneuver(Point velocity, Point wanted, double step, DoubleUnaryOperator burn) {
        var change = wanted.sub(velocity);
        double length = change.length();
        if (length == 0) return velocity;
        double available = burn.applyAsDouble(Math.min(length, step) * 20) / 20;
        return velocity.add(change.scale(Math.min(length, available) / length));
    }

    private SpaceSpeed() {}
}
