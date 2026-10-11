package dev.starfold.mixin;
import dev.starfold.NativeGroundFlightServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scoped vessel owner only; low-altitude native movement and all other dimensions untouched. */
@Mixin(LivingEntity.class)
public abstract class NativeGroundFlightTravelMixin {
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeVessel(Vec3 input,CallbackInfo ci){var e=(LivingEntity)(Object)this;if(NativeGroundFlightServer.controlled(e)){e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;ci.cancel();}}
}
