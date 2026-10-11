package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Direct native chunk edits invalidate derived coverage too (not only Level.setBlock callers). */
@Mixin(LevelChunk.class)
public abstract class NativeGroundSurfaceChunkMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void starfold$nativeChanged(BlockPos pos,BlockState state,boolean moved,CallbackInfoReturnable<BlockState> ci){var chunk=(LevelChunk)(Object)this;if(ci.getReturnValue()!=null&&chunk.getLevel() instanceof ServerLevel level){NativeSpaceSpeedServer.changed(level,pos);if(!NativeGroundServer.is(level))return;if(NativePlanetAccess.of(level).key(chunk.getPos().x,chunk.getPos().z)>=0)NativeGroundSurfaceIndex.at(level,chunk.getPos().x,chunk.getPos().z).changed(chunk);NativeGroundViewServer.changed(level,pos,state);}}
}
