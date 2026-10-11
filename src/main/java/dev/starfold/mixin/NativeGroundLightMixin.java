package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native block/hand skylight uses the same physical Sun; vanilla block light stays intact. */
@Mixin(ClientLevel.class)
public abstract class NativeGroundLightMixin {
    @Inject(method="getSkyDarken",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeSun(float partial,CallbackInfoReturnable<Float> ci){if(NativeSpaceClient.active()){ci.setReturnValue((float)(.2+.8*NativeSpaceClient.sunlight()));return;}if(NativeGroundServer.is((ClientLevel)(Object)this)&&NativeGroundClient.ready())ci.setReturnValue((float)(.2+.8*(NativeGroundFlightClient.controlled()?NativeGroundClient.sunlight():GroundSolar.daylight(NativeGroundClient.elevation()))));}
}
