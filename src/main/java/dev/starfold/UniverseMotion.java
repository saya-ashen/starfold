package dev.starfold;

/** Shared altitude-dependent flight speed, in blocks per tick. */
public final class UniverseMotion {
    public static double speed(double height,boolean fast){return fast?Math.clamp(height*.06,1.2,64):Math.clamp(height*.012,.35,12);}
    private UniverseMotion(){}
}
