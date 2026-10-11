package dev.starfold.mixin;

import dev.starfold.NativeWeatherClient;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client-only mixin: inherited weather getters also feed precipitation and sky brightness. */
@Mixin(Level.class)
public abstract class NativeWeatherLevelMixin {
    @Inject(method={"getRainLevel","getThunderLevel"},at=@At("HEAD"),cancellable=true)
    private void starfold$weather(float partial,CallbackInfoReturnable<Float> ci){var level=(Level)(Object)this;if(level.isClientSide&&NativeWeatherClient.absent(level))ci.setReturnValue(0f);}
}
