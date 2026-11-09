package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;

import org.joml.Vector3d;

/** Current-pose gas envelope math only. Does not enumerate hulls or prove swept collision safety. */
final class GasHullGeometry {
    /** Inclusive block bounds; child is null for the parent plot, otherwise child -> plot -> level. */
    static double radius(Pose3dc hull, BoundingBox3ic bounds, Pose3dc child) {
        valid(hull);
        if (child != null) valid(child);
        if (bounds == null) throw new IllegalArgumentException("Unknown gas hull bounds");
        if (bounds.maxX() < bounds.minX()
                || bounds.maxY() < bounds.minY()
                || bounds.maxZ() < bounds.minZ()) return 0;
        double radius = 0;
        var anchor = hull.position();
        for (int corner = 0; corner < 8; corner++) {
            var p =
                    new Vector3d(
                            (corner & 1) == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                            (corner & 2) == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                            (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ() + 1.0);
            if (child != null) child.transformPosition(p, p);
            hull.transformPosition(p, p);
            double distance =
                    Math.hypot(Math.hypot(p.x - anchor.x(), p.y - anchor.y()), p.z - anchor.z());
            if (!Double.isFinite(distance))
                throw new IllegalArgumentException("Nonfinite gas hull extent");
            radius = Math.max(radius, distance);
        }
        return radius;
    }

    /** Encloses a transformed unit block. Rotation does not change this scale-aware circumsphere. */
    static double blockPadding(Pose3dc hull) {
        valid(hull);
        var s = hull.scale();
        return .5 * Math.hypot(Math.hypot(s.x(), s.y()), s.z());
    }

    private static void valid(Pose3dc pose) {
        if (pose == null
                || !pose.position().isFinite()
                || !pose.rotationPoint().isFinite()
                || !pose.scale().isFinite()
                || pose.scale().x() == 0
                || pose.scale().y() == 0
                || pose.scale().z() == 0
                || !Double.isFinite(pose.orientation().lengthSquared())
                || Math.abs(pose.orientation().lengthSquared() - 1) > 1e-6)
            throw new IllegalArgumentException("Unknown gas hull pose");
    }

    private GasHullGeometry() {}
}
