package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;

/**
 * Observer values and target notices supplied by layers built on the space region.
 * Alone, the region has no celestial metadata, chart planet, saved target or distant view,
 * its sky time is zero, and no suns are drawn.
 */
public interface SpaceClientHost {
    /** Celestial metadata for the current client level; absent without an observer provider. */
    default GroundEphemeris.Model ephemeris() {
        return null;
    }

    /** Current celestial clock, including the render partial; zero without metadata. */
    default double skyTime(float delta) {
        return 0;
    }

    /** Discard the distant view when the region observer changes; alone there is none. */
    default void viewLost() {}

    /** Maximum number of nearby suns drawn; alone no sun view is provided. */
    default int sunLimit() {
        return 0;
    }

    /** Planet of the current chart observer; absent without a chart view. */
    default int planet() {
        return -1;
    }

    /** Capture target guidance with the same world projection; alone there is no target. */
    default void captureTarget(RenderFrame context) {}

    /** Planet of the active navigation bookmark; absent without saved targets. */
    default int launchPlanet() {
        return -1;
    }

    /** Chart guidance towards the active bookmark; absent without saved targets. */
    default FlightSnapshot.Home home() {
        return null;
    }

    static SpaceClientHost get() {
        return Installed.host;
    }

    static void install(SpaceClientHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed client host, separate from server notices. */
    final class Installed {
        private static volatile SpaceClientHost host = new SpaceClientHost() {};

        private Installed() {}
    }
}
