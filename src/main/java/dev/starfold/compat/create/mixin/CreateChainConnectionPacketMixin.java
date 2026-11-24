package dev.starfold.compat.create.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;

import dev.starfold.ground.ObserverFrames;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorConnectionPacket", remap = false)
public abstract class CreateChainConnectionPacketMixin {
    private static final String APPLY = "applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;)V";

    @ModifyExpressionValue(method = APPLY, at = @At(value = "FIELD",
            target = "Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorConnectionPacket;targetPos:Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$targetInSourceChart(BlockPos target, ServerPlayer player, ChainConveyorBlockEntity source) {
        if (!PlanetLevels.canonical(player.level())) return target;
        var query = ObserverFrames.active(player) ? ObserverFrames.get(player).toStorage(target) : target;
        var access = PlanetAccess.of(player.level());
        var local = access.localBlock(query);
        if (local == null) return query;
        var pos = ((BlockEntity) (Object) source).getBlockPos();
        var projected = access.adjacentImage(pos, local.address().owner());
        return projected == null ? local.address().owner() : projected;
    }

    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/core/BlockPos;subtract(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$refundOffset(BlockPos target, Vec3i packetSource, Operation<BlockPos> original,
            ServerPlayer player, ChainConveyorBlockEntity source) {
        return original.call(target, PlanetLevels.canonical(player.level())
                ? ((BlockEntity) (Object) source).getBlockPos() : packetSource);
    }
}
