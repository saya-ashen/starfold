package dev.starfold.flight;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Requests and notices from personal flight. Alone, flight has no distant view or saved target. */
public interface FlightHost {
    /** The old surroundings are no longer valid; without a view provider there is nothing to discard. */
    default void viewLost(ServerPlayer player) {}

    /** Publish the player's navigation target for this flight session; alone there is no target. */
    default void targetSync(ServerPlayer player) {}

    /** Clear a saved target before selecting a planet; alone there is no saved target to clear. */
    default void targetCleared(ServerPlayer player) {}

    /** Describe failed navigation data; alone only the generic data-error message is available. */
    default Component failure(RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger("starfold").warn("Navigation data error", error);
        return Component.translatable("message.starfold.data_error");
    }

    /** Ask to leave the planets; without player-entry wiring, no departure is performed. */
    default int leave(ServerPlayer player) {
        return 0;
    }

    static FlightHost get() {
        return Installed.host;
    }

    static void install(FlightHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed host. */
    final class Installed {
        private static volatile FlightHost host = new FlightHost() {};

        private Installed() {}
    }
}
