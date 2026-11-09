package dev.starfold.mixin;

import dev.starfold.planet.PlanetConditions;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({FlintAndSteelItem.class, FireChargeItem.class})
public abstract class PlanetFirestarterMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void starfold$noFirestarter(
            UseOnContext context, CallbackInfoReturnable<InteractionResult> ci) {
        if (PlanetConditions.airless(context.getLevel(), context.getClickedPos())
                || PlanetConditions.airless(
                        context.getLevel(),
                        context.getClickedPos().relative(context.getClickedFace())))
            ci.setReturnValue(InteractionResult.FAIL);
    }
}
