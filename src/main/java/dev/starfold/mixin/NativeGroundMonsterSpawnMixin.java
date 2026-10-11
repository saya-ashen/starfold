package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Monsters come out in a planet's own night: the vanilla rule, with the darkness of the place and not of the clock. */
@Mixin(Monster.class)
public abstract class NativeGroundMonsterSpawnMixin {
    @Inject(method="isDarkEnoughToSpawn",at=@At("HEAD"),cancellable=true)
    private static void starfold$planetNight(ServerLevelAccessor world,BlockPos pos,RandomSource random,CallbackInfoReturnable<Boolean> ci){
        ServerLevel level=world.getLevel();if(!NativeGroundServer.is(level)||!NativePlanetSystem.shared(level)||!NativePlanetAccess.of(level).inChart(pos))return;
        boolean sunless=NativeGroundDaylight.sunless(world,pos);
        if(!sunless&&world.getBrightness(LightLayer.SKY,pos)>random.nextInt(32)){ci.setReturnValue(false);return;}
        var type=world.dimensionType();int limit=type.monsterSpawnBlockLightLimit();if(limit<15&&world.getBrightness(LightLayer.BLOCK,pos)>limit){ci.setReturnValue(false);return;}
        if(sunless){ci.setReturnValue(true);return;} // only lamps keep them away
        int darken=level.isThundering()?Math.max(10,NativeGroundDaylight.darken(level,pos)):NativeGroundDaylight.darken(level,pos);
        ci.setReturnValue(world.getMaxLocalRawBrightness(pos,darken)<=type.monsterSpawnLightTest().sample(random));
    }
}
