package dev.starfold.mixin;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.starfold.ship.HullBottom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both sides: edits, full plot chunks, client bounds sync and unload invalidate collision bottoms. */
@Mixin(value = LevelPlot.class, remap = false)
public abstract class HullBottomCacheMixin {
    @Inject(method = "onBlockChange", at = @At("RETURN"), remap = false)
    private void starfold$blockChanged(BlockPos pos, BlockState state, CallbackInfo ci) {
        HullBottom.invalidate(((LevelPlot) (Object) this).getSubLevel());
    }

    @Inject(method = "addChunkHolder", at = @At("RETURN"), remap = false)
    private void starfold$chunkAdded(ChunkPos pos, PlotChunkHolder holder, boolean lighting, CallbackInfo ci) {
        HullBottom.invalidate(((LevelPlot) (Object) this).getSubLevel());
    }

    @Inject(method = "setBoundingBox", at = @At("RETURN"), remap = false)
    private void starfold$boundsChanged(BoundingBox3ic bounds, CallbackInfo ci) {
        HullBottom.invalidate(((LevelPlot) (Object) this).getSubLevel());
    }

    @Inject(method = "onRemove", at = @At("RETURN"), remap = false)
    private void starfold$removed(CallbackInfo ci) {
        HullBottom.invalidate(((LevelPlot) (Object) this).getSubLevel());
    }
}
