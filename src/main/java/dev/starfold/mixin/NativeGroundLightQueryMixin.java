package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockAndTintGetter.class)
public interface NativeGroundLightQueryMixin {
    @WrapMethod(method="getBrightness")
    private int starfold$nativeLight(LightLayer layer,BlockPos pos,Operation<Integer> original){
        if(!((Object)this instanceof Level level)||!NativeGroundServer.canonical(level)||!NativePlanetAccess.of(level).inChart(pos))return original.call(layer,pos);
        var owner=NativePlanetAccess.of(level).address(pos).owner();if(level.isClientSide&&!level.getChunkSource().hasChunk(owner.getX()>>4,owner.getZ()>>4)){var value=NativeGroundSeams.clientBrightness(level,owner,layer);if(value!=null)return value;}return original.call(layer,owner);
    }
}
