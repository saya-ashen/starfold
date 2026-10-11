package dev.starfold.mixin;

import dev.starfold.NativeHelmClient;
import net.minecraft.client.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Retain vanilla's remappable toggle-perspective key, replacing only its helm cycle. */
@Mixin(Minecraft.class)
public abstract class NativeHelmPerspectiveMixin {
    @Redirect(method="handleKeybinds",at=@At(value="INVOKE",target="Lnet/minecraft/client/Options;setCameraType(Lnet/minecraft/client/CameraType;)V"))
    private void starfold$view(Options options,CameraType type){if(NativeHelmClient.body()!=null)NativeHelmClient.cycle();else options.setCameraType(type);}
}
