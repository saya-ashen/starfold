package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

/** Read-only region navigation. Velocities enter in blocks/tick and leave in blocks/second. */
public final class SpaceNavigation {
    public record Marker(double x, double y, boolean edge, double angle) {}

    public record Reading(double distance, double closing) {}

    public static final double LOCK_CONE = 20;

    public record Candidate(int planet, Marker marker, double angle) {}

    /** Pixel distance to the aim ring, filtered by nose angle; list order breaks exact ties. */
    public static int choose(java.util.List<Candidate> candidates, int width, int height) {
        int planet = -1;
        double best = Double.POSITIVE_INFINITY;
        for (var c : candidates) {
            if (!Double.isFinite(c.angle()) || c.angle() < 0 || c.angle() > LOCK_CONE) continue;
            double distance =
                    Math.hypot((c.marker().x() - .5) * width, (c.marker().y() - .5) * height);
            if (distance < best) {
                best = distance;
                planet = c.planet();
            }
        }
        return planet;
    }

    /** Distance order (id breaks ties), with the current reference body moved to the end, not removed. */
    public static java.util.List<Integer> cycle(
            GroundEphemeris.Model model, Point ship, double tick, int reference) {
        var ids = new java.util.ArrayList<>(SpaceView.nearest(model, ship, tick, model.count()));
        if (ids.remove(Integer.valueOf(reference))) ids.add(reference);
        return java.util.List.copyOf(ids);
    }

    public static int next(
            GroundEphemeris.Model model, Point ship, double tick, int locked, int reference) {
        var ids = cycle(model, ship, tick, reference);
        int next = ids.indexOf(locked) + 1;
        return next < ids.size() ? ids.get(next) : -1;
    }

    public static int next(GroundEphemeris.Model model, Point ship, double tick, int locked) {
        return next(model, ship, tick, locked, -1);
    }

    /** Centre must be ahead of the ship and nearer than the target; test the finite sightline. */
    public static boolean occludes(Point ship, Point target, Point centre, double radius) {
        var ray = target.sub(ship);
        var offset = centre.sub(ship);
        double length2 = ray.dot(ray);
        if (length2 <= 1e-12 || radius < 0 || offset.dot(offset) >= length2) return false;
        double t = offset.dot(ray) / length2;
        return t > 0 && t < 1 && offset.sub(ray.scale(t)).length() <= radius;
    }

    static int blocker(GroundEphemeris.Model model, Point ship, int target, double tick) {
        if (target < 0 || target >= model.count()) return -1;
        var end = model.at(target, tick).center();
        int blocker = -1;
        double nearest = Double.POSITIVE_INFINITY;
        for (int id = 0; id < model.count(); id++)
            if (id != target) {
                var centre = model.at(id, tick).center();
                double distance = centre.sub(ship).length();
                if (distance < nearest
                        && occludes(ship, end, centre, PlanetShape.of(id).radius() + Space.IN)) {
                    blocker = id;
                    nearest = distance;
                }
            }
        return blocker;
    }

    public static String arrival(Reading reading) {
        if (!Double.isFinite(reading.distance())
                || !Double.isFinite(reading.closing())
                || reading.closing() <= 0) return "—";
        long seconds = (long) Math.ceil(Math.max(0, reading.distance()) / reading.closing());
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Universe velocity relative to the chosen centre, still in blocks/tick. No rotating-frame subtraction. */
    public static Point velocity(
            GroundEphemeris.Model model, Space.Frame frame, int locked, double tick) {
        int planet = locked >= 0 && locked < model.count() ? locked : frame.planet();
        return frame.universeVelocity(GroundEphemeris.ZERO, model, tick)
                .sub(model.at(planet, tick).velocity());
    }

    public record Velocity(Point camera, boolean retrograde) {}

    public static Velocity velocityMarker(Point camera) {
        return camera.length() * 20 < 1
                ? null
                : new Velocity(camera.z() > 0 ? camera.scale(-1) : camera, camera.z() > 0);
    }

    /** Camera axes: right +X, up +Y, forward -Z. Coordinates are fractions of the viewport. */
    public static Marker project(
            Point camera, double fov, double aspect, double insetX, double insetY) {
        double tangent = Math.tan(fov * .5),
                x = camera.x() / (tangent * aspect),
                y = -camera.y() / tangent;
        double depth = -camera.z(), bx = .5 - insetX, by = .5 - insetY;
        if (depth > 0) {
            double sx = x / (2 * depth), sy = y / (2 * depth);
            if (Math.abs(sx) <= bx && Math.abs(sy) <= by)
                return new Marker(.5 + sx, .5 + sy, false, 0);
        }
        // Never divide by negative depth: a target behind-right requires a right turn, not a left
        // turn.
        // Exactly astern has no unique shortest turn; choose right deterministically.
        if (Math.hypot(x, y) < 1e-12) {
            x = 1;
            y = 0;
        }
        double scale = Math.min(bx / Math.abs(x), by / Math.abs(y));
        return new Marker(.5 + x * scale, .5 + y * scale, true, Math.atan2(y, x * aspect));
    }

    public static double closing(Point toward, Point shipVelocity, Point planetVelocity) {
        double length = toward.length();
        return length > 1e-12
                ? shipVelocity.sub(planetVelocity).dot(toward.scale(1 / length)) * 20
                : 0;
    }

    /** Nominal radial surface, the same surface used by region hand-over, not terrain clearance. */
    public static Reading read(
            GroundEphemeris.Model model, Space.Frame frame, Point eye, int planet, double tick) {
        var target = model.at(planet, tick);
        var toward = target.center().sub(eye);
        return new Reading(
                Space.height(model, planet, eye, tick),
                closing(
                        toward,
                        frame.universeVelocity(GroundEphemeris.ZERO, model, tick),
                        target.velocity()));
    }

    public static double speed(Space.Frame frame) {
        return frame.drift().length() * 20;
    }

    /** Degrees between Level -Y and the reference centre; zero at the undefined centre itself. */
    public static double angle(Point levelToward) {
        return levelToward.length() > 1e-12
                ? Math.toDegrees(
                        Math.atan2(Math.hypot(levelToward.x(), levelToward.z()), -levelToward.y()))
                : 0;
    }

    private SpaceNavigation() {}
}
