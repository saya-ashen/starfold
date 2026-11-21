package dev.starfold.render;

import dev.starfold.geometry.BentStripGeometry.Point;

/** One eye/occluder pair, reused only within a body's synchronous surface draw. */
final class RemoteOcclusion {
    private final Point eye;
    private final double ax, ay, az, distance, size;
    private boolean angleReady;
    private double angle;

    RemoteOcclusion(Point eye, Point occluder, double size) {
        this.eye = eye;
        this.size = size;
        ax = occluder.x() - eye.x();
        ay = occluder.y() - eye.y();
        az = occluder.z() - eye.z();
        distance = Math.sqrt(ax * ax + ay * ay + az * az);
    }

    boolean hidden(double x, double y, double z, double radius) {
        if (size <= 0 || distance <= size) return false;
        double vx = x - eye.x(), vy = y - eye.y(), vz = z - eye.z();
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length <= radius) return false;
        double along = (vx * ax + vy * ay + vz * az) / distance;
        if (along - radius < distance) return false;
        // Preserve the original divisions and angular comparison at tangent/plane boundaries.
        // Bodies whose candidates all exit above never need this inverse trigonometric call.
        if (!angleReady) {
            angle = Math.asin(size / distance);
            angleReady = true;
        }
        return Math.acos(Math.min(1, along / length)) + Math.asin(radius / length) <= angle;
    }
}
