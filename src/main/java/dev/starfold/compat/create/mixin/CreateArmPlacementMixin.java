package dev.starfold.compat.create.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

import dev.starfold.ground.ChartTag;
import dev.starfold.ground.ObserverFrames;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.mechanicalArm.ArmPlacementPacket", remap = false)
public abstract class CreateArmPlacementMixin {
    @ModifyExpressionValue(method = "handle", at = @At(value = "FIELD",
            target = "Lcom/simibubi/create/content/kinetics/mechanicalArm/ArmPlacementPacket;pos:Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$ownerPlacement(BlockPos pos, ServerPlayer player,
            @Share("turns") LocalIntRef turns) {
        if (!PlanetLevels.canonical(player.level())) return pos;
        var frame = ObserverFrames.active(player) ? ObserverFrames.get(player) : null;
        var query = frame == null ? pos : frame.toStorage(pos);
        var local = PlanetAccess.of(player.level()).localBlock(query);
        if (local == null) return query;
        turns.set(local.address().turns() - (frame == null ? 0 : frame.turns()));
        return local.address().owner();
    }

    @ModifyExpressionValue(method = "handle", at = @At(value = "FIELD",
            target = "Lcom/simibubi/create/content/kinetics/mechanicalArm/ArmPlacementPacket;tag:Lnet/minecraft/nbt/ListTag;"))
    private ListTag starfold$ownerOffsets(ListTag points, @Share("turns") LocalIntRef turns) {
        int rotation = turns.get();
        if (Math.floorMod(rotation, 4) == 0) return points;
        var copy = points.copy();
        ChartTag.rotateOffsets(copy, "Pos", rotation);
        return copy;
    }
}
