package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.starfold.NativeWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.21.1 places weather lightning inside tickChunk, not a separate tickThunder method. */
@Mixin(ServerLevel.class)
public abstract class NativeWeatherThunderMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method="addFreshEntity",at=@At("HEAD"))
    private void starfold$boltProbe(net.minecraft.world.entity.Entity entity,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci){if(Boolean.getBoolean("starfold.carryProbe")&&entity instanceof net.minecraft.world.entity.LightningBolt)org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE WEATHER BOLT region {} pos {} wall {}",dev.starfold.NativeSpaceServer.in(entity),entity.position(),System.currentTimeMillis());}
    @WrapOperation(method="tickChunk",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;isRainingAt(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean starfold$thunder(ServerLevel level,BlockPos pos,Operation<Boolean> original){return !NativeWeather.absent(level,pos)&&original.call(level,pos);}
}
