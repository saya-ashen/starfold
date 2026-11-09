package dev.starfold.devtools;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = "starfold_devtools", dist = Dist.CLIENT)
public final class Devtools {
    static final Logger LOGGER = LogUtils.getLogger();
    public Devtools() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
                event.getDispatcher().register(Commands.literal("sfprofile")
                        .then(Commands.literal("start").executes(context -> { Profiler.start(); return 1; }))
                        .then(Commands.literal("stop").executes(context -> { Profiler.stop(); return 1; }))));
    }
    static void message(String text) {
        LOGGER.info("[sfprofile] {}", text);
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[sfprofile] " + text));
    }
}
