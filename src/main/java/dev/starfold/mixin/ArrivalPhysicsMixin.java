package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;

import dev.starfold.probe.ArrivalProbe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(
        targets = "dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager",
        remap = false)
public abstract class ArrivalPhysicsMixin {
    @WrapOperation(
            method = "inhabitChunk",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private ChunkAccess starfold$arrivalPhysics(
            ServerLevel level,
            int x,
            int z,
            ChunkStatus status,
            boolean load,
            Operation<ChunkAccess> original) {
        long start = ArrivalProbe.start();
        try {
            return original.call(level, x, z, status, load);
        } finally {
            ArrivalProbe.end("physicsChunk", start);
            if (ArrivalProbe.ENABLED)
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE ARRIVAL_PHYSICS wall {} game {} ns {} thread {} x {} z {}",
                                System.currentTimeMillis(),
                                level.getGameTime(),
                                System.nanoTime() - start,
                                Thread.currentThread().getName(),
                                x,
                                z);
        }
    }
}
