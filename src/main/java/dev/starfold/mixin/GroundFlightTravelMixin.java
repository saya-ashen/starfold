package dev.starfold.mixin;

import dev.starfold.flight.GroundFlightServer;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scoped vessel owner only; low-altitude native movement and all other dimensions untouched. */
@Mixin(LivingEntity.class)
public abstract class GroundFlightTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeVessel(Vec3 input, CallbackInfo ci) {
        var e = (LivingEntity) (Object) this;
        if (GroundFlightServer.controlled(e)) {
            e.setDeltaMovement(Vec3.ZERO);
            e.fallDistance = 0;
            ci.cancel();
        }
    }
}
