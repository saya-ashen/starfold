package dev.starfold.mixin;

import dev.starfold.NativeSpaceNavigationClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consume the helm mapping before vanilla queues swap-offhand, including repeats/releases. */
@Mixin(KeyboardHandler.class)
public abstract class NativeNavigationKeyMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void starfold$navigation(long window,int key,int scan,int action,int modifiers,CallbackInfo ci){if(NativeSpaceNavigationClient.key(window,key,scan,action))ci.cancel();}
}
