package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.render.ShaderUniforms;

import net.minecraft.client.renderer.ShaderInstance;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class CoreShaderShaderMixin {
    // Wrap the whole apply, including other mods' TAIL uploads, rather than racing at TAIL.
    @WrapMethod(method = "apply")
    private void starfold$capUniforms(Operation<Void> original) {
        original.call();
        var shader = (ShaderInstance) (Object) this;
        dev.starfold.render.GroundClient.apply(shader);
    }

    @Inject(method = "updateLocations", at = @At("HEAD"))
    private void starfold$relinked(CallbackInfo ci) {
        ShaderUniforms.invalidate((ShaderInstance) (Object) this);
    }
}
