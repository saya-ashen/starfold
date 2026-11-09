package dev.starfold.mixin;

import dev.starfold.planet.PlanetConditions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extinguishes pre-existing saved fire before it can consume fuel or spread, even with fireTick off. */
@Mixin(FireBlock.class)
public abstract class PlanetFireTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void starfold$extinguish(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            CallbackInfo ci) {
        if (PlanetConditions.airless(level, pos)) {
            level.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
