package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The undead burn under a planet's own sun: where it is day for them, whatever the level's clock says. */
@Mixin(Mob.class)
public abstract class NativeGroundSunBurnMixin {
    @Inject(method="isSunBurnTick",at=@At("HEAD"),cancellable=true)
    private void starfold$planetDay(CallbackInfoReturnable<Boolean> ci){
        var mob=(Mob)(Object)this;if(!(mob.level() instanceof ServerLevel level)||!NativeGroundServer.is(level)||!NativePlanetSystem.shared(level)||!NativePlanetAccess.of(level).inChart(mob.blockPosition()))return;
        var eyes=BlockPos.containing(mob.getX(),mob.getEyeY(),mob.getZ());
        // As vanilla has it at full daylight: a chance of one in twenty-five each tick, dry and under open sky.
        ci.setReturnValue(!NativeGroundDaylight.sunless(level,eyes)&&NativeGroundDaylight.day(level,eyes)&&mob.getRandom().nextFloat()*30<1.2f&&!mob.isInWaterRainOrBubble()&&!mob.isInPowderSnow&&!mob.wasInPowderSnow&&level.canSeeSky(eyes));
    }
}
