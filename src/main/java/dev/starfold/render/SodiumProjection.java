package dev.starfold.render;

/**
 * Uploads the active frame's projection values to whichever GL program Sodium has just bound.
 * Called from Sodium's shader interface, after it binds the program for a terrain pass.
 */
public final class SodiumProjection {
    public static void apply() {
        GroundProjectionUniforms.applyNative();
    }

    private SodiumProjection() {}
}
