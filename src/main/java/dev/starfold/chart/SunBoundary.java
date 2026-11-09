package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

/** Swept, frictionless sun walls. All distances and displacements use universe blocks. */
public final class SunBoundary {
    public static final double MARGIN = 32;

    /** Clip a whole segment, not just its endpoint: even a step through the far side hits the wall.
     * At contact retain the remaining tangent in the supporting plane. Already-inside objects are
     * never ejected; only their inward component is removed. */
    public static Point clip(Point position, Point motion, Point center, double radius) {
        var offset = position.sub(center);
        double distance = offset.length();
        if (distance <= radius + 1e-8) {
            if (distance < 1e-12) return motion;
            var normal = offset.scale(1 / distance);
            double inward = motion.dot(normal);
            return inward < 0 ? motion.sub(normal.scale(inward)) : motion;
        }
        double a = motion.dot(motion), b = offset.dot(motion);
        if (a == 0 || b >= 0) return motion;
        double c = (distance - radius) * (distance + radius), discriminant = b * b - a * c;
        if (discriminant <= 0) return motion;
        // Stable near root, including a start very close to the surface.
        double contact = c / (-b + Math.sqrt(discriminant));
        if (contact >= 1) return motion;
        var normal = offset.add(motion.scale(contact)).scale(1 / radius);
        return motion.sub(normal.scale(Math.min(0, motion.dot(normal)) * (1 - contact)));
    }

    public static Point clip(
            GroundEphemeris.Model model,
            Point position,
            Point motion,
            double tick,
            double padding) {
        var result = motion;
        for (var star : model.everyStar(tick))
            result = clip(position, result, star.position(), star.radius() + MARGIN + padding);
        return result;
    }

    /** Include the frame's next-tick translation; a stationary Level point can still fly into a sun. */
    public static Point inFrame(
            GroundEphemeris.Model model,
            Space.Frame frame,
            Point position,
            Point motion,
            double tick,
            double padding) {
        return inFrame(model, frame, position, motion, tick, padding, 1);
    }

    /** The same sweep for a physics substep; span is measured in game ticks. */
    public static Point inFrame(
            GroundEphemeris.Model model,
            Space.Frame frame,
            Point position,
            Point motion,
            double tick,
            double padding,
            double span) {
        var world = frame.universe(position, model, tick);
        var carried = frame.universe(position, model, tick + span).sub(world);
        var proposed = frame.universeVector(motion).add(carried);
        var limited = clip(model, world, proposed, tick, padding);
        return limited.equals(proposed) ? motion : frame.levelVector(limited.sub(carried));
    }

    private SunBoundary() {}
}
