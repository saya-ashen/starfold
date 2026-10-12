package dev.starfold.space;

import dev.starfold.ship.Thrusters;

/** Pure hardware-to-speed rule. Reference is the shipped, unarmed dev.85 Hauler. */
public final class SpaceSpeed {
    public static final double V_REF = 600,
            A_REF = 8 * Thrusters.THRUST / 2228,
            V_MIN = 200,
            V_MAX = 2400;
    static final double STOP_TIME = 20;

    public record Caps(double cruise, double boost) {
        double active(boolean boosted) {
            return boosted ? boost : cruise;
        }

        double brake() {
            return Math.max(SpaceServer.BRAKE, boost / STOP_TIME);
        }
    }

    static double acceleration(double thrust, double mass) {
        return mass > 0 && Double.isFinite(mass) && thrust > 0 ? thrust / mass : 0;
    }

    static double cap(double acceleration) {
        return Double.isNaN(acceleration)
                ? V_MIN
                : Math.clamp(V_REF * Math.sqrt(Math.max(0, acceleration) / A_REF), V_MIN, V_MAX);
    }

    static Caps caps(double acceleration) {
        return new Caps(cap(acceleration), cap(acceleration * Thrusters.BOOST));
    }

    private SpaceSpeed() {}
}
