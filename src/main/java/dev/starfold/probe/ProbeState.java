package dev.starfold.probe;

import java.util.function.BooleanSupplier;

/**
 * The one thing production code asks about development probes: whether a probe script is driving
 * the client, in which case input is read as if the window had focus. Kept apart from the probe
 * itself so that nothing outside this package depends on it.
 */
public final class ProbeState {
    private static BooleanSupplier driving = () -> false;

    private ProbeState() {}

    public static boolean active() {
        return driving.getAsBoolean();
    }

    public static void drivenBy(BooleanSupplier probe) {
        driving = probe;
    }
}
