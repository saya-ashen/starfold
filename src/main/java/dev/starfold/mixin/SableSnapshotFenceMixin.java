package dev.starfold.mixin;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.starfold.ship.SableCarryClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Late UDP snapshots still use the old observer labels; never interpolate them across a carry. */
@Mixin(value = SubLevelSnapshotInterpolator.class, remap = false)
public abstract class SableSnapshotFenceMixin {
    @Inject(method = "receiveSnapshot", at = @At("HEAD"), cancellable = true, remap = false)
    private void starfold$carryFence(int tick, Pose3dc pose, CallbackInfo ci) {
        if (!SableCarryClient.accepts((SubLevelSnapshotInterpolator) (Object) this, tick))
            ci.cancel();
    }
}
