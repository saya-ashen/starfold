package dev.starfold.mixin;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import dev.starfold.ship.SableShipStorageAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sable catches write failures inside its void same-address save method. */
@Mixin(value = SubLevelStorage.class, remap = false)
public abstract class SableShipWriteMixin implements SableShipStorageAccess {
    @Unique private boolean starfold$written;

    @Inject(
            method =
                    "attemptSaveSubLevel(Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)V",
            at = @At("HEAD"),
            remap = false)
    private void starfold$begin(
            GlobalSavedSubLevelPointer pointer, SubLevelData data, CallbackInfo ci) {
        starfold$written = false;
    }

    @Inject(
            method =
                    "attemptSaveSubLevel(Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/sublevel/storage/region/SubLevelStorageFile;write(ILnet/minecraft/nbt/CompoundTag;)V",
                            shift = At.Shift.AFTER,
                            remap = false),
            remap = false)
    private void starfold$written(
            GlobalSavedSubLevelPointer pointer, SubLevelData data, CallbackInfo ci) {
        starfold$written = true;
    }

    @Override
    public boolean starfold$writeSucceeded() {
        return starfold$written;
    }
}
