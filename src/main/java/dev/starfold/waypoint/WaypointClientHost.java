package dev.starfold.waypoint;

import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.planet.PlanetAccess;

/** Chart observer values supplied by client wiring. Alone, waypoints have no chart view. */
public interface WaypointClientHost {
    default int planet() {
        return -1;
    }

    default ObserverFrame frame() {
        return null;
    }

    default PlanetAccess access() {
        return null;
    }

    default PlanetShape shape() {
        return null;
    }

    default GroundSpace.Pose pose() {
        return null;
    }

    default boolean ready() {
        return false;
    }

    default boolean diagnosticHud() {
        return false;
    }

    static WaypointClientHost get() {
        return Installed.host;
    }

    static void install(WaypointClientHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed host. */
    final class Installed {
        private static volatile WaypointClientHost host = new WaypointClientHost() {};

        private Installed() {}
    }
}
