package dev.starfold.ship;

import dev.ryanhcode.sable.sublevel.SubLevel;

/** Pilot steering limits in degrees per game tick. Longer hulls build turn rate more slowly. */
public final class HelmTurn {
    public static double span(SubLevel body) {
        var box = body.getPlot().getBoundingBox();
        return Math.max(box.maxX() - box.minX() + 1, box.maxZ() - box.minZ() + 1);
    }

    public static double rate(double span, boolean space) {
        return scale(span) * (space ? 3 : 1.5);
    }

    public static double acceleration(double span, boolean space) {
        double scale = scale(span);
        return scale * scale * (space ? .65 : .32);
    }

    private static double scale(double span) {
        return Math.clamp(Math.sqrt(24 / Math.max(1, span)), .25, 1);
    }

    private HelmTurn() {}
}
