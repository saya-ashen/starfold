package dev.starfold.universe;

import static dev.starfold.universe.SystemDefinition.*;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.chart.SunSettings;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;
import java.util.*;

/** Whole-system orbital constraints, independent of materialization. */
final class SystemValidation {
    /** Seedless validation uses the largest possible size, so choosing another world seed cannot invalidate a pack. */
    static void validate(SystemDefinition definition) {
        var suns = definition.suns();
        var home = definition.home();
        var beyond = definition.beyond();
        if (beyond.isPresent()) {
            var layout = beyond.get().value();
            var path = suns.get(home.sun()).planets().get(home.planet()).orbit();
            double outer = path.radius() + (layout.maxPlanets() - 1) * layout.ring();
            double first =
                    SunSettings.MAX_GENERATED_RADIUS
                            + new PlanetShape(PlanetShape.MAX).radius()
                            + GroundEphemeris.Body.DEFAULT_REACH
                            + MARGIN / 2;
            if (path.radius() <= first
                    || layout.gap()
                            < UniverseLayout.minimumGap(
                                    path.radius(), layout.maxPlanets(), layout.ring()))
                throw new IllegalArgumentException(
                        "beyond rings need home orbit radius above "
                                + first
                                + " and star_gap at least "
                                + UniverseLayout.minimumGap(
                                        path.radius(), layout.maxPlanets(), layout.ring()));
            if (path.period(suns.get(home.sun()).gm()) * Math.pow(outer / path.radius(), 1.5) > 1e9)
                throw new IllegalArgumentException(
                        "beyond outer orbit period exceeds 1000000000 ticks; shorten the home period or reduce beyond rings");
        }
        double[] reaches = new double[suns.size()];
        for (int s = 0; s < suns.size(); s++) {
            var sun = suns.get(s);
            double[] radii = new double[sun.planets().size()];
            double[] families = new double[radii.length];
            for (int p = 0; p < radii.length; p++) {
                var planet = sun.planets().get(p);
                validatePeriod(planet.orbit(), sun.gm(), "sun " + s + " planet " + p);
                try {
                    var type = planet.type().value();
                    int size = planet.size().orElse(type.maxSize());
                    type.generation(size);
                    radii[p] = new PlanetShape(size).radius();
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            "sun "
                                    + s
                                    + " planet "
                                    + p
                                    + " type "
                                    + planet.type()
                                            .unwrapKey()
                                            .map(k -> k.location().toString())
                                            .orElse("inline")
                                    + ": "
                                    + e.getMessage(),
                            e);
                }
                families[p] =
                        family(
                                planet,
                                radii[p],
                                planetGm(planet),
                                "sun " + s + " planet " + p);
                double reach = families[p] + MARGIN / 2;
                if (planet.orbit().radius() <= sun.radius() + reach)
                    throw new IllegalArgumentException(
                            "sun "
                                    + s
                                    + " planet "
                                    + p
                                    + " orbit is too close to its sun; radius must exceed "
                                    + (sun.radius() + reach));
                reaches[s] = Math.max(reaches[s], planet.orbit().radius() + reach);
                for (int q = 0; q < p; q++) {
                    var other = sun.planets().get(q);
                    double distance = distance(planet.orbit(), other.orbit(), sun.gm());
                    double required =
                            planet.moons().isEmpty() && other.moons().isEmpty()
                                    ? radii[p] + radii[q] + (planet.reach() + other.reach()) + MARGIN
                                    : families[p] + families[q] + MARGIN;
                    if (!Double.isFinite(distance) || distance < required)
                        throw new IllegalArgumentException(
                                "sun "
                                        + s
                                        + " planets "
                                        + q
                                        + " and "
                                        + p
                                        + " orbits too close: minimum "
                                        + distance
                                        + ", need "
                                        + required
                                        + "; separate rings, or use equal periods and separated phases");
                }
            }
            for (int g = 0; g < sun.gasGiants().size(); g++) {
                var gas = sun.gasGiants().get(g);
                validatePeriod(gas.orbit(), sun.gm(), "sun " + s + " gas giant " + g);
                double gasFamily = family(gas, "sun " + s + " gas giant " + g);
                double shell = gas.shell();
                if (gas.orbit().radius() <= sun.radius() + shell + MARGIN / 2)
                    throw new IllegalArgumentException("Gas giant orbit too close to sun " + s);
                for (int p = 0; p < sun.planets().size(); p++) {
                    double separation = distance(gas.orbit(), sun.planets().get(p).orbit(), sun.gm());
                    if (separation < gasFamily + families[p] + MARGIN)
                        throw new IllegalArgumentException(
                                "Gas giant/planet orbital shells overlap");
                }
                for (int before = 0; before < g; before++) {
                    var other = sun.gasGiants().get(before);
                    if (distance(gas.orbit(), other.orbit(), sun.gm())
                            < gasFamily
                                    + family(other, "sun " + s + " gas giant " + before)
                                    + MARGIN)
                        throw new IllegalArgumentException("Gas giant orbital shells overlap");
                }
                reaches[s] = Math.max(reaches[s], gas.orbit().radius() + gasFamily + MARGIN / 2);
            }
            // Own-flight saves reject absolute positions beyond this bound; do not offer an
            // unreachable authored planet.
            if (sun.position().length() + reaches[s] > 1e9)
                throw new IllegalArgumentException(
                        "sun "
                                + s
                                + " orbit envelope must stay within 1000000000 blocks of origin");
            for (int t = 0; t < s; t++)
                if (sun.position().sub(suns.get(t).position()).length() < reaches[s] + reaches[t])
                    throw new IllegalArgumentException(
                            "suns "
                                    + t
                                    + " and "
                                    + s
                                    + " outermost orbits overlap; separate positions by at least "
                                    + (reaches[s] + reaches[t]));
        }
    }

    private static void validatePeriod(Path path, double gm, String label) {
        double period = path.period(gm);
        if (!Double.isFinite(period) || period <= 0 || period > 1e9)
            throw new IllegalArgumentException(
                    label + " Kepler period must be in (0, 1000000000] ticks");
    }

    private static double planetGm(Planet planet) {
        var type = planet.type().value();
        int size = planet.size().orElse(type.maxSize());
        var generation = type.generation(size);
        double radius = new PlanetShape(size).radius();
        return 32 * generation.environment().gravity() * radius * radius;
    }

    private static double radius(Planet planet) {
        var type = planet.type().value();
        int size = planet.size().orElse(type.maxSize());
        type.generation(size);
        return new PlanetShape(size).radius();
    }

    /** The parent's shell and every moon's entire path must fit inside the sun's spacing envelope. */
    private static double family(Planet planet, double radius, double gm, String label) {
        double extent = radius + planet.reach();
        var shells = new double[planet.moons().size()];
        for (int m = 0; m < shells.length; m++) {
            var moon = planet.moons().get(m);
            validatePeriod(moon.orbit(), gm, label + " moon " + m);
            try {
                shells[m] = radius(moon) + moon.reach();
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        label + " moon " + m + ": " + error.getMessage(), error);
            }
            double required = radius + planet.reach() + shells[m] + MARGIN;
            if (moon.orbit().radius() < required)
                throw new IllegalArgumentException(
                        label
                                + " moon "
                                + m
                                + " orbit too close to its planet; radius must be at least "
                                + required);
            extent = Math.max(extent, moon.orbit().radius() + shells[m]);
            for (int n = 0; n < m; n++) {
                double distance = distance(moon.orbit(), planet.moons().get(n).orbit(), gm);
                double need = shells[m] + shells[n] + MARGIN;
                if (!Double.isFinite(distance) || distance < need)
                    throw new IllegalArgumentException(
                            label
                                    + " moons "
                                    + n
                                    + " and "
                                    + m
                                    + " orbits too close: minimum "
                                    + distance
                                    + ", need "
                                    + need
                                    + "; separate rings, or use equal periods and separated phases");
            }
        }
        return extent;
    }

    private static double family(GasBody parent, String label) {
        double shellRadius = parent.radius() + parent.gas().outerHeight();
        double extent = shellRadius;
        for (int m = 0; m < parent.moons().size(); m++) {
            var moon = parent.moons().get(m);
            validatePeriod(moon.orbit(), parent.gm(), label + " moon " + m);
            double shell = radius(moon) + moon.reach();
            if (moon.orbit().radius() < shellRadius + shell + MARGIN)
                throw new IllegalArgumentException(
                        label + " moon " + m + " orbit too close to gas body");
            extent = Math.max(extent, moon.orbit().radius() + shell);
            for (int n = 0; n < m; n++) {
                var other = parent.moons().get(n);
                if (distance(moon.orbit(), other.orbit(), parent.gm())
                        < shell + radius(other) + other.reach() + MARGIN)
                    throw new IllegalArgumentException(label + " moon orbits overlap");
            }
        }
        return extent;
    }

    private static double distance(Path a, Path b, double gm) {
        return a.period(gm) == b.period(gm)
                ? Math.sqrt(
                        Math.pow(a.radius() - b.radius(), 2)
                                + 4
                                        * a.radius()
                                        * b.radius()
                                        * Math.pow(Math.sin((a.phase() - b.phase()) / 2), 2))
                : Math.abs(a.radius() - b.radius());
    }

    private SystemValidation() {}
}
