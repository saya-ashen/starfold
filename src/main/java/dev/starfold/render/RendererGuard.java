package dev.starfold.render;

import dev.starfold.planet.PlanetLevels;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/** Shared renderer compatibility guard and once-per-level Native notice. */
public final class RendererGuard {
    private static final RendererCompatibility.Notice rendererNotice =
            new RendererCompatibility.Notice();

    private RendererGuard() {}

    public static boolean pipelineSupported() {
        var loader = ModList.get();
        return RendererCompatibility.supported(loader::isLoaded);
    }

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (rendererNotice.enter(
                            mc.level,
                            PlanetLevels.is(mc.level),
                            mc.player != null,
                            pipelineSupported())) {
                        var message = Component.translatable("message.starfold.renderer");
                        mc.player.sendSystemMessage(message);
                        org.slf4j.LoggerFactory.getLogger("starfold-renderer")
                                .warn("{}", message.getString());
                    }
                });
    }
}
