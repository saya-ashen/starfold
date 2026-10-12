package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;

import dev.starfold.flight.GroundFlightClient;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
public abstract class GroundFlightMouseMixin {
    @WrapOperation(
            method = "turnPlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void starfold$nativeAim(
            LocalPlayer player, double x, double y, Operation<Void> original) {
        if (!GroundFlightClient.turn(player, x, y)) original.call(player, x, y);
    }
}
