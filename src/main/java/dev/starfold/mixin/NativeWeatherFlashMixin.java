package dev.starfold.mixin;

import dev.starfold.NativeWeatherClient;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class NativeWeatherFlashMixin {
    @Inject(method="getSkyFlashTime",at=@At("HEAD"),cancellable=true)
    private void starfold$flash(CallbackInfoReturnable<Integer> ci){if(NativeWeatherClient.absent((ClientLevel)(Object)this))ci.setReturnValue(0);}
}
