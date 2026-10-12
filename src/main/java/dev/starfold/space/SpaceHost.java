package dev.starfold.space;

import net.minecraft.server.level.ServerPlayer;

/** Notices from the space region to layers built on it. Alone, the region has no saved targets. */
public interface SpaceHost {
    /** Publish the player's saved navigation target for the region session; alone there is none. */
    default void targetSync(ServerPlayer player, long session) {}

    static SpaceHost get() {
        return Installed.host;
    }

    static void install(SpaceHost host) {
        Installed.host = java.util.Objects.requireNonNull(host);
    }

    /** Holder for the installed server host. */
    final class Installed {
        private static volatile SpaceHost host = new SpaceHost() {};

        private Installed() {}
    }
}
