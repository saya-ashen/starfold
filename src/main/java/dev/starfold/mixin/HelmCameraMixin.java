package dev.starfold.mixin;

import dev.starfold.ship.HelmClient;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class HelmCameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void starfold$helm(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean mirror,
            float partial,
            CallbackInfo ci) {
        var p = HelmClient.camera((Camera) (Object) this, partial);
        if (p != null) setPosition(p.x, p.y, p.z);
    }
}
