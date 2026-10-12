package dev.starfold.mixin;

import dev.starfold.universe.SystemDefinition;

import net.minecraft.core.Registry;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Validate the selected, bound dimensions before either launcher saves a new world's level.dat. */
@Mixin(WorldDimensions.class)
public abstract class SystemValidationMixin {
    @Inject(method = "bake", at = @At("RETURN"))
    private void starfold$validateSystem(
            Registry<LevelStem> dimensions, CallbackInfoReturnable<WorldDimensions.Complete> cir) {
        SystemDefinition.validateDimensions(cir.getReturnValue().dimensions());
    }
}
