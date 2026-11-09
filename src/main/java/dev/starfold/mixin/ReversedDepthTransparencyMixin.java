package dev.starfold.mixin;

import dev.starfold.render.ReversedDepth;

import net.minecraft.client.renderer.EffectInstance;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EffectInstance.class)
public abstract class ReversedDepthTransparencyMixin {
    @Inject(method = "apply()V", at = @At("HEAD"))
    private void starfold$depthOrder(CallbackInfo ci) {
        ((EffectInstance) (Object) this).safeGetUniform("ReversedDepth").set(ReversedDepth.ACTIVE ? 1 : 0);
    }
}
