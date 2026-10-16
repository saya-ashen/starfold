package dev.starfold.mixin;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.starfold.ship.ShipDormancy;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ticking yesterday's columns must not resurrect a space hull at yesterday's pose. */
@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public abstract class SableShipReadinessMixin {
    @Inject(
            method =
                    "canLoadSubLevel(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void starfold$currentPlace(
            ServerLevel level, SubLevelData data, CallbackInfoReturnable<Boolean> ci) {
        if (ShipDormancy.managed(level, data.uuid())) ci.setReturnValue(false);
    }
}
