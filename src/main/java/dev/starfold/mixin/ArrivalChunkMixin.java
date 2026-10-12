package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.probe.ArrivalProbe;

import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerChunkCache.class)
public abstract class ArrivalChunkMixin {
    @WrapMethod(method = "getChunk")
    private ChunkAccess starfold$arrivalChunk(
            int x, int z, ChunkStatus status, boolean load, Operation<ChunkAccess> original) {
        if (!ArrivalProbe.ENABLED) return original.call(x, z, status, load);
        long start = ArrivalProbe.start();
        try {
            return original.call(x, z, status, load);
        } finally {
            ArrivalProbe.chunk(start, x, z, load);
        }
    }

    @WrapMethod(method = "move")
    private void starfold$arrivalMove(ServerPlayer player, Operation<Void> original) {
        long start = ArrivalProbe.start();
        try {
            original.call(player);
        } finally {
            ArrivalProbe.end("chunkMove", start);
        }
    }
}
