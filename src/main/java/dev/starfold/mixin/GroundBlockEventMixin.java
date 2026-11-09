package dev.starfold.mixin;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ServerLevel.class)
public abstract class GroundBlockEventMixin {
    @ModifyVariable(method = "blockEvent", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BlockPos starfold$nativeEventOwner(BlockPos pos) {
        var level = (ServerLevel) (Object) this;
        var access = PlanetAccess.of(level);
        return PlanetLevels.is(level) && access.inChart(pos) ? access.address(pos).owner() : pos;
    }

}
