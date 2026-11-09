package dev.starfold.mixin;

import dev.starfold.render.GroundClient;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Original flat chunk boxes cannot cull vertices after spherical projection at high altitude. */
@Mixin(Frustum.class)
public abstract class GroundFlightFrustumMixin {
    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeProjectedBounds(AABB bounds, CallbackInfoReturnable<Boolean> ci) {
        // Never remove native sections from the visible list on behalf of asynchronous LOD.
        // A committed exterior may replace pixels, not the section visibility/compilation input.
        if (GroundClient.projectedFrame()) ci.setReturnValue(true);
    }
}
