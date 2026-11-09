package dev.starfold.mixin;

import dev.starfold.render.ReversedDepth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Veil (bundled with Aeronautics) clears its framebuffers, the first-person hand target among them, with raw GL. */
@Mixin(
        targets = {
            "foundry.veil.impl.client.render.framebuffer.DSAAdvancedFboImpl",
            "foundry.veil.impl.client.render.framebuffer.LegacyAdvancedFboImpl",
            "foundry.veil.impl.client.render.wrapper.DSAVanillaAdvancedFboWrapper",
            "foundry.veil.impl.client.render.wrapper.LegacyVanillaAdvancedFboWrapper"
        },
        remap = false)
public abstract class ReversedDepthVeilMixin {
    @ModifyVariable(method = "clear(FFFFFI[I)V", at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private float starfold$clearDepth(float depth) {
        return ReversedDepth.ACTIVE ? 1 - depth : depth;
    }
}
