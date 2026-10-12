package dev.starfold.ship;

/** Pure client engine envelope. One spool per hull follows the thrust in use; volume and pitch only follow the spool. */
public final class ThrusterSound {
    /** Opposed nozzles never burn together, so half the installed thrusters at full is a whole hull's worth. */
    static final double FULL_SHARE = .5,
            DRY = .75,
            IDLE = .1,
            SPOOL_UP = 1 / 20.0,
            SPOOL_DOWN = 1 / 40.0;

    static final double VOLUME = 1, BASE_PITCH = .7, PITCH_RANGE = .4;
    static final int RANGE = 64;

    /** Spool the engines settle at: commanded strength (15 a thruster) over what the hull carries; the booster runs whatever burns flat out. */
    static double target(double power, int thrusters, boolean boost, boolean piloted) {
        double share =
                thrusters > 0 && power > 0 ? Math.min(1, power / (15 * thrusters * FULL_SHARE)) : 0;
        return Math.max(piloted ? IDLE : 0, boost && share > 0 ? 1 : share * DRY);
    }

    static double spool(double current, double target) {
        return current + Math.clamp(target - current, -SPOOL_DOWN, SPOOL_UP);
    }

    static double volume(double spool) {
        return VOLUME * Math.clamp(spool, 0, 1);
    }

    static double pitch(double spool) {
        return BASE_PITCH + PITCH_RANGE * Math.clamp(spool, 0, 1);
    }

    private ThrusterSound() {}
}
