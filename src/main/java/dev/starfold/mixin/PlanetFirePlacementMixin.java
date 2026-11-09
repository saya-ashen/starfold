package dev.starfold.mixin;

import dev.starfold.planet.PlanetConditions;
import dev.starfold.planet.PlanetFire;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Central placement gate also covers dispensers, lava ignition and flaming projectiles. */
@Mixin(Level.class)
public abstract class PlanetFirePlacementMixin {
    @Inject(
            method =
                    "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void starfold$noFire(
            BlockPos pos,
            BlockState state,
            int flags,
            int depth,
            CallbackInfoReturnable<Boolean> ci) {
        if (PlanetFire.fire(state) && PlanetConditions.airless((Level) (Object) this, pos))
            ci.setReturnValue(false);
    }

    @ModifyVariable(
            method =
                    "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            argsOnly = true)
    private BlockState starfold$unlit(
            BlockState state, BlockPos pos, BlockState original, int flags, int depth) {
        return state.getBlock() instanceof CampfireBlock
                        && state.getValue(CampfireBlock.LIT)
                        && PlanetConditions.airless((Level) (Object) this, pos)
                ? state.setValue(CampfireBlock.LIT, false)
                : state;
    }
}
