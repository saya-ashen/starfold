package dev.starfold;

import net.neoforged.fml.ModList;
import net.minecraft.network.chat.Component;

/** Shared renderer compatibility guard and once-per-level Native notice. */
public final class LocalCapClient {
    private static final RendererCompatibility.Notice rendererNotice=new RendererCompatibility.Notice();
    private LocalCapClient() {}
    public static boolean pipelineSupported() {
        var loader=ModList.get();
        return RendererCompatibility.supported(loader::isLoaded);
    }
    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent)->{var mc=net.minecraft.client.Minecraft.getInstance();
            if(rendererNotice.enter(mc.level,NativeGroundServer.is(mc.level),mc.player!=null,pipelineSupported())) {
                var message=Component.translatable("message.starfold.renderer");
                mc.player.sendSystemMessage(message);
                org.slf4j.LoggerFactory.getLogger("starfold-renderer").warn("{}",message.getString());
            }
        });
    }
}
