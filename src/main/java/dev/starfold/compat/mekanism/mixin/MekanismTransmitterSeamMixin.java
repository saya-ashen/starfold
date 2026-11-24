package dev.starfold.compat.mekanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** A transmitter checks and refreshes the neighbouring transmitter through its owner-side port. */
@Pseudo
@Mixin(targets = "mekanism.common.content.network.transmitter.Transmitter", remap = false)
public abstract class MekanismTransmitterSeamMixin {
    @Shadow public abstract Level getLevel();
    @Shadow public abstract BlockPos getBlockPos();

    @WrapOperation(method = {"getPossibleTransmitterConnections", "getPossibleTransmitterConnection", "recheckConnections"},
            at = @At(value = "INVOKE", target = "Lmekanism/common/util/WorldUtils;getTileEntity(Ljava/lang/Class;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity starfold$loadedOwner(Class<?> type, BlockGetter world, BlockPos pos,
            Operation<BlockEntity> original) {
        if (world instanceof Level level && PlanetLevels.canonical(level)) {
            var local = PlanetAccess.of(level).localBlock(pos);
            if (local != null) pos = local.address().owner();
        }
        return original.call(type, world, pos);
    }

    @WrapOperation(method = {
            "canConnectMutual(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/entity/BlockEntity;)Z",
            "canConnectMutual(Lnet/minecraft/core/Direction;Lmekanism/common/content/network/transmitter/Transmitter;)Z",
            "recheckConnections(B)V"
        }, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;getOpposite()Lnet/minecraft/core/Direction;"))
    private Direction starfold$neighbourPort(Direction side, Operation<Direction> original) {
        var opposite = original.call(side);
        var level = getLevel();
        if (!PlanetLevels.canonical(level)) return opposite;
        var local = PlanetAccess.of(level).localBlock(getBlockPos().relative(side));
        return local == null ? opposite : local.address().ownerDirection(opposite);
    }
}
