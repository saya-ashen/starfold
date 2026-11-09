package dev.starfold.render;

import org.joml.FrustumIntersection;
import org.joml.Vector4f;

/** Vanilla's camera-cube back-off with a bound: a degenerate frustum froze the client in deep space (2026-11-02). */
public final class FrustumOffset {
    public static final int LIMIT = 256;

    /** The backed-off camera, or null when the cube never fits within {@link #LIMIT} steps. */
    public static double[] offset(FrustumIntersection intersection, Vector4f view, double camX, double camY, double camZ, int step) {
        double x0 = Math.floor(camX / step) * step, y0 = Math.floor(camY / step) * step, z0 = Math.floor(camZ / step) * step;
        double x1 = Math.ceil(camX / step) * step, y1 = Math.ceil(camY / step) * step, z1 = Math.ceil(camZ / step) * step;
        double x = camX, y = camY, z = camZ;
        for (int i = 0; i < LIMIT; i++) {
            if (intersection.intersectAab((float) (x0 - x), (float) (y0 - y), (float) (z0 - z),
                    (float) (x1 - x), (float) (y1 - y), (float) (z1 - z)) == FrustumIntersection.INSIDE)
                return new double[] {x, y, z};
            x -= view.x() * 4f; y -= view.y() * 4f; z -= view.z() * 4f;
        }
        return null;
    }

    private FrustumOffset() {}
}
