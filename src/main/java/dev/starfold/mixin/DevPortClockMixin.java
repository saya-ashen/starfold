package dev.starfold.mixin;
import dev.starfold.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=NativeGroundClient.class,remap=false)
public abstract class DevPortClockMixin {
    @Inject(method="time",at=@At("RETURN"),cancellable=true)
    private static void time(CallbackInfoReturnable<Double> ci){if(DevPortProbe.enabled()&&DevPortProbe.sky!=null)ci.setReturnValue(DevPortProbe.sky);}
}
