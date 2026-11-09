package dev.starfold.dev;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Opt-in measurements for integrated-server chunk loading; never requests chunks. */
final class DevChunkPerfProbe {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-chunk-perf");
    private static long start, previous;
    private static int ticks;
    private static final java.util.Map<String, Integer> generated = new java.util.HashMap<>();

    static void initialize() {
        if (!Boolean.getBoolean("starfold.chunkPerf")) return;
        // Vanilla registers this only on dedicated servers; use its unchanged implementation.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                net.minecraft.server.commands.SaveAllCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.level.getGameTime() % 20 != 0) return;
            LOG.info("PROBE PERF client dim {} chunks {} distance {} pos {} health {} ground {} flying {}",
                    mc.level.dimension().location(), mc.level.getChunkSource().getLoadedChunksCount(),
                    mc.options.getEffectiveRenderDistance(), mc.player.position(), mc.player.getHealth(),
                    mc.player.onGround(), mc.player.getAbilities().flying);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> {
            start = System.nanoTime();
            if (previous != 0) LOG.info("PROBE PERF interval_ms {}", (start - previous) / 1e6);
            previous = start;
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && event.isNewChunk())
                generated.merge(level.dimension().location().toString(), 1, Integer::sum);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            LOG.info("PROBE PERF tick {} ms {}", ++ticks, (System.nanoTime() - start) / 1e6);
            if (ticks % 20 != 0) return;
            for (var level : event.getServer().getAllLevels()) {
                var id = level.dimension().location().toString();
                LOG.info("PROBE PERF dim {} loaded {} new {} stats {}", id,
                        level.getChunkSource().getLoadedChunksCount(), generated.getOrDefault(id, 0),
                        level.getChunkSource().gatherStats());
            }
            for (var player : event.getServer().getPlayerList().getPlayers())
                LOG.info("PROBE PERF player {} pos {} health {} ground {}", player.level().dimension().location(),
                        player.position(), player.getHealth(), player.onGround());
        });
    }

    private DevChunkPerfProbe() {}
}
