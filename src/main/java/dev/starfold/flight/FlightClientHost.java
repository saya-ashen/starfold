package dev.starfold.flight;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/** Client view requests from personal flight. Alone there is no drawn chart or region view. */
public interface FlightClientHost {
    /** Discard the old distant view on departure; alone there is no distant view. */
    default void departing() {}

    /** Whether another travel frame shares this level; alone no other frame does. */
    default boolean shared(Level level) {
        return false;
    }

    /** Associate the drawn chart with a flight session; alone nothing is drawn. */
    default void sessionBound(ClientLevel level, long session) {}

    /** Supply atmosphere metadata to the view; alone there is no view to update. */
    default void atmosphere(GroundAir.Config config) {}

    /** Supply celestial metadata to the view; alone there is no view to update. */
    default void metadata(GroundEphemeris.Model model, long tick) {}

    /** Whether the chart view can place the pilot; alone it is not ready. */
    default boolean viewReady() {
        return false;
    }

    /** Whether the view requests diagnostic instruments; alone it does not. */
    default boolean diagnosticHud() {
        return false;
    }

    /** Position of feet in the drawn chart, or null when no chart is available. */
    default Point chartPosition(int planet, PlanetShape.Pose pose) {
        return null;
    }

    /** Quarter turns from a face to the drawn chart; alone there is no extra turn. */
    default int chartTurns(Face face) {
        return 0;
    }

    /** Position of an arbitrary point in the drawn chart, or null without a chart. */
    default Point chartPoint(int planet, Face face, Point point) {
        return null;
    }

    static FlightClientHost get() {
        return Installed.host;
    }

    static void install(FlightClientHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed client host; never loaded by server wiring. */
    final class Installed {
        private static volatile FlightClientHost host = new FlightClientHost() {};

        private Installed() {}
    }
}
