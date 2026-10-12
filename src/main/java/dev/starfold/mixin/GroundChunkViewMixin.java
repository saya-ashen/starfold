package dev.starfold.mixin;

import dev.starfold.ground.ChunkViews;
import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Halo chunk coordinates resolve to live views of their canonical owners instead of loading the empty storage
 * gap there. Loading requests load the owners; non-loading requests only inspect already available owners. */
@Mixin(ServerChunkCache.class)
public abstract class GroundChunkViewMixin {
    @Shadow @Final ServerLevel level;
    @Shadow @Final Thread mainThread;

    private boolean starfold$ghost(int x, int z) {
        return GroundServer.is(level) && ChunkViews.ghost(PlanetAccess.of(level), x, z);
    }

    @Inject(
            method =
                    "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true)
    private void starfold$viewChunk(
            int x,
            int z,
            ChunkStatus status,
            boolean load,
            CallbackInfoReturnable<ChunkAccess> ci) {
        // Off-thread calls are re-posted to the main thread by vanilla and land here again.
        if (Thread.currentThread() != mainThread || !starfold$ghost(x, z)) return;
        var cache = (ServerChunkCache) (Object) this;
        var columns = ChunkViews.columns(PlanetAccess.of(level), x, z);
        // FULL,false can still schedule and wait for a ticketed owner. A missing owner must instead
        // let
        // the caller use its unloaded-chunk fallback (e.g. LevelReader's uncached biome lookup).
        boolean ready =
                ChunkViews.ready(
                        columns,
                        load,
                        owner ->
                                cache.getChunk(
                                                ChunkPos.getX(owner),
                                                ChunkPos.getZ(owner),
                                                ChunkStatus.FULL,
                                                true)
                                        != null,
                        owner ->
                                cache.getChunkNow(ChunkPos.getX(owner), ChunkPos.getZ(owner))
                                        != null);
        ci.setReturnValue(ready ? ChunkViews.view(level, x, z) : null);
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void starfold$viewNow(int x, int z, CallbackInfoReturnable<LevelChunk> ci) {
        if (Thread.currentThread() != mainThread || !starfold$ghost(x, z)) return;
        var cache = (ServerChunkCache) (Object) this;
        var columns = ChunkViews.columns(PlanetAccess.of(level), x, z);
        ci.setReturnValue(
                ChunkViews.ready(
                                level,
                                columns,
                                o -> cache.getChunkNow(ChunkPos.getX(o), ChunkPos.getZ(o)) != null)
                        ? ChunkViews.view(level, x, z)
                        : null);
    }

    @Inject(method = "getChunkForLighting", at = @At("HEAD"), cancellable = true)
    private void starfold$viewLight(int x, int z, CallbackInfoReturnable<LightChunk> ci) {
        if (!starfold$ghost(x, z)) return;
        var columns = ChunkViews.columns(PlanetAccess.of(level), x, z);
        ci.setReturnValue(ChunkViews.ready(level, columns) ? ChunkViews.view(level, x, z) : null);
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void starfold$viewPresent(int x, int z, CallbackInfoReturnable<Boolean> ci) {
        if (!starfold$ghost(x, z)) return;
        // Owner tickets alone are not readable chunks: isLoaded/hasChunkAt guard lava and other
        // block
        // reads that would otherwise wait for generation after being told the ghost was present.
        ci.setReturnValue(
                ChunkViews.ready(level, ChunkViews.columns(PlanetAccess.of(level), x, z)));
    }
}
