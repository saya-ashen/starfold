package dev.starfold.compat.jade.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.starfold.ground.PacketMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "snownee.jade.impl.BlockAccessorImpl$SyncData", remap = false)
public abstract class JadeBlockRequestMixin {
    @ModifyExpressionValue(method = {"unpack", "lambda$unpack$0"}, at = @At(value = "FIELD", target =
            "Lsnownee/jade/impl/BlockAccessorImpl$SyncData;hit:Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult starfold$storageHit(BlockHitResult hit, @Local(argsOnly = true) ServerPlayer player) {
        return PacketMap.storageHit(player, hit);
    }
}
