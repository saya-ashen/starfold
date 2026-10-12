package dev.starfold.mixin;

import dev.starfold.ship.HelmClient;

import net.minecraft.client.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Retain vanilla's remappable toggle-perspective key, replacing only its helm cycle. */
@Mixin(Minecraft.class)
public abstract class HelmPerspectiveMixin {
    @Redirect(
            method = "handleKeybinds",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/Options;setCameraType(Lnet/minecraft/client/CameraType;)V"))
    private void starfold$view(Options options, CameraType type) {
        if (HelmClient.body() != null) HelmClient.cycle();
        else options.setCameraType(type);
    }
}
