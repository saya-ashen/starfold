package dev.starfold.universe;

import static dev.starfold.universe.SystemDefinition.*;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.chart.SunSettings;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;
import java.util.*;

/** Deterministic home-first storage and celestial catalog construction. */
final class SystemMaterializer {
    static Made materialize(SystemDefinition definition, long worldSeed) {
        definition.validate();
        var suns = definition.suns();
        var home = definition.home();
        var beyond = definition.beyond();
        var planets = new ArrayList<PlanetChunkGenerator>();
        var bodies = new ArrayList<GroundEphemeris.Body>();
        var names = new ArrayList<String>();
        var groups = new ArrayList<Integer>();
        var parents = new IdentityHashMap<Planet, Integer>();
        var masses = new IdentityHashMap<Planet, Double>();
        var solarParents = new HashMap<Integer, Integer>();
        var order = new ArrayList<Integer>();
        order.add(home.sun());
        for (int s = 0; s < suns.size(); s++) if (s != home.sun()) order.add(s);
        for (int s : order) {
            var sun = suns.get(s);
            int parent = bodies.size();
            solarParents.put(s, parent);
            bodies.add(
                    new GroundEphemeris.Body(
                            new GroundEphemeris.Fixed(sun.position()),
                            sun.radius(),
                            true,
                            -1,
                            GroundEphemeris.Appearance.LIGHT));
            groups.add(
                    sun.planets().stream().mapToInt(p -> 1 + p.moons().size()).sum()
                            + sun.gasGiants().stream().mapToInt(g -> g.moons().size()).sum());
            var places = new ArrayList<Integer>();
            if (s == home.sun()) places.add(home.planet());
            for (int p = 0; p < sun.planets().size(); p++) if (!places.contains(p)) places.add(p);
            int offset = 0;
            for (int before = 0; before < s; before++) offset += suns.get(before).planets().size();
            for (int p : places) {
                var planet = sun.planets().get(p);
                long seed = planet.seed().orElse(derivedSeed(worldSeed, offset + p));
                parents.put(planet, bodies.size());
                append(planet, seed, parent, sun.gm(), planets, bodies, names);
                var generated = planets.getLast();
                masses.put(planet, 32 * generated.generation().environment().gravity()
                        * Math.pow(generated.terrain().shape().radius(), 2));
            }
        }
        int ordinal = planets.size();
        for (var sun : suns)
            for (var planet : sun.planets())
                for (var moon : planet.moons()) {
                    long seed = moon.seed().orElse(derivedSeed(worldSeed, ordinal++));
                    append(
                            moon,
                            seed,
                            parents.get(planet),
                            masses.get(planet),
                            planets,
                            bodies,
                            names);
                }
        // Gas parents precede their surface moons in the catalog, but do not take storage slots.
        for (int s : order)
            for (var gas : suns.get(s).gasGiants()) {
                var path = gas.orbit();
                var motion =
                        new GroundEphemeris.Orbit(
                                solarParents.get(s),
                                GroundEphemeris.Y,
                                new Point(0, 0, 1),
                                path.radius(),
                                path.period(suns.get(s).gm()),
                                path.phase(),
                                0,
                                gas.spin().spin());
                int gasIndex = bodies.size();
                bodies.add(GroundEphemeris.Body.gas(motion, gas.radius(), gas.gas()));
                for (var moon : gas.moons()) {
                    long seed = moon.seed().orElse(derivedSeed(worldSeed, ordinal++));
                    append(moon, seed, gasIndex, gas.gm(), planets, bodies, names);
                }
            }
        var model = new GroundEphemeris.Model(bodies);
        return new Made(List.copyOf(planets), new Sky(model, names, groups), beyond);
    }

    private static void append(
            Planet planet,
            long seed,
            int parent,
            double parentGm,
            List<PlanetChunkGenerator> planets,
            List<GroundEphemeris.Body> bodies,
            List<String> names) {
        planets.add(
                planet.type()
                        .value()
                        .planet(seed, planet.size())
                        .identity(planet.typeId(), planet.name()));
        names.add(planet.name().orElse(PlanetSystem.defaultName(names.size())));
        var path = planet.orbit();
        var orbit =
                new GroundEphemeris.Orbit(
                        parent,
                        GroundEphemeris.Y,
                        new Point(0, 0, 1),
                        path.radius(),
                        path.period(parentGm),
                        path.phase(),
                        0,
                        planet.rotation());
        bodies.add(
                new GroundEphemeris.Body(
                                orbit,
                                0,
                                false,
                                planets.size() - 1,
                                planet.reach(),
                                GroundEphemeris.Appearance.SURFACE)
                        .withEnvironment(planets.getLast().generation().environment()));
    }

    public static long derivedSeed(long world, int index) {
        long seed = world + 0x9E3779B97F4A7C15L * (index + 1L);
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        return seed ^ (seed >>> 31);
    }

    private SystemMaterializer() {}
}
