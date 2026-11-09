package dev.starfold.mixin;

import dev.starfold.planet.PlanetConditions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Position-aware wetness, on both sides; never change the dimension-wide weather state. */
@Mixin(Level.class)
public abstract class PlanetWeatherMixin {
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void starfold$dry(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        if (PlanetConditions.airless((Level) (Object) this, pos)) ci.setReturnValue(false);
    }
}
