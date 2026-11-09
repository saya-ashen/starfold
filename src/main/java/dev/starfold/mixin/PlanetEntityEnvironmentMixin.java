package dev.starfold.mixin;

import dev.starfold.planet.PlanetConditions;
import dev.starfold.planet.PlanetGravity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Entity.class)
public abstract class PlanetEntityEnvironmentMixin {
    @Inject(method = "baseTick", at = @At("HEAD"))
    private void starfold$environment(CallbackInfo ci) {
        var entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity living) PlanetGravity.sync(living);
        if (PlanetConditions.airless(entity.level(), entity.blockPosition())) entity.clearFire();
    }

    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void starfold$gravity(CallbackInfoReturnable<Double> ci) {
        var entity = (Entity) (Object) this;
        if (!(entity instanceof LivingEntity))
            ci.setReturnValue(
                    ci.getReturnValue()
                            * PlanetConditions.gravity(entity.level(), entity.blockPosition()));
    }

    @Inject(method = "setRemainingFireTicks", at = @At("HEAD"), cancellable = true)
    private void starfold$noIgnition(int ticks, CallbackInfo ci) {
        var entity = (Entity) (Object) this;
        if (ticks > 0 && PlanetConditions.airless(entity.level(), entity.blockPosition())) {
            entity.clearFire();
            ci.cancel();
        }
    }
}
