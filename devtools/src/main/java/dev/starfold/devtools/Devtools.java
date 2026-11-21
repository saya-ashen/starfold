package dev.starfold.devtools;

import com.mojang.logging.LogUtils;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.ChunkWorkDiagnostics.Stage;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

import org.slf4j.Logger;

@Mod(value = "starfold_devtools", dist = Dist.CLIENT)
public final class Devtools {
    static final Logger LOGGER = LogUtils.getLogger();

    public Devtools() {
        FarCommands.register();
        LiveBridge.register();
        registerChunkEvents();
        NeoForge.EVENT_BUS.addListener(
                (RegisterClientCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("sfprofile")
                                                .then(
                                                        Commands.literal("start")
                                                                .executes(
                                                                        context -> {
                                                                            Profiler.start();
                                                                            return 1;
                                                                        }))
                                                .then(
                                                        Commands.literal("stop")
                                                                .executes(
                                                                        context -> {
                                                                            Profiler.stop();
                                                                            return 1;
                                                                        }))));
    }

    private static void registerChunkEvents() {
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (!(ChunkWorkDiagnostics.listener() instanceof ChunkTrace trace)
                    || !(event.getLevel() instanceof Level level) || !PlanetLevels.is(level)) return;
            long chunk = event.getChunk().getPos().toLong();
            long now = System.nanoTime();
            if (level.isClientSide()) trace.clientLoad(chunk, now);
            else if (trace.isServer(level.getServer()))
                trace.record(event.isNewChunk() ? Stage.SERVER_NEW_LOAD : Stage.SERVER_LOAD, chunk, now, 0);
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (ChunkWorkDiagnostics.listener() instanceof ChunkTrace trace
                    && event.getLevel() instanceof Level level && level.isClientSide() && PlanetLevels.is(level))
                trace.clientUnload(event.getChunk().getPos().toLong());
        });
        NeoForge.EVENT_BUS.addListener((ChunkWatchEvent.Watch event) -> watch(event, Stage.SEND_QUEUED));
        NeoForge.EVENT_BUS.addListener((ChunkWatchEvent.Sent event) -> watch(event, Stage.SENT));
    }

    private static void watch(ChunkWatchEvent event, Stage stage) {
        if (ChunkWorkDiagnostics.listener() instanceof ChunkTrace trace
                && trace.isServer(event.getLevel().getServer()) && PlanetLevels.is(event.getLevel())
                && trace.isPlayer(event.getPlayer().getUUID()))
            trace.record(stage, event.getPos().toLong(), System.nanoTime(), 0);
    }

    static void message(String text) {
        LOGGER.info("[sfprofile] {}", text);
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[sfprofile] " + text));
    }
}
