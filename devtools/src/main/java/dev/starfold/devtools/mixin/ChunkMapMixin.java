package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.ChunkWorkDiagnostics.Stage;
import dev.starfold.planet.PlanetLevels;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.Unit;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntFunction;

@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @WrapOperation(method = "runGenerationTask", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Lnet/minecraft/server/level/GenerationChunkHolder;Ljava/lang/Runnable;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"))
    private ChunkTaskPriorityQueueSorter.Message<Runnable> starfold$observeGenerationDispatch(
            GenerationChunkHolder holder, Runnable task,
            Operation<ChunkTaskPriorityQueueSorter.Message<Runnable>> original) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null || !PlanetLevels.is(level)) return original.call(holder, task);
        long key = holder.getPos().toLong();
        long queued = System.nanoTime();
        return ChunkTaskPriorityQueueSorter.<Runnable>message(holder, ack -> {
            long dispatched = System.nanoTime();
            trace.record(Stage.GENERATION_SORTER_QUEUE, key, queued, dispatched - queued);
            trace.generationLayer(Stage.GENERATION_DISPATCH_LEVEL, key, holder.getQueueLevel(), null, dispatched);
            return () -> {
                trace.record(Stage.GENERATION_WORKER_QUEUE, key, dispatched, System.nanoTime() - dispatched);
                task.run();
                ack.tell(Unit.INSTANCE);
            };
        });
    }

    @WrapOperation(method = "scheduleChunkLoad", at = @At(value = "INVOKE", target =
            "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkAccess> starfold$observeEmpty(CompletableFuture<?> read,
            Function<?, ?> load, Executor executor, Operation<CompletableFuture<ChunkAccess>> original,
            @Local(argsOnly = true) ChunkPos pos) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null || !PlanetLevels.is(level)) return original.call(read, load, executor);
        long key = pos.toLong();
        long start = System.nanoTime();
        Executor timed = command -> {
            long queued = System.nanoTime();
            trace.record(Stage.EMPTY_READ, key, start, queued - start);
            executor.execute(() -> {
                long began = System.nanoTime();
                trace.record(Stage.EMPTY_MAIN_QUEUE, key, queued, began - queued);
                command.run();
                trace.record(Stage.EMPTY_MAIN_WORK, key, began, System.nanoTime() - began);
            });
        };
        return original.call(read, load, timed);
    }

    @WrapOperation(method = "prepareAccessibleChunk", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ChunkMap;getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<List<ChunkAccess>>> starfold$requestFull(ChunkMap map,
            ChunkHolder holder, int radius, IntFunction<?> status,
            Operation<CompletableFuture<ChunkResult<List<ChunkAccess>>>> original) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace != null && PlanetLevels.is(level))
            trace.record(Stage.FULL_REQUEST, holder.getPos().toLong(), System.nanoTime(), 0);
        return original.call(map, holder, radius, status);
    }

    @WrapOperation(method = "prepareAccessibleChunk", at = @At(value = "INVOKE", target =
            "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<LevelChunk>> starfold$observeFull(
            CompletableFuture<ChunkResult<List<ChunkAccess>>> dependencies,
            Function<ChunkResult<List<ChunkAccess>>, ChunkResult<LevelChunk>> callback, Executor executor,
            Operation<CompletableFuture<ChunkResult<LevelChunk>>> original, @Local(argsOnly = true) ChunkHolder holder) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null || !PlanetLevels.is(level)) return original.call(dependencies, callback, executor);
        long key = holder.getPos().toLong();
        Executor timed = command -> {
            long queued = System.nanoTime();
            trace.record(Stage.FULL_DEPENDENCIES_READY, key, queued, 0);
            executor.execute(() -> {
                long start = System.nanoTime();
                trace.record(Stage.FULL_CALLBACK, key, start, start - queued);
                command.run();
            });
        };
        return original.call(dependencies, (Function<ChunkResult<List<ChunkAccess>>, ChunkResult<LevelChunk>>) result -> {
            var ready = callback.apply(result);
            if (ready.isSuccess()) trace.record(Stage.FULL_READY, key, System.nanoTime(), 0);
            return ready;
        }, timed);
    }
}
