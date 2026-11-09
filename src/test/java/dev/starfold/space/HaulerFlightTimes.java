package dev.starfold.space;

import dev.starfold.ship.Thrusters;

/** Unlimited-fuel, static-mass estimate; actual trips require a fuel/Δv budget. */
public final class HaulerFlightTimes {
    public static double seconds(double distance, boolean boost) {
        double speed = SpaceSpeed.DEEP_MAX;
        double acceleration = 8 * Thrusters.THRUST / 2260.75 * (boost ? Thrusters.BOOST : 1);
        return distance < speed * speed / (2 * acceleration)
                ? Math.sqrt(2 * distance / acceleration)
                : distance / speed + speed / (2 * acceleration);
    }

    private HaulerFlightTimes() {}
}
