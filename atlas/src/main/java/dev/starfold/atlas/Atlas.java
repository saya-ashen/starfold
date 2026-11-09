package dev.starfold.atlas;

import dev.starfold.api.SystemMapApi;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import org.lwjgl.glfw.GLFW;

@Mod(value = "starfold_atlas", dist = Dist.CLIENT)
public final class Atlas {
    static final SurveyStore surveys = new SurveyStore();
    private static final KeyMapping MAP = new KeyMapping("key.starfold_atlas.map", GLFW.GLFW_KEY_M, "key.categories.starfold_atlas");
    public Atlas(IEventBus bus) {
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(MAP));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            surveys.tick();
            var mc = Minecraft.getInstance();
            while (MAP.consumeClick()) if (mc.player != null && mc.screen == null) {
                mc.setScreen(new AtlasScreen(SystemMapApi.inSystem() ? AtlasScreen.Layer.GLOBE : AtlasScreen.Layer.GALAXY));
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> surveys.clear());
    }
}
