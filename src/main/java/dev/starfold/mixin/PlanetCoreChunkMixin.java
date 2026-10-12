package dev.starfold.mixin;

import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetCoreBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Guard the owner chunk, since assembly and other direct writers never call Level.setBlock. */
@Mixin(LevelChunk.class)
public abstract class PlanetCoreChunkMixin {
    @Inject(
            method =
                    "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true)
    private void starfold$coreWrite(
            BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> ci) {
        var chunk = (LevelChunk) (Object) this;
        var level = chunk.getLevel();
        if (level.isClientSide) return;
        // Sable copies into the plot before trying to remove the source. Refusing only removal
        // duplicates the core.
        if (state.is(PlanetCoreBlock.IMMUTABLE)) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container != null && container.getPlot(chunk.getPos()) != null) {
                ci.setReturnValue(null);
                return;
            }
        }
        if (!GroundServer.is(level)) return;
        var old = chunk.getBlockState(pos);
        if (old.is(PlanetCoreBlock.IMMUTABLE) && old != state) ci.setReturnValue(null);
    }
}
