package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceView;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.material.FogType;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** Chart sky depth is discarded before terrain; region bodies retain physical depth. */
public final class GroundCelestials {
    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        GroundSunRenderer.initialize(modBus);
        GroundStars.initialize(modBus);
        PlanetRemoteRenderer.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent) -> {
                    if (cpEvent.getStage()
                            == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_SKY) {
                        PlanetRemoteRenderer.beginFrame();
                        if (!SpaceClient.active()
                                && GroundClient.ready()
                                && RendererGuard.pipelineSupported())
                            drawSky(new RenderFrame(cpEvent));
                    }
                    if (cpEvent.getStage()
                            != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                    .AFTER_ENTITIES) return;
                    var c = new RenderFrame(cpEvent);
                    if ((!SpaceClient.active() && !GroundClient.ready())
                            || !RendererGuard.pipelineSupported()
                            || c.matrixStack() == null) return;
                    PlanetRemoteRenderer.draw(c);
                    if (SpaceClient.active()) {
                        GroundStars.draw(c);
                        GroundSunRenderer.draw(c);
                    }
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

    private static void drawSky(RenderFrame c) {
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        boolean write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            RenderSystem.setProjectionMatrix(SpaceView.skyProjection(projection), sorting);
            GroundStars.draw(c);
            if (c.camera().getFluidInCamera() == FogType.NONE) {
                PlanetRemoteRenderer.draw(c);
                if (Minecraft.getInstance()
                                .level
                                .getRainLevel(c.tickCounter().getGameTimeDeltaPartialTick(false))
                        == 0) GroundSunRenderer.draw(c);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.depthMask(write);
            RenderSystem.setProjectionMatrix(projection, sorting);
        }
    }

    private static void clear() {
        GroundSunRenderer.clear();
        GroundStars.clear();
        PlanetRemoteRenderer.clear();
    }

    private GroundCelestials() {}
}
