package dev.starfold.mixin;

import dev.starfold.render.SodiumShaderSource;
import dev.starfold.render.SodiumSupport;

import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium reads its chunk shader through its own loader, so the vanilla core-shader patches never
 * see it. This handler is gated by the mixin plugin on Sodium's presence and by the capability
 * check at read time.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader")
public abstract class SodiumShaderSourceMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void starfold$projectChunkShader(
            ResourceLocation id, CallbackInfoReturnable<String> ci) {
        if (!"sodium".equals(id.getNamespace()) || !SodiumShaderSource.handles(id.getPath()))
            return;
        if (!SodiumSupport.supported() || SodiumSupport.broken()) return;
        try {
            ci.setReturnValue(SodiumShaderSource.patch(id.getPath(), ci.getReturnValue()));
        } catch (RuntimeException e) {
            SodiumSupport.breakShader(e.getMessage());
        }
    }
}
