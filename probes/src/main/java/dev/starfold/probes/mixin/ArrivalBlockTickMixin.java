package dev.starfold.probes.mixin;

import dev.starfold.probe.ArrivalProbe;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ArrivalBlockTickMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "tickChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void starfold$probeBlock(
            net.minecraft.world.level.block.state.BlockState state,
            ServerLevel level, net.minecraft.core.BlockPos pos,
            net.minecraft.util.RandomSource random,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var previous = ArrivalProbe.tickingBlock;
        ArrivalProbe.tickingBlock = pos.immutable();
        try {
            original.call(state, level, pos, random);
        } finally {
            ArrivalProbe.tickingBlock = previous;
        }
    }

    @Inject(method = "tickBlock", at = @At("HEAD"))
    private void starfold$scheduledBlockProbe(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.Block block, CallbackInfo ci) {
        ArrivalProbe.tickingBlock = pos.immutable();
    }
    @Inject(method = "tickFluid", at = @At("HEAD"))
    private void starfold$scheduledFluidProbe(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.material.Fluid fluid, CallbackInfo ci) {
        ArrivalProbe.tickingBlock = pos.immutable();
    }
    @Inject(method = "tickBlock", at = @At("RETURN"))
    private void starfold$scheduledBlockProbeEnd(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.Block block, CallbackInfo ci) {
        ArrivalProbe.tickingBlock = null;
    }
    @Inject(method = "tickFluid", at = @At("RETURN"))
    private void starfold$scheduledFluidProbeEnd(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.material.Fluid fluid, CallbackInfo ci) {
        ArrivalProbe.tickingBlock = null;
    }
}
