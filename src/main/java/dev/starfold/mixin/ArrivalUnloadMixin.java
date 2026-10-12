package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.dev.ArrivalProbe;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class ArrivalUnloadMixin {
    @WrapMethod(method = "save")
    private boolean starfold$arrivalSave(ChunkAccess chunk, Operation<Boolean> original) {
        long start = ArrivalProbe.start();
        boolean saved = original.call(chunk);
        if (saved)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE ARRIVAL_SAVE wall {} column {} status {} ns {} stack {}",
                            System.currentTimeMillis(),
                            chunk.getPos(),
                            chunk.getPersistedStatus(),
                            System.nanoTime() - start,
                            java.util.Arrays.toString(Thread.currentThread().getStackTrace()));
        return saved;
    }

    @WrapMethod(method = "processUnloads")
    private void starfold$arrivalUnloads(BooleanSupplier time, Operation<Void> original) {
        long start = ArrivalProbe.start();
        try {
            original.call(time);
        } finally {
            ArrivalProbe.end("unload", start);
        }
    }
}
