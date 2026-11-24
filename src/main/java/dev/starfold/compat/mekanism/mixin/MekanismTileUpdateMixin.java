package dev.starfold.compat.mekanism.mixin;

import dev.starfold.ground.ImageStream;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mekanism.common.tile.base.TileEntityUpdateable", remap = false)
public abstract class MekanismTileUpdateMixin {
    @Inject(method = "sendUpdatePacket(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("HEAD"), cancellable = true)
    private void starfold$sendToImageViewers(BlockEntity trackingTile, CallbackInfo ci) {
        var tile = (BlockEntity) (Object) this;
        if (trackingTile != tile || tile.isRemoved() || !(tile.getLevel() instanceof ServerLevel level)
                || !PlanetLevels.canonical(level)) return;
        var pos = tile.getBlockPos();
        if (!PlanetAccess.of(level).inChart(pos)) return;
        // Mekanism's tracking gate uses storage chunks, which an observer across a seam never tracks.
        ImageStream.blockEntityChanged(level, pos);
        ci.cancel();
    }
}
