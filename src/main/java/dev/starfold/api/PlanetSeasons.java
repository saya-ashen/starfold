package dev.starfold.api;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

/** Pure, replaceable first climate slice. Planet is a surface slot; up is a unit body-local
 * direction and altitude is blocks above the nominal sphere (not the storage Y coordinate).
 * Phase: spring equinox=0, summer=.25, autumn=.5, winter=.75; southern phase is offset by .5. */
public final class PlanetSeasons {
    public record Sample(double phase, double temperature, double snow) {}
    /** Body-local spin axis and annual forcing, also usable by position-driven renderers. */
    public record Forcing(Point axis, double phase, double seasonal, double density, double tilt, double temperatureOffset) {}

    public static Forcing forcing(GroundEphemeris.Model model, int planet, double tick) {
        var body = model.body(planet);
        if (!(body.motion() instanceof GroundEphemeris.Orbit orbit))
            return new Forcing(GroundEphemeris.Y, 0, 0, body.atmosphere().density(), 0, body.temperatureOffset());
        var year = year(model, orbit);
        var axis = orbit.spin().axis();
        var worldAxis = GroundEphemeris.rotate(orbit.spin().reference(), axis);
        double tilt = worldAxis.cross(year.axis()).length();
        double step = year.period() / 4096;
        double declination = declination(model, planet, worldAxis, tick);
        double otherTick = tick + step <= GroundEphemeris.MAX_TICK ? tick + step : tick - step;
        double derivative = (declination(model, planet, worldAxis, otherTick) - declination)
                / ((otherTick - tick) * 2 * Math.PI / year.period());
        double angle = Math.atan2(declination, derivative);
        double phase = (angle / (2 * Math.PI) + 1) % 1;
        return new Forcing(axis, phase, 40 * tilt * Math.sin(angle - Math.PI / 4), body.atmosphere().density(), tilt, body.temperatureOffset());
    }

    // A moon's year follows its parent's orbit about the star, not its monthly orbit.
    private static GroundEphemeris.Orbit year(GroundEphemeris.Model model, GroundEphemeris.Orbit orbit) {
        var year = orbit;
        while (!model.catalogBody(year.parent()).light()
                && model.catalogBody(year.parent()).motion() instanceof GroundEphemeris.Orbit parent)
            year = parent;
        return year;
    }

    /** What a clock and a thermometer read at a world-space eye: local solar hour 0-24, season of the eye's
     *  hemisphere (0 spring .. 3 winter), day of the year from 1 and local days in a year. */
    public record Reading(double hour, int season, int day, int days, double temperature, double snow) {}

    public static Reading reading(GroundEphemeris.Model model, int planet, Point eye, double tick, dev.starfold.worldgen.plan.terrain.TerrainPlan terrain) {
        var forcing = forcing(model, planet, tick);
        var frame = model.at(planet, tick);
        var offset = frame.local(eye);
        double altitude = offset.length() - model.body(planet).radius();
        var up = offset.scale(1 / offset.length());
        var sample = sample(forcing, up, altitude,terrain);
        double step = 100, angle = hourAngle(model, planet, forcing.axis(), up, tick);
        double rate = Math.IEEEremainder(hourAngle(model, planet, forcing.axis(), up, tick + step) - angle, 2 * Math.PI) / step;
        double hour = ((12 + Math.signum(rate) * angle * 12 / Math.PI) % 24 + 24) % 24;
        int days = model.body(planet).motion() instanceof GroundEphemeris.Orbit orbit && rate != 0
                ? Math.max(1, (int) Math.round(year(model, orbit).period() * Math.abs(rate) / (2 * Math.PI))) : 1;
        return new Reading(hour, (int) (sample.phase() * 4) % 4, (int) (sample.phase() * days) % days + 1, days,
                sample.temperature(), sample.snow());
    }

    /** Body-local angle about the spin axis from a place's meridian to its sun: 0 at local noon. */
    private static double hourAngle(GroundEphemeris.Model model, int planet, Point axis, Point up, double tick) {
        var frame = model.at(planet, tick);
        var world = frame.world(up.scale(model.body(planet).radius()));
        var sun = frame.inverseVector(model.star(world, tick).position().sub(world));
        var u = up.sub(axis.scale(up.dot(axis)));
        var s = sun.sub(axis.scale(sun.dot(axis)));
        return Math.atan2(axis.dot(u.cross(s)), u.dot(s));
    }

    private static double declination(GroundEphemeris.Model model, int planet, Point axis, double tick) {
        var center = model.at(planet, tick).center();
        var sun = model.star(center, tick).position().sub(center);
        return axis.dot(sun.scale(1 / sun.length()));
    }

    public static Sample sample(GroundEphemeris.Model model, int planet, Point up, double altitude, double tick, dev.starfold.worldgen.plan.terrain.TerrainPlan terrain) {
        return sample(forcing(model, planet, tick), up, altitude,terrain);
    }

    public static Sample sample(Forcing forcing, Point up, double altitude,dev.starfold.worldgen.plan.terrain.TerrainPlan terrain) {
        double latitude = Math.clamp(up.dot(forcing.axis()), -1, 1);
        double phase = (forcing.phase() + (latitude < 0 ? .5 : 0)) % 1;
        double temperature=terrain.temperatureField().temperature(up,altitude)+forcing.seasonal()*latitude;
        double edge = noise(up);
        double snow = forcing.density() == 0 ? 0 : Math.clamp((2 - temperature + edge * 3) / 6, 0, 1);
        return new Sample(phase, temperature, snow);
    }

    /** Low-frequency body-local noise: continuous across cube faces and identical at all LODs. */
    public static double noise(Point up) {
        return Math.sin(up.x() * 137 + up.z() * 91) * Math.sin(up.y() * 113 - up.z() * 57);
    }
    private PlanetSeasons() {}
}
