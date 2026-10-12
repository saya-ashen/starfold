package dev.starfold.ground;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * What the ground layer asks of the layers built on it: personal flight, ships, the space region and the far
 * view. The server wiring installs the real answers. Until it does, nobody is in flight or carried, and the
 * notices go nowhere, which is how the ground behaves on its own.
 */
public interface GroundHost {
    /** A player's place changed in one step: what was shown to them from the old place no longer holds. */
    default void viewLost(ServerPlayer player) {}

    /** A player stands somewhere new after a seam or a move, and personal flight should take its bearings again. */
    default void resited(ServerPlayer player) {}

    /** A player was carried somewhere that may be the space region or out of it. */
    default void regionSync(ServerPlayer player) {}

    /** Whether personal flight, not vanilla movement, moves this entity. */
    default boolean controlled(Entity entity) {
        return false;
    }

    /** Whether a player is flying high enough to be sent the wider, coarser surroundings. */
    default boolean aloft(ServerPlayer player) {
        return false;
    }

    /** Whether a ship is carrying this player across a seam right now. */
    default boolean carrying(ServerPlayer player) {
        return false;
    }

    /** Release higher-layer arrival preparation before ground tickets; alone there is none. */
    default void clearArrival() {}

    /** Cleanup after a completed player death, supplied by the player lifecycle wiring. */
    default void afterDeath(ServerPlayer player) {}

    static GroundHost get() {
        return Installed.host;
    }

    static void install(GroundHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed host. */
    final class Installed {
        private static volatile GroundHost host = new GroundHost() {};

        private Installed() {}
    }
}
