package dev.starfold;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/** Original native terrain/opaque entities precede these; vanilla translucent geometry follows. */
public final class NativeGroundCelestials {
    public static void initialize(net.neoforged.bus.api.IEventBus modBus){
        NativeGroundSunRenderer.initialize(modBus);NativeGroundStars.initialize(modBus);NativePlanetRemoteRenderer.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderLevelStageEvent cpEvent)->{if(cpEvent.getStage()==net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY)NativePlanetRemoteRenderer.beginFrame();if(cpEvent.getStage()!=net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES)return;var c=new LocalRender(cpEvent);if((!NativeSpaceClient.active()&&!NativeGroundClient.ready())||!LocalCapClient.pipelineSupported()||c.matrixStack()==null)return;NativePlanetRemoteRenderer.draw(c);NativeGroundStars.draw(c);NativeGroundSunRenderer.draw(c);});
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event)->event.registerReloadListener(new net.minecraft.server.packs.resources.ResourceManagerReloadListener(){@Override public void onResourceManagerReload(ResourceManager manager){clear();}}));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent cpEvent)->{clear();});
    }
    private static void clear(){NativeGroundSunRenderer.clear();NativeGroundStars.clear();NativePlanetRemoteRenderer.clear();}
    private NativeGroundCelestials(){}
}
