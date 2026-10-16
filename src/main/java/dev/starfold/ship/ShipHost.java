package dev.starfold.ship;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;

/**
 * What hulls ask of the layers built on them. Server wiring installs the answers.
 * Alone, ships have no wider moving frame or arrival preparation, keep their own roll,
 * and send no region snapshot; notices go nowhere.
 */
public interface ShipHost {
    /** Hull hardware's cruise and boosted speed limits, in blocks per second. */
    record SpeedCaps(double cruise, double boost) {}

    /** With no wider flight system, packets use the baseline speed limit. */
    default double minimumSpeed() {
        return 200;
    }

    /** Without a wider flight system, hardware has only baseline speed limits. */
    default SpeedCaps speedCaps(ServerSubLevel body) {
        return new SpeedCaps(minimumSpeed(), minimumSpeed());
    }

    /** Whether the hull's turning thrusters are being used to roll its surroundings instead. */
    default boolean externalRoll(Level level, ServerSubLevel body) {
        return false;
    }

    /** A pilot has left the helm; any higher-layer steering wish must end. */
    default void pilotReleased(ServerPlayer player) {}

    /** No hull container remains, so any prepared arrival can be released. */
    default void releaseArrival(ServerLevel level) {}

    /** Advance the surroundings before processing hull handovers. */
    default void advanceSurroundings(ServerLevel level, ServerSubLevelContainer container) {}

    /** Choose the labels a departing hull will share with hulls already outside the chart. */
    default Space.Frame departureFrame(
            ServerLevel level,
            Space.Motion motion,
            Quaterniond orientation,
            GroundEphemeris.Model model,
            int planet,
            long tick) {
        return Space.Frame.IDENTITY;
    }

    /** A hull has departed its planet, after its coordinate transport. */
    default void hullDeparted(ServerLevel level, int planet, String id) {}

    /** Spin carried by region labels instead of the hull's native angular velocity. */
    default Point carriedSpin(ServerLevel level, java.util.UUID hull) {
        return GroundEphemeris.ZERO;
    }

    /** The current labels for hull motion outside the charts; alone, labels are unchanged. */
    default Space.Frame surroundingsFrame(ServerLevel level) {
        return Space.Frame.IDENTITY;
    }

    /** Whether the destination's surrounding columns are loaded; alone, no preparation is promised. */
    default boolean landingLoaded(ServerLevel level, Point anchor) {
        return false;
    }

    /** Whether the prepared destination is ready for handover; alone, handover is unavailable. */
    default boolean landingReady(GroundEphemeris.Body body, double height, boolean full) {
        return false;
    }

    /** The destination column used in arrival diagnostics. */
    default ChunkPos landingColumn(Point anchor) {
        return new ChunkPos((int) Math.floor(anchor.x()) >> 4, (int) Math.floor(anchor.z()) >> 4);
    }

    /** A hull is back in its chart, after its coordinate transport. */
    default void hullEnteredChart(ServerLevel level, String id) {}

    /** A hull has arrived and its prepared destination can be released. */
    default void hullArrived(ServerLevel level, ServerSubLevel body) {}

    /** Snapshot carried with a player's coordinate relabel; alone, the observer is outside any moving frame. */
    default SpacePackets.Frame carriedFrame(ServerPlayer player) {
        return new SpacePackets.Frame(null, 0);
    }

    /** Production retains every loaded hull; a dev experiment may exclude one UUID. */
    default boolean alwaysTicket(java.util.UUID ship) {
        return true;
    }

    /** A transient probe hold is a separate reason, never a saved force-load ticket. */
    default boolean probeTicket(java.util.UUID ship) {
        return false;
    }

    /** Space dormancy stays off unless a development experiment enables it. */
    default boolean spaceDormancy() {
        return Boolean.getBoolean("starfold.shipProbe");
    }

    /** An occupied region retains its lead, even with a distant observer. */
    default boolean retainLead(ServerLevel level, ServerSubLevel body) {
        return false;
    }

    static ShipHost get() {
        return Installed.host;
    }

    static void install(ShipHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed host. */
    final class Installed {
        private static volatile ShipHost host = new ShipHost() {};

        private Installed() {}
    }
}
