package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.ChunkWorkDiagnostics.Stage;
import dev.starfold.devtools.GenerationLayerObservation;
import dev.starfold.planet.PlanetLevels;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkGenerationTask.class)
abstract class GenerationLayerMixin {
    @Shadow @Final private GeneratingChunkMap chunkMap;
    @Shadow @Final private ChunkPos pos;
    @Unique private GenerationLayerObservation starfold$layer;

    @WrapMethod(method = "scheduleLayer")
    private void starfold$observeLayer(ChunkStatus status, boolean generate, Operation<Void> original) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace != null && status.getIndex() <= ChunkStatus.NOISE.getIndex()
                && chunkMap instanceof GenerationLevelAccess access && PlanetLevels.is(access.starfold$generationLevel())) {
            long now = System.nanoTime();
            long center = pos.toLong();
            if (starfold$layer != null && starfold$layer.trace == trace) starfold$layer.resume(now);
            int index = status.getIndex();
            trace.generationLayer(Stage.GENERATION_LAYER_START, center, index, null, now);
            starfold$layer = status == ChunkStatus.NOISE ? null : new GenerationLayerObservation(trace, center, index);
        } else {
            starfold$layer = null;
        }
        original.call(status, generate);
    }

    @WrapOperation(method = "scheduleChunkInLayer", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/GenerationChunkHolder;applyStep(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<ChunkAccess>> starfold$observeDependency(GenerationChunkHolder holder,
            ChunkStep step, GeneratingChunkMap map, StaticCache2D<GenerationChunkHolder> cache,
            Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        var future = original.call(holder, step, map, cache);
        var layer = starfold$layer;
        if (layer != null && !future.isDone()) layer.watch(future, holder.getPos().toLong());
        return future;
    }
}
