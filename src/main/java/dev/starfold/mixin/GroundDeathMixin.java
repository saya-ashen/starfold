package dev.starfold.mixin;

import dev.starfold.ground.GroundServer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cleanup only after a completed player death, never after a cancelled LivingDeathEvent. */
@Mixin(ServerPlayer.class)
public abstract class GroundDeathMixin {
    @Inject(method = "die", at = @At("TAIL"))
    private void starfold$afterDeath(DamageSource source, CallbackInfo ci) {
        GroundServer.afterDeath((ServerPlayer) (Object) this);
    }
}
