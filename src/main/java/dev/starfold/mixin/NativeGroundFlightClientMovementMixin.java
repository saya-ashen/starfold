package dev.starfold.mixin;
import dev.starfold.NativeGroundFlightClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class NativeGroundFlightClientMovementMixin {
    @Inject(method="sendPosition",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeMove(CallbackInfo ci){if(NativeGroundFlightClient.controlled())ci.cancel();}
}
