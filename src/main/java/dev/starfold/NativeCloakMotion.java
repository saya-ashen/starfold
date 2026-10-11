package dev.starfold;

/** Vanilla quarter-step relaxation, evaluated in the carrying hull rather than the world. */
public final class NativeCloakMotion {
    public static double follow(double cloak,double position){double delta=position-cloak;return Math.abs(delta)>10?position:cloak+delta*.25;}
    private NativeCloakMotion(){}
}
