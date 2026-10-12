package dev.starfold.geometry;

import dev.starfold.geometry.BentStripGeometry.Point;

/** Fixed, axis-permutation symmetric cube-to-sphere map. No player, time or altitude blend.
 * Ordinary seam/interior cells take priority; true corner columns have no physical volume.
 * The diagnostic chart remains continuous/invertible even inside those omitted columns.
 * Coefficients and exhaustive finite-grid admission are in tools/audit_balanced_geometry.py.
 * This is a bounded compromise, not a conformal map or a claim of globally optimal cubes. */
public final class PlanetBalancedGeometry {
    public static final double BASE = .035,
            OWN = .0475,
            OTHER = .07125,
            SEAM = .515,
            SPREAD = .2825;
    private static final Warp ORIGINAL = new Warp(BASE, OWN, OTHER, SEAM, SPREAD);

    private PlanetBalancedGeometry() {}

    public static Point direction(Point cube) {
        return ORIGINAL.direction(cube);
    }

    public static Pair inverse(double x, double z) {
        return ORIGINAL.inverse(x, z);
    }

    public record Pair(double a, double b) {}

    /** Immutable coefficients: shared formula, separate admitted historical/current shapes. */
    public record Warp(double BASE, double OWN, double OTHER, double SEAM, double SPREAD) {
        private static double tangent(double a) {
            return a == 1 ? 1 : a == -1 ? -1 : Math.tan(a * Math.PI / 4);
        }

        private double component(double a, double t) {
            double s = a * a, p = 1 - s, q = 1 - t, den = p + q;
            // pq/(p+q) tends continuously to zero at the tip. Unlike a low-order polynomial,
            // its transverse derivative can correct a seam right up to the adjacent ordinary cell.
            double harmonic = den > 0 ? p * q / den : 0;
            double spread = SPREAD + (1 - SPREAD) * s * t;
            return a * (1 + p * t * (BASE + OWN * s + OTHER * t) - SEAM * harmonic * spread);
        }

        public Point direction(Point cube) {
            double a = tangent(cube.x()), b = tangent(cube.y()), c = tangent(cube.z());
            // On a cube boundary one squared coordinate is exactly 1. This symmetric expression
            // gives the same result at every incident face, including all eight three-valent tips.
            return PlanetSpace.unit(
                    new Point(
                            component(a, b * b + c * c - 1),
                            component(b, a * a + c * c - 1),
                            component(c, a * a + b * b - 1)));
        }

        /** Inverts face-plane coordinates, not triangles/rays. Used for labels and texture corners.
         * Rendering, picking and collision still use the exact same fixed faceted mesh. */
        public Pair inverse(double x, double z) {
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || Math.abs(x) > 1 + 1e-8
                    || Math.abs(z) > 1 + 1e-8)
                throw new IllegalArgumentException("Not on the selected cube chart");
            x = Math.clamp(x, -1, 1);
            z = Math.clamp(z, -1, 1);
            double a = x, b = z;
            for (int i = 0; i < 24; i++) {
                double s = a * a,
                        t = b * b,
                        ka = BASE + OWN * s + OTHER * t,
                        kb = BASE + OWN * t + OTHER * s;
                double ex = component(a, t) - x, ez = component(b, s) - z;
                if (Math.max(Math.abs(ex), Math.abs(ez)) < 1e-13) return new Pair(a, b);
                double p = 1 - s, q = 1 - t, den = p + q;
                // The exact corner has already returned above; never evaluate its undefined
                // derivative.
                if (den <= 0) throw new IllegalStateException("Invalid corner inverse iterate");
                double harmonic = p * q / den, spread = SPREAD + (1 - SPREAD) * s * t;
                double hs = -q * q / (den * den), ht = -p * p / (den * den);
                double cs = SEAM * (hs * spread + harmonic * (1 - SPREAD) * t);
                double ct = SEAM * (ht * spread + harmonic * (1 - SPREAD) * s);
                double correction = SEAM * harmonic * spread;
                double aa = 1 + t * ((1 - 3 * s) * ka + 2 * OWN * s * p) - correction - 2 * s * cs;
                double ab = 2 * a * b * (p * (ka + OTHER * t) - ct);
                double bb = 1 + s * ((1 - 3 * t) * kb + 2 * OWN * t * q) - correction - 2 * t * ct;
                double ba = 2 * a * b * (q * (kb + OTHER * s) - cs);
                double det = aa * bb - ab * ba;
                if (det <= 1e-8) throw new IllegalStateException("Folded balanced chart");
                a = Math.clamp(a - (bb * ex - ab * ez) / det, -1, 1);
                b = Math.clamp(b - (aa * ez - ba * ex) / det, -1, 1);
            }
            throw new IllegalStateException("Balanced chart inverse did not converge");
        }
    }
}
