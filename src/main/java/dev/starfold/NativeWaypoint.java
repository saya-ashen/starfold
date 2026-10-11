package dev.starfold;

import dev.starfold.BentStripGeometry.Point;

/** Immutable planet-local navigation annotation, independent of persistence and commands. */
public record NativeWaypoint(String name, int planet, NativeGroundTopology.Pose pose) {
    public static final String LAUNCH = "starfold:launch";
    public static final class Failure extends IllegalArgumentException {
        final String key;final Object[] arguments;
        Failure(String key,Object... arguments){super(key);this.key=key;this.arguments=arguments;}
    }
    public NativeWaypoint {
        if (name == null || !(name.equals(LAUNCH) || name.matches("[\\p{L}\\p{N}_-]{1,32}"))
                || pose == null || pose.point().y() > NativeGroundDynamics.HANDOFF) {
            throw new Failure("message.starfold.mark.invalid");
        }
        NativePlanetShape.valid(planet);
    }

    public Point world(GroundEphemeris.Model model, double tick) {
        if (planet >= model.count()) {
            throw new IllegalArgumentException("Marker planet unavailable");
        }
        return model.at(planet, tick).world(NativeGroundTopology.planet(NativePlanetShape.of(planet),pose));
    }
}
