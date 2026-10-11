package dev.starfold.mixin;

import dev.starfold.ShaderUniforms;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class LocalCapShaderMixin {
    // Wrap the whole apply, including other mods' TAIL uploads, rather than racing at TAIL.
    @WrapMethod(method="apply")
    private void starfold$capUniforms(Operation<Void> original) {
        original.call();
        var shader=(ShaderInstance)(Object)this;
        dev.starfold.NativeGroundClient.apply(shader);
    }
    @Inject(method="updateLocations",at=@At("HEAD"))
    private void starfold$relinked(CallbackInfo ci) { ShaderUniforms.invalidate((ShaderInstance)(Object)this); }
}
