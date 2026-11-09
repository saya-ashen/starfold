package dev.starfold.mixin;

import dev.starfold.ship.ThrusterClient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/** The inset model cannot hide a whole neighbour face. Rendering only; its full-cube collision is unchanged. */
@Mixin(Block.class)
public abstract class ThrusterOcclusionMixin {
    @Inject(method = "shouldRenderFace", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;skipRendering(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"),
            locals = LocalCapture.CAPTURE_FAILHARD, cancellable = true)
    private static void starfold$nozzleNeighbour(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Direction side,
            BlockPos neighbour,
            CallbackInfoReturnable<Boolean> result,
            BlockState neighbourState) {
        // Vanilla already fetched the neighbour; keep the inset-face exception without another lookup.
        if (ThrusterClient.inset(neighbourState)) result.setReturnValue(true);
    }
}
