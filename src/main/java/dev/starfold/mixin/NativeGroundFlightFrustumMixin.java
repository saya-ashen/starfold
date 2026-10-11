package dev.starfold.mixin;

import dev.starfold.NativeGroundClient;
import dev.starfold.NativeGroundFlightClient;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Original flat chunk boxes cannot cull vertices after spherical projection at high altitude. */
@Mixin(Frustum.class)
public abstract class NativeGroundFlightFrustumMixin {
    @Inject(method="isVisible",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeProjectedBounds(AABB bounds,CallbackInfoReturnable<Boolean> ci){
        if(NativeGroundClient.projectedFrame()){boolean section=bounds.getXsize()==16&&bounds.getYsize()==16&&bounds.getZsize()==16;// Halo sections are real client chunks now (images of the adjacent face): draw them like any other.
        if(section&&dev.starfold.NativeGroundRenderer.replacesNative())ci.setReturnValue(false);else ci.setReturnValue(true);}
    }
}
