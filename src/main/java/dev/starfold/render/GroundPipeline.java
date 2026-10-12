package dev.starfold.render;

import java.util.Arrays;
import java.util.Objects;

/** Display policy only: complete cohorts, no world or flight-state changes. */
public final class GroundPipeline {
    public static double lowFrequency(double height) {
        double t = Math.clamp((height - 96) / 32, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static float channel(int color, int shift) {
        return (color >> shift & 255) / 255f * 2 - 1;
    }

    public static boolean complete(Object[] resident) {
        return Arrays.stream(resident).allMatch(Objects::nonNull);
    }

    public static boolean publish(
            int pending, boolean building, Object[] resident, Object[] staging) {
        if (pending != 0 || building || Arrays.stream(staging).allMatch(Objects::isNull))
            return false;
        for (int i = 0; i < resident.length; i++)
            if (resident[i] == null && staging[i] == null) return false;
        return true;
    }

    private GroundPipeline() {}
}
