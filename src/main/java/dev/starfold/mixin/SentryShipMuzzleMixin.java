package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sentry 1.3.7 aims from its projected muzzle but relocates kinetic shooters to a different,
 * plot-space muzzle (including an inverted pitch offset). Keep aim and shot origins identical.
 * No replacement gun logic, damage, range or ammo rules; ordinary land turrets are untouched. */
@Pseudo
@Mixin(targets = "euphy.upo.sentrymechanicalarm.content.SentryArmBlockEntity", remap = false)
public abstract class SentryShipMuzzleMixin {
    @Shadow
    public abstract boolean isInSableSubLevel();

    @Shadow
    public abstract Vec3 getActualMuzzlePos();

    @ModifyExpressionValue(
            method = "fireGun",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Leuphy/upo/sentrymechanicalarm/util/SentryFakePlayer;getMuzzlePosition(Leuphy/upo/sentrymechanicalarm/content/SentryArmBlockEntity;FFD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 starfold$projectedShotOrigin(Vec3 original) {
        return isInSableSubLevel() ? getActualMuzzlePos() : original;
    }

    /** At the vertical pole yaw is undefined: millimetres of idle pose drift must not prevent
     * the <1 degree yaw firing gate from settling. Pitch still tracks the real target. */
    @Inject(method = "calculateAngleBetween", at = @At("RETURN"), cancellable = true)
    private void starfold$verticalYaw(Vec3 from, Vec3 to, CallbackInfoReturnable<Vec2> cir) {
        if (isInSableSubLevel() && to.subtract(from).horizontalDistanceSqr() < 0.0025) {
            cir.setReturnValue(new Vec2(0.0F, cir.getReturnValue().y));
        }
    }
}
