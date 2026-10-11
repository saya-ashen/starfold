package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;

/** Keep vanilla callback ordering and counts; transport relative source and receiver together. */
@Mixin(NeighborUpdater.class)
public interface NativeGroundTopologyNeighborMixin {
    @WrapMethod(method="executeUpdate")
    private static void starfold$nativeNotify(Level level,BlockState state,BlockPos target,Block block,BlockPos source,boolean moved,Operation<Void> original){
        var access=NativePlanetAccess.of(level);if(!NativeGroundServer.is(level)||level.isClientSide||!access.inChart(target)){original.call(level,state,target,block,source,moved);return;}
        var n=access.notification(target,source);original.call(level,level.getBlockState(n.target()),n.target(),block,n.source(),moved);
    }
    @WrapMethod(method="executeShapeUpdate")
    private static void starfold$nativeShape(LevelAccessor accessor,Direction direction,BlockState neighbor,BlockPos target,BlockPos source,int flags,int limit,Operation<Void> original){
        if(!(accessor instanceof Level level)||!NativeGroundServer.is(level)||level.isClientSide||!NativePlanetAccess.of(level).inChart(target)){original.call(accessor,direction,neighbor,target,source,flags,limit);return;}
        var n=NativePlanetAccess.of(level).notification(target,source);original.call(accessor,ChartOrientation.direction(direction,n.turns()),ChartOrientation.state(neighbor,n.turns()),n.target(),n.source(),flags,limit);
    }
}
