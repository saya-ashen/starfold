package dev.starfold.mixin;

import dev.starfold.ship.SableBoarding;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class SablePlayerSaveMixin {
    @Inject(method = "saveWithoutId", at = @At("HEAD"))
    private void starfold$saveAboard(CompoundTag tag, CallbackInfoReturnable<CompoundTag> ci) {
        if ((Object) this instanceof ServerPlayer player) SableBoarding.save(player);
    }
}
