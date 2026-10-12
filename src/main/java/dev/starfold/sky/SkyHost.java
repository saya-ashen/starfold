package dev.starfold.sky;

import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What the sky asks of travel and observer views. The wiring installs server and client answers
 * separately, so an integrated server does not replace the client's view. On its own the sky has
 * no vessel under way or client space view, uses unchanged client coordinates, and receives full
 * sunlight in the space region.
 */
public interface SkyHost {
    /** Whether a vessel is under way and sleeping must not advance its clock. */
    default boolean underWay() {
        return false;
    }

    /** Sunlight at a space-region position, using the current region frame and celestial clock. */
    default double sunlight(ServerLevel level, BlockPos pos, GroundEphemerisSave clock) {
        return 1;
    }

    /** Whether the client's eye is in the space region. */
    default boolean present() {
        return false;
    }

    /** Client eye coordinates expressed in the planet's storage chart. */
    default Point storage(double x, double y, double z) {
        return new Point(x, y, z);
    }

    static SkyHost get() {
        return Installed.server;
    }

    static void install(SkyHost host) {
        Installed.server = java.util.Objects.requireNonNull(host);
    }

    static SkyHost client() {
        return Installed.client;
    }

    static void installClient(SkyHost host) {
        Installed.client = java.util.Objects.requireNonNull(host);
    }

    /** Separate holders for server answers and the client's observer view. */
    final class Installed {
        private static volatile SkyHost server = new SkyHost() {};
        private static volatile SkyHost client = new SkyHost() {};

        private Installed() {}
    }
}
