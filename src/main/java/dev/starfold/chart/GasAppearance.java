package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;

/** Deterministic, static body-local cloud bands. No surface terrain or weather simulation. */
public final class GasAppearance {
    public static final double BANDS = 36, WARP = 1.3, AMBIENT = .12;

    public static double phase(long seed) {
        long z = seed + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53 * (2 * Math.PI);
    }

    public static double blend(long seed, Point normal) {
        unit(normal);
        double phase = phase(seed);
        // Cartesian warp is continuous at the longitude seam AND poles; no undefined atan2.
        double warp = WARP * (normal.x() * Math.sin(phase) + normal.z() * Math.cos(phase));
        double value = .5 + .5 * Math.sin(normal.y() * BANDS + warp + phase);
        double t = Math.clamp((value - .2) / .6, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static Point color(int rgb) {
        if (rgb < 0 || rgb > 0xffffff) throw new IllegalArgumentException("Gas color");
        return new Point((rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0);
    }

    public static Point bands(GasGiant gas, Point normal) {
        double weight = blend(gas.seed(), normal);
        return color(gas.lowColor()).scale(1 - weight).add(color(gas.highColor()).scale(weight));
    }

    /** Non-emissive diffuse lighting with a small readability floor, not the sun's emissive shader. */
    public static Point lit(GasGiant gas, Point normal, Point towardSun, double transmission) {
        unit(normal);
        unit(towardSun);
        fraction(transmission);
        double light = AMBIENT + (1 - AMBIENT) * Math.max(0, normal.dot(towardSun)) * transmission;
        return bands(gas, normal).scale(light);
    }

    public static Point fogColor(GasGiant gas, double transmission) {
        fraction(transmission);
        return color(gas.lowColor())
                .add(color(gas.highColor()))
                .scale(.5 * (AMBIENT + (1 - AMBIENT) * transmission));
    }

    private static void unit(Point normal) {
        GroundEphemeris.point(normal);
        if (Math.abs(normal.length() - 1) > 1e-6) throw new IllegalArgumentException("Gas normal");
    }

    private static void fraction(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("Gas transmission");
    }

    private GasAppearance() {}
}
