package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.devtools.Profiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class FrameMixin {
    // Wrap, not redirect: other mods may target the same call. Only the main loop is a frame:
    // forced nested ticks during screen/world changes are not.
    @WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"), require = 1)
    private void sfprofile$frame(Minecraft client, boolean renderLevel, Operation<Void> original) {
        Profiler.frameBegin();
        try { original.call(client, renderLevel); }
        finally { Profiler.frameEnd(); }
    }
}
