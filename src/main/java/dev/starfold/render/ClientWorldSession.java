package dev.starfold.render;

import dev.starfold.flight.GroundFlightClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigationClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.common.NeoForge;

/** Connection/world lifecycle, separate from chart sessions and retained remote fallback meshes. */
public final class ClientWorldSession {
    private ClientLevel level;

    public ClientWorldSession() {
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ClientLevel next) {
                if (level != null && level != next) reset();
                level = next;
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
            if (event.getLevel() == level) reset();
        });
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
                    reset();
                    GroundViewClient.disconnected();
                    dev.starfold.planet.PlanetSystem.identities(java.util.List.of());
                });
    }

    private void reset() {
        GroundRenderer.clear();
        GroundClient.resetWorld();
        PlanetRemoteRenderer.clear();
        GroundViewClient.clear();
        SpaceNavigationClient.resetWorld();
        SpaceClient.clear();
        GroundFlightClient.reset();
        level = null;
    }
}
