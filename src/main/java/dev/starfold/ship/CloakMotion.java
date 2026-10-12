package dev.starfold.ship;

/** Vanilla quarter-step relaxation, evaluated in the carrying hull rather than the world. */
public final class CloakMotion {
    public static double follow(double cloak, double position) {
        double delta = position - cloak;
        return Math.abs(delta) > 10 ? position : cloak + delta * .25;
    }

    private CloakMotion() {}
}
