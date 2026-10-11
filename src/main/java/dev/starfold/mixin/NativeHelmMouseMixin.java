package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.NativeSpaceAttitudeClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep MouseHandler and other wrappers running; only consume the final vanilla turn arguments. */
@Mixin(MouseHandler.class)
public abstract class NativeHelmMouseMixin {
    @WrapOperation(method="turnPlayer",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void starfold$mouse(LocalPlayer player,double x,double y,Operation<Void> original){boolean flight=NativeSpaceAttitudeClient.mouse(x,y);original.call(player,flight?0d:x,flight?0d:y);}
}
