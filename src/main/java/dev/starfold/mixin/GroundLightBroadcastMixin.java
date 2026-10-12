package dev.starfold.mixin;

import dev.starfold.ground.GroundServer;
import dev.starfold.ground.ImageStream;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

/** Image chunks are lit from their owner: note which owners' light changed before vanilla clears the flags. */
@Mixin(ChunkHolder.class)
public abstract class GroundLightBroadcastMixin {
    @Shadow @Final private BitSet skyChangedLightSectionFilter;
    @Shadow @Final private BitSet blockChangedLightSectionFilter;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void starfold$nativeLight(LevelChunk chunk, CallbackInfo ci) {
        if ((!skyChangedLightSectionFilter.isEmpty() || !blockChangedLightSectionFilter.isEmpty())
                && chunk.getLevel() instanceof ServerLevel level
                && GroundServer.is(level)) ImageStream.lit(level, chunk.getPos());
    }
}
