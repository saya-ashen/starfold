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

    /** Vanilla's camera fog type only knows water, lava and powder snow; modded fluids count as submerged too. */
    public static boolean submerged(net.minecraft.client.Camera camera) {
        if (camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) return true;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return false;
        var pos = camera.getBlockPosition();
        var fluid = level.getFluidState(pos);
        return !fluid.isEmpty() && camera.getPosition().y < pos.getY() + fluid.getHeight(level, pos);
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
