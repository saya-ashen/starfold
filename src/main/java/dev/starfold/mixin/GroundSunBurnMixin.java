package dev.starfold.mixin;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundDaylight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The undead burn under a planet's own sun: where it is day for them, whatever the level's clock says. */
@Mixin(Mob.class)
public abstract class GroundSunBurnMixin {
    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void starfold$planetDay(CallbackInfoReturnable<Boolean> ci) {
        var mob = (Mob) (Object) this;
        if (!(mob.level() instanceof ServerLevel level)
                || !PlanetLevels.is(level)
                || !PlanetSystem.shared(level)
                || !PlanetAccess.of(level).inChart(mob.blockPosition())) return;
        var eyes = BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ());
        // As vanilla has it at full daylight: a chance of one in twenty-five each tick, dry and
        // under open sky.
        ci.setReturnValue(
                !GroundDaylight.sunless(level, eyes)
                        && GroundDaylight.day(level, eyes)
                        && mob.getRandom().nextFloat() * 30 < 1.2f
                        && !mob.isInWaterRainOrBubble()
                        && !mob.isInPowderSnow
                        && !mob.wasInPowderSnow
                        && level.canSeeSky(eyes));
    }
}
