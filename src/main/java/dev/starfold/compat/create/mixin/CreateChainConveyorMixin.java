package dev.starfold.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.geometry.ChartOrientation;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each link is stored as an offset in the conveyor's own chart. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity", remap = false)
public abstract class CreateChainConveyorMixin {
    @Shadow public abstract void notifyConnectedToValidate();

    @Inject(method = "clearContent", at = @At("HEAD"))
    private void starfold$validateClearedLinks(CallbackInfo ci) {
        if (PlanetLevels.canonical(((BlockEntity) (Object) this).getLevel()))
            notifyConnectedToValidate();
    }

    @WrapMethod(method = {"addConnectionTo", "removeConnectionTo"})
    private boolean starfold$connectionImage(BlockPos target, Operation<Boolean> original) {
        return original.call(starfold$image(target));
    }

    @WrapOperation(method = "propagateRotationTo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;subtract(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$propagationOffset(BlockPos target, Vec3i source, Operation<BlockPos> original) {
        return original.call(starfold$image(target), source);
    }

    @Unique
    private BlockPos starfold$image(BlockPos target) {
        var conveyor = (BlockEntity) (Object) this;
        var level = conveyor.getLevel();
        if (!PlanetLevels.canonical(level)) return target;
        var access = PlanetAccess.of(level);
        var local = access.localBlock(target);
        if (local == null) return target;
        var projected = access.adjacentImage(conveyor.getBlockPos(), local.address().owner());
        return projected == null ? target : projected;
    }

    @WrapOperation(method = {"tick", "removeInvalidConnections"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;multiply(I)Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$reciprocalOffset(BlockPos offset, int factor, Operation<BlockPos> original) {
        var reversed = original.call(offset, factor);
        var conveyor = (BlockEntity) (Object) this;
        var level = conveyor.getLevel();
        if (!PlanetLevels.canonical(level)) return reversed;
        var local = PlanetAccess.of(level).localBlock(conveyor.getBlockPos().offset(offset));
        return local == null ? reversed : ChartOrientation.offset(reversed, local.address().turns());
    }
}
