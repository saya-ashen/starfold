package dev.starfold.mixin;

import dev.starfold.flight.GroundFlightClient;

import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class GroundFlightCameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void starfold$nativeCamera(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean mirror,
            float partial,
            CallbackInfo ci) {
        if (!(entity instanceof LocalPlayer)) return;
        var point = GroundFlightClient.camera((Camera) (Object) this, partial);
        if (point != null) setPosition(point.x(), point.y(), point.z());
    }
}
