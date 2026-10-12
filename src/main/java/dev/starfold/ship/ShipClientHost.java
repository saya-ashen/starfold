package dev.starfold.ship;

/**
 * What hull clients ask of the layers built on them. Client wiring installs the answers.
 * Alone, ships have no surrounding moving frame or external mouse steering, and carried
 * frame notices go nowhere.
 */
public interface ShipClientHost {
    /** Whether the chart observer is active; alone there is no chart view. */
    default boolean chartActive() {
        return false;
    }

    /** Map client coordinates into chart storage; alone the mapping is identity. */
    default dev.starfold.geometry.BentStripGeometry.Point storage(double x, double y, double z) {
        return new dev.starfold.geometry.BentStripGeometry.Point(x, y, z);
    }

    /** Current chart access; absent without a chart observer. */
    default dev.starfold.planet.PlanetAccess chartAccess() {
        return null;
    }

    /** Current chart frame; absent without a chart observer. */
    default dev.starfold.ground.ObserverFrame chartFrame() {
        return null;
    }

    /** Install a carried chart observer at the snapshot fence; alone notices go nowhere. */
    default void carriedObserver(dev.starfold.ground.ObserverFrame frame) {}

    /** Whether the observer has a surrounding moving frame, even before its view is ready. */
    default boolean movingSurroundings() {
        return false;
    }

    /** Whether mouse steering owns the flight view rather than the hull's ordinary chase direction. */
    default boolean mouseSteering() {
        return false;
    }

    /** Install the surrounding frame carried by a coordinate relabel, at the same snapshot fence. */
    default void carriedFrame(SpacePackets.Frame frame) {}

    static ShipClientHost get() {
        return Installed.host;
    }

    static void install(ShipClientHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed host. */
    final class Installed {
        private static volatile ShipClientHost host = new ShipClientHost() {};

        private Installed() {}
    }
}
