package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.planet.PlanetLevels;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.IntSupplier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.world.level.chunk.status.ChunkStatusTasks")
abstract class ChunkFullConversionMixin {
    @WrapOperation(method = "lambda$full$3", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"))
    private static ChunkTaskPriorityQueueSorter.Message<Runnable> starfold$traceDispatch(Runnable task, long key,
            IntSupplier level, Operation<ChunkTaskPriorityQueueSorter.Message<Runnable>> original, WorldGenContext context) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null || !PlanetLevels.is(context.level())) return original.call(task, key, level);
        trace.record(ChunkWorkDiagnostics.Stage.FULL_CONVERSION_SUBMITTED, key, System.nanoTime(), 0);
        return ChunkTaskPriorityQueueSorter.<Runnable>message(ack -> {
            trace.record(ChunkWorkDiagnostics.Stage.FULL_CONVERSION_DISPATCHED, key, System.nanoTime(), 0);
            return () -> {
                task.run();
                ack.tell(Unit.INSTANCE);
            };
        }, key, level);
    }

    @WrapMethod(method = "lambda$full$2")
    private static ChunkAccess starfold$measureConversion(ChunkAccess chunk, WorldGenContext context,
            GenerationChunkHolder holder, Operation<ChunkAccess> original) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null || !PlanetLevels.is(context.level())) return original.call(chunk, context, holder);
        long start = System.nanoTime();
        try {
            return original.call(chunk, context, holder);
        } finally {
            trace.record(ChunkWorkDiagnostics.Stage.FULL_CONVERSION, chunk.getPos().toLong(), start,
                    System.nanoTime() - start);
        }
    }
}
