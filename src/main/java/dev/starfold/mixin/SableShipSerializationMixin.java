package dev.starfold.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.starfold.ship.ShipRecords;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/** Captures the pose Sable will restore, before natural unload drops the live handle. */
@Mixin(value = SubLevelSerializer.class, remap = false)
public abstract class SableShipSerializationMixin {
    @Inject(
            method =
                    "toData(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ljava/util/List;)Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;",
            at = @At("RETURN"),
            remap = false)
    private static void starfold$serialized(
            ServerSubLevel hull, List<UUID> dependencies, CallbackInfoReturnable<SubLevelData> ci) {
        ShipRecords.serialized(hull, ci.getReturnValue());
    }
}
