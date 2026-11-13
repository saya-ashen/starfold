package dev.starfold.mixin;

import dev.starfold.render.SodiumProjection;
import dev.starfold.render.SodiumSupport;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium calls this once per terrain pass while its chunk program is bound. Iris replaces the
 * chunk program when a shader pack is active, so this never runs against pack shaders.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface")
public abstract class SodiumShaderInterfaceMixin {
    @Inject(method = "setupState", at = @At("TAIL"))
    private void starfold$projectionUniforms(CallbackInfo ci) {
        if (!SodiumSupport.supported()) return;
        SodiumProjection.apply();
    }
}
