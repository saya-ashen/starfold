package dev.starfold.sky;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.worldgen.PlanetAtmosphere;

/** CPU single scattering in a spherical exponential atmosphere, also used to colour the sky mesh.
 * No colour tables, time-of-day ramps or GPU twin: fog, sky and orbital limb call this integrator. */
public final class AtmosphereScattering {
    private static final Point BLACK = new Point(0, 0, 0);

    public static Point tint(PlanetAtmosphere air) {
        return new Point(
                ((air.color() >> 16) & 255) / 255.0,
                ((air.color() >> 8) & 255) / 255.0,
                (air.color() & 255) / 255.0);
    }

    /** Eye is relative to the planet centre; direction and sun point outwards in the same axes.
     * Returns exposure-mapped linear RGB. Keep this linear for luminance/star visibility; call
     * display only at the sky vertex or fog colour output boundary. */
    public static Point sky(
            PlanetAtmosphere air, double radius, Point eye, Point direction, Point sun) {
        return sky(air, radius, eye, direction, sun, 1);
    }

    /** A bounded external shadow attenuates radiance BEFORE exposure/display encoding. */
    public static Point sky(
            PlanetAtmosphere air,
            double radius,
            Point eye,
            Point direction,
            Point sun,
            double solarTransmission) {
        if (!Double.isFinite(solarTransmission) || solarTransmission < 0 || solarTransmission > 1)
            throw new IllegalArgumentException("Atmosphere solar transmission");
        if (air.density() == 0) return BLACK;
        var ray = PlanetSpace.unit(direction);
        sun = PlanetSpace.unit(sun);
        double outer = radius + AtmosphereSettings.SCALE_HEIGHT * AtmosphereSettings.SHELL_HEIGHTS;
        double[] segment = interval(eye, ray, outer);
        if (segment == null) return BLACK;
        double start = Math.max(0, segment[0]), end = segment[1];
        double[] ground = interval(eye, ray, radius);
        if (ground != null && ground[1] > 1e-5) {
            if (ground[0] > 1e-5) end = Math.min(end, ground[0]);
            else if (eye.length() < radius - 1e-5) return BLACK;
        }
        if (end <= start) return BLACK;
        double ds = (end - start) / AtmosphereSettings.VIEW_SAMPLES;
        var tint = tint(air);
        var beta =
                new Point(
                                Math.pow(tint.x(), AtmosphereSettings.TINT_POWER),
                                Math.pow(tint.y(), AtmosphereSettings.TINT_POWER),
                                Math.pow(tint.z(), AtmosphereSettings.TINT_POWER))
                        .scale(AtmosphereSettings.RAYLEIGH);
        double mu = Math.clamp(ray.dot(sun), -1, 1);
        double phaseR = 3 * (1 + mu * mu) / (16 * Math.PI);
        double g = AtmosphereSettings.MIE_G;
        double phaseM = (1 - g * g) / (4 * Math.PI * Math.pow(1 + g * g - 2 * g * mu, 1.5));
        double viewDepth = 0;
        Point sum = BLACK;
        for (int i = 0; i < AtmosphereSettings.VIEW_SAMPLES; i++) {
            var p = eye.add(ray.scale(start + (i + .5) * ds));
            double local = density(air, radius, p) * ds / AtmosphereSettings.SCALE_HEIGHT;
            double[] shadow = interval(p, sun, radius);
            if (shadow == null || shadow[1] <= 1e-5) {
                var exit = interval(p, sun, outer);
                double step =
                        exit == null ? 0 : Math.max(0, exit[1]) / AtmosphereSettings.SUN_SAMPLES;
                double sunDepth = 0;
                for (int j = 0; j < AtmosphereSettings.SUN_SAMPLES; j++)
                    sunDepth +=
                            density(air, radius, p.add(sun.scale((j + .5) * step)))
                                    * step
                                    / AtmosphereSettings.SCALE_HEIGHT;
                double depth = viewDepth + local * .5 + sunDepth;
                sum =
                        sum.add(
                                new Point(
                                                channel(beta.x(), depth, phaseR, phaseM),
                                                channel(beta.y(), depth, phaseR, phaseM),
                                                channel(beta.z(), depth, phaseR, phaseM))
                                        .scale(local));
            }
            viewDepth += local;
        }
        return new Point(
                mapped(sum.x() * solarTransmission),
                mapped(sum.y() * solarTransmission),
                mapped(sum.z() * solarTransmission));
    }

    private static double channel(double beta, double depth, double r, double m) {
        return Math.exp(-(beta + AtmosphereSettings.MIE) * depth)
                * (beta * r + AtmosphereSettings.MIE * m);
    }

    private static double mapped(double x) {
        return 1 - Math.exp(-AtmosphereSettings.EXPOSURE * x);
    }

    /** Vanilla's RGBA8 targets and our colour shader do not encode linear light for display.
     * Encode once, after exposure (and rim radiance scaling), never for grading or uniforms
     * that represent density, direction or visibility. */
    public static Point display(Point linear) {
        return new Point(display(linear.x()), display(linear.y()), display(linear.z()));
    }

    private static double display(double linear) {
        double x = Math.clamp(linear, 0, 1);
        return x <= .0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - .055;
    }

    private static double density(PlanetAtmosphere air, double radius, Point p) {
        return air.density()
                * Math.exp(-Math.max(0, p.length() - radius) / AtmosphereSettings.SCALE_HEIGHT);
    }

    private static double[] interval(Point p, Point d, double radius) {
        double b = p.dot(d), disc = b * b - p.dot(p) + radius * radius;
        if (disc < 0) return null;
        double root = Math.sqrt(disc);
        return new double[] {-b - root, -b + root};
    }

    /** Azimuth of the view projected onto the physical tangent plane. */
    public static Point horizon(Point up, Point view) {
        up = PlanetSpace.unit(up);
        var tangent = view.sub(up.scale(view.dot(up)));
        if (tangent.length() < 1e-6)
            tangent = up.cross(Math.abs(up.y()) < .9 ? new Point(0, 1, 0) : new Point(1, 0, 0));
        return PlanetSpace.unit(tangent);
    }

    public static Point fog(PlanetAtmosphere air, double radius, Point eye, Point view, Point sun) {
        return fog(air, radius, eye, view, sun, 1);
    }

    public static Point fog(
            PlanetAtmosphere air,
            double radius,
            Point eye,
            Point view,
            Point sun,
            double solarTransmission) {
        return sky(air, radius, eye, horizon(eye, view), sun, solarTransmission);
    }

    public static double stars(Point sky) {
        return Math.exp(
                -AtmosphereSettings.STAR_EXTINCTION
                        * (.2126 * sky.x() + .7152 * sky.y() + .0722 * sky.z()));
    }

    public static double fogStrength(PlanetAtmosphere air, double altitude) {
        return Math.clamp(
                air.density() * Math.exp(-Math.max(0, altitude) / AtmosphereSettings.SCALE_HEIGHT),
                0,
                1);
    }

    public static Point grade(PlanetAtmosphere air, double altitude) {
        var t = tint(air);
        double mean = (t.x() + t.y() + t.z()) / 3;
        double strength = AtmosphereSettings.GRADING_STRENGTH * fogStrength(air, altitude);
        return new Point(
                1 + (t.x() - mean) * strength,
                1 + (t.y() - mean) * strength,
                1 + (t.z() - mean) * strength);
    }

    private AtmosphereScattering() {}
}
