package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.server.level.ServerPlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class GroundHostTest {
    @Test
    void completedDeathIsForwardedOnceToTheInstalledHost() {
        var previous = GroundHost.get();
        var calls = new ArrayList<ServerPlayer>();
        try {
            GroundHost.install(
                    new GroundHost() {
                        @Override
                        public void afterDeath(ServerPlayer player) {
                            calls.add(player);
                        }
                    });
            GroundHost.get().afterDeath(null);
            assertEquals(1, calls.size());
            assertNull(calls.getFirst());
        } finally {
            GroundHost.install(previous);
        }
    }

    @Test
    void standaloneDeathHookNeedsNoUpperLayer() {
        assertDoesNotThrow(() -> new GroundHost() {}.afterDeath(null));
    }
}
