package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;

import org.joml.Quaterniond;

/** Fixed luminous celestial data, independent of mesh, terrain, gravity and gameplay. */
public final class GroundSolar {
    public record Sun(boolean enabled, Point position, double radius) {
        public static final Sun OFF = new Sun(false, GroundEphemeris.ZERO, 512);

        public Sun {
            GroundEphemeris.point(position);
            if (position.length() > 100000
                    || !Double.isFinite(radius)
                    || radius < 32
                    || radius > 1024) throw new IllegalArgumentException("Sun parameters");
        }
    }

    public static GroundEphemeris.Model defaults() {
        var old = GroundEphemeris.defaults();
        var axis = new Point(Math.sin(Math.toRadians(23.5)), Math.cos(Math.toRadians(23.5)), 0);
        var spin = new GroundEphemeris.Spin(true, axis, new Quaterniond(), 0, 48000, 0);
        return new GroundEphemeris.Model(
                old.center(),
                old.axis(),
                old.radial(),
                old.radius(),
                old.period(),
                old.phase(),
                old.epoch(),
                spin,
                old.b(),
                new Sun(true, old.center(), 512));
    }

    public static Point direction(GroundEphemeris.Model model, Point world) {
        var d = model.sun().position().sub(world);
        return d.length() < 1e-9 ? GroundEphemeris.Y : d.scale(1 / d.length());
    }

    public static double elevation(
            GroundEphemeris.Model model, int planet, Point world, double time) {
        var up = PlanetSpace.unit(world.sub(model.at(planet, time).center()));
        return up.dot(direction(model, world));
    }

    public static double daylight(double elevation) {
        return GroundGap.smooth(-.12, .12, elevation);
    }

    /** Planet self-occlusion of a finite segment; no block/building shadow mapping. */
    public static boolean occulted(Point eye, Point target, Point center, double radius) {
        var d = target.sub(eye);
        double distance = d.length();
        if (distance < 1e-9) return false;
        d = d.scale(1 / distance);
        var o = eye.sub(center);
        double b = o.dot(d), c = o.dot(o) - radius * radius, disc = b * b - c;
        if (disc < 0) return false;
        double near = -b - Math.sqrt(disc), far = -b + Math.sqrt(disc);
        return far > 1e-4 && near < distance - 1e-4 && Math.max(near, 0) < distance - 1e-4;
    }

    public static double light(Point source, Point normal, Point sun) {
        var toSun = PlanetSpace.unit(sun.sub(source));
        double elevation = PlanetSpace.unit(source).dot(toSun);
        double diffuse = Math.max(0, PlanetSpace.unit(normal).dot(toSun));
        return .035 + .10 * daylight(elevation) + .865 * daylight(elevation) * diffuse;
    }

    public static Point sky(double elevation, double space) {
        double day = daylight(elevation),
                dusk = (1 - GroundGap.smooth(0, .25, Math.abs(elevation))) * day;
        var night = new Point(.012, .018, .045);
        var blue = new Point(.38, .63, .93);
        var color = night.scale(1 - day).add(blue.scale(day));
        color = color.scale(1 - .35 * dusk).add(new Point(.72, .28, .12).scale(.35 * dusk));
        return color.scale(1 - Math.clamp(space, 0, 1));
    }

    public static double stars(double elevation, double space) {
        return space + (1 - space) * (1 - daylight(elevation));
    }

    private GroundSolar() {}
}
