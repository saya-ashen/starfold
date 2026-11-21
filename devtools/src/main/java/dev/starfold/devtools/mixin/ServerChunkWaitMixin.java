package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkWaitMixin {
    @Unique private static final boolean starfold$TIME_TICKS = Boolean.getBoolean("starfold.traceLongTicks");

    @WrapMethod(method = "getChunk")
    private ChunkAccess starfold$traceWait(int x, int z, ChunkStatus status, boolean load,
                                           Operation<ChunkAccess> original) {
        if (!starfold$TIME_TICKS) return original.call(x, z, status, load);
        long start = System.nanoTime();
        try {
            return original.call(x, z, status, load);
        } finally {
            long duration = System.nanoTime() - start;
            if (duration >= 20_000_000L) {
                String stack = StackWalker.getInstance().walk(frames -> frames.limit(36)
                        .map(Object::toString).collect(java.util.stream.Collectors.joining("\n  ")));
                LogUtils.getLogger().info("Slow getChunk ({}, {}) {} load={} at {} ns took {} ms\n  {}",
                        x, z, status, load, start, duration / 1e6, stack);
            }
        }
    }
}
