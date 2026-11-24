package dev.starfold.compat.mekanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The orphan traversal queues storage addresses so its next step starts in the right chart. */
@Pseudo
@Mixin(targets = "mekanism.common.lib.transmitter.TransmitterNetworkRegistry$OrphanPathFinder", remap = false)
public abstract class MekanismOrphanPathMixin {
    @Shadow @Final private Level world;

    @WrapOperation(method = "iterate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;setWithOffset(Lnet/minecraft/core/Vec3i;Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos$MutableBlockPos;"))
    private BlockPos.MutableBlockPos starfold$ownerNeighbour(BlockPos.MutableBlockPos scratch,
            Vec3i source, Direction side, Operation<BlockPos.MutableBlockPos> original) {
        var pos = original.call(scratch, source, side);
        if (PlanetLevels.canonical(world)) {
            var local = PlanetAccess.of(world).localBlock(pos);
            if (local != null) pos.set(local.address().owner());
        }
        return pos;
    }
}
