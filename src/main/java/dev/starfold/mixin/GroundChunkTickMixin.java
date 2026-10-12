package dev.starfold.mixin;

import dev.starfold.ground.ChunkTickReadiness;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.probe.ArrivalProbe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ghost chunks are display/query addresses, never a second random-tick/weather simulation. */
@Mixin(ServerLevel.class)
public abstract class GroundChunkTickMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "tickChunk",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void starfold$probeBlock(
            net.minecraft.world.level.block.state.BlockState state,
            ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.util.RandomSource random,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (!ArrivalProbe.ENABLED) {
            original.call(state, level, pos, random);
            return;
        }
        var previous = ArrivalProbe.tickingBlock;
        ArrivalProbe.tickingBlock = pos.immutable();
        try {
            original.call(state, level, pos, random);
        } finally {
            ArrivalProbe.tickingBlock = previous;
        }
    }

    @Inject(method = "shouldTickBlocksAt(J)Z", at = @At("RETURN"), cancellable = true)
    private void starfold$readableNeighbours(
            long chunk,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValueZ() && !ChunkTickReadiness.ready((ServerLevel) (Object) this, chunk))
            ci.setReturnValue(false);
    }

    @Inject(method = "tickBlock", at = @At("HEAD"))
    private void starfold$scheduledBlockProbe(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.Block block,
            CallbackInfo ci) {
        if (ArrivalProbe.ENABLED) ArrivalProbe.tickingBlock = pos.immutable();
    }

    @Inject(method = "tickFluid", at = @At("HEAD"))
    private void starfold$scheduledFluidProbe(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.material.Fluid fluid,
            CallbackInfo ci) {
        if (ArrivalProbe.ENABLED) ArrivalProbe.tickingBlock = pos.immutable();
    }

    @Inject(method = "tickBlock", at = @At("RETURN"))
    private void starfold$scheduledBlockProbeEnd(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.Block block,
            CallbackInfo ci) {
        if (ArrivalProbe.ENABLED) ArrivalProbe.tickingBlock = null;
    }

    @Inject(method = "tickFluid", at = @At("RETURN"))
    private void starfold$scheduledFluidProbeEnd(
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.material.Fluid fluid,
            CallbackInfo ci) {
        if (ArrivalProbe.ENABLED) ArrivalProbe.tickingBlock = null;
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void starfold$onlyOwner(LevelChunk chunk, int speed, CallbackInfo ci) {
        if (!ChunkTickReadiness.ready((ServerLevel) (Object) this, chunk.getPos().toLong())) {
            ci.cancel();
            return;
        }
        if (PlanetLevels.is((ServerLevel) (Object) this)
                && PlanetAccess.of((ServerLevel) (Object) this)
                                .key(chunk.getPos().x, chunk.getPos().z)
                        < 0
                && PlanetAccess.of((ServerLevel) (Object) this)
                        .inChart(
                                new net.minecraft.core.BlockPos(
                                        chunk.getPos().getMiddleBlockX(),
                                        128,
                                        chunk.getPos().getMiddleBlockZ()))) ci.cancel();
    }
}
