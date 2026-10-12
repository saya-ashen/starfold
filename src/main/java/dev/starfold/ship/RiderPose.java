package dev.starfold.ship;

/** Pure pose-query policy. This does not seat an entity or change movement collision. */
public final class RiderPose {
    public static boolean passengerDecision(boolean passenger, boolean liveHelm) {
        return passenger || liveHelm;
    }

    public static double inset(boolean standingOrCrouching, Object carrying, Object tested) {
        return standingOrCrouching && carrying != null && carrying == tested ? 1e-3 : 0;
    }

    private RiderPose() {}
}
