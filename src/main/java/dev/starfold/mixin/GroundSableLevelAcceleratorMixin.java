package dev.starfold.mixin;

import dev.starfold.ground.ChunkViews;
import dev.starfold.ground.GhostChunk;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sable's holder lookup and raw section reads bypass the native seam's mapped chunk view. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.util.LevelAccelerator", remap = false)
public abstract class GroundSableLevelAcceleratorMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private int minBuildHeight;
    @Shadow @Final private int maxBuildHeight;

    @Inject(
            method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void starfold$seamChunk(int x, int z, CallbackInfoReturnable<LevelChunk> ci) {
        if (level instanceof ServerLevel server
                && PlanetLevels.is(server)
                && ChunkViews.ghost(PlanetAccess.of(server), x, z)) {
            // Run before Sable's one-chunk cache as well as its visible-holder shortcut.
            // Creating the view does not load any canonical owner chunks.
            ci.setReturnValue(ChunkViews.view(server, x, z));
        }
    }

    @Inject(
            method =
                    "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void starfold$seamState(
            LevelChunk chunk, BlockPos pos, CallbackInfoReturnable<BlockState> ci) {
        if (chunk instanceof GhostChunk
                && level instanceof ServerLevel
                && PlanetLevels.is(level)
                && pos.getY() >= minBuildHeight
                && pos.getY() < maxBuildHeight) {
            // Ghost sections are empty; its virtual read maps and rotates the owner's state.
            // Outside Sable's captured height bounds, retain its original AIR result.
            ci.setReturnValue(chunk.getBlockState(pos));
        }
    }
}
