package dev.starfold.render;

import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;

import net.minecraft.server.packs.resources.ResourceManager;

/** Original native terrain/opaque entities precede these; vanilla translucent geometry follows. */
public final class GroundCelestials {
    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        GroundSunRenderer.initialize(modBus);
        GroundStars.initialize(modBus);
        PlanetRemoteRenderer.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_SKY) PlanetRemoteRenderer.beginFrame();
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_ENTITIES) return;
                    var c = new RenderFrame(cpEvent);
                    if ((!SpaceClient.active() && !GroundClient.ready())
                            || !RendererGuard.pipelineSupported()
                            || c.matrixStack() == null) return;
                    PlanetRemoteRenderer.draw(c);
                    GroundStars.draw(c);
                    GroundSunRenderer.draw(c);
                });
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) ->
                        event.registerReloadListener(
                                new net.minecraft.server.packs.resources
                                        .ResourceManagerReloadListener() {
                                    @Override
                                    public void onResourceManagerReload(ResourceManager manager) {
                                        clear();
                                    }
                                }));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.GameShuttingDownEvent cpEvent) -> {
                    clear();
                });
    }

    private static void clear() {
        GroundSunRenderer.clear();
        GroundStars.clear();
        PlanetRemoteRenderer.clear();
    }

    private GroundCelestials() {}
}
