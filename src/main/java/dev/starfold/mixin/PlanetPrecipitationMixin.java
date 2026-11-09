package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetConditions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No snowfall, cauldron filling or precipitation callbacks. Cold-water freezing is not weather. */
@Mixin(ServerLevel.class)
public abstract class PlanetPrecipitationMixin {
    @WrapOperation(
            method = "tickPrecipitation",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean starfold$noPrecipitation(
            ServerLevel level, Operation<Boolean> original, BlockPos pos) {
        return !PlanetConditions.airless(level, pos) && original.call(level);
    }
}
