package dev.starfold.waypoint;

import dev.starfold.flight.GroundDynamics;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetShape;
import dev.starfold.sky.GroundEphemeris;

/** Immutable planet-local navigation annotation, independent of persistence and commands. */
public record Waypoint(String name, int planet, GroundTopology.Pose pose) {
    public static final String LAUNCH = "starfold:launch";

    public static final class Failure extends IllegalArgumentException {
        final String key;
        final Object[] arguments;

        Failure(String key, Object... arguments) {
            super(key);
            this.key = key;
            this.arguments = arguments;
        }
    }

    public Waypoint {
        if (name == null
                || !(name.equals(LAUNCH) || name.matches("[\\p{L}\\p{N}_-]{1,32}"))
                || pose == null
                || pose.point().y() > GroundDynamics.HANDOFF) {
            throw new Failure("message.starfold.mark.invalid");
        }
        PlanetShape.valid(planet);
    }

    public Point world(GroundEphemeris.Model model, double tick) {
        if (planet >= model.count()) {
            throw new IllegalArgumentException("Marker planet unavailable");
        }
        return model.at(planet, tick).world(GroundTopology.planet(PlanetShape.of(planet), pose));
    }
}
