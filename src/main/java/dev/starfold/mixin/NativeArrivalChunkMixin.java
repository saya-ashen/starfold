package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.NativeArrivalProbe;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerChunkCache.class)
public abstract class NativeArrivalChunkMixin {
    @WrapMethod(method="getChunk")
    private ChunkAccess starfold$arrivalChunk(int x,int z,ChunkStatus status,boolean load,Operation<ChunkAccess> original){if(!NativeArrivalProbe.ENABLED)return original.call(x,z,status,load);long start=NativeArrivalProbe.start();try{return original.call(x,z,status,load);}finally{NativeArrivalProbe.chunk(start,x,z,load);}}
    @WrapMethod(method="move")
    private void starfold$arrivalMove(ServerPlayer player,Operation<Void> original){long start=NativeArrivalProbe.start();try{original.call(player);}finally{NativeArrivalProbe.end("chunkMove",start);}}
}
