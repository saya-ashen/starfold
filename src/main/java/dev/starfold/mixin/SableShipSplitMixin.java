package dev.starfold.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.ship.GasShipDamage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The only moment Sable names a split piece's source; it clears the link on a later tracking tick. */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableShipSplitMixin {
    @Inject(
            method =
                    "setSplitFrom(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/companion/math/Pose3d;)V",
            at = @At("HEAD"),
            remap = false)
    private void starfold$split(ServerSubLevel source, Pose3d pose, CallbackInfo ci) {
        GasShipDamage.split((ServerSubLevel) (Object) this, source);
    }
}
