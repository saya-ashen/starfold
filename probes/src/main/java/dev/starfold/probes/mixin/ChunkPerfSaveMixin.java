package dev.starfold.probes.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in wall time includes chunk serialization, IO waits and mod save hooks. */
@Mixin(ServerLevel.class)
public abstract class ChunkPerfSaveMixin {
    @Unique private long starfold$saveStart;

    @Inject(method = "save", at = @At("HEAD"))
    private void starfold$saveStart(ProgressListener progress, boolean flush, boolean skip, CallbackInfo ci) {
        if (!Boolean.getBoolean("starfold.chunkPerf")) return;
        starfold$saveStart = System.nanoTime();
        org.slf4j.LoggerFactory.getLogger("starfold-chunk-perf").info("PROBE PERF save-start dim {} flush {} skip {}",
                ((ServerLevel) (Object) this).dimension().location(), flush, skip);
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void starfold$saveEnd(ProgressListener progress, boolean flush, boolean skip, CallbackInfo ci) {
        if (!Boolean.getBoolean("starfold.chunkPerf")) return;
        org.slf4j.LoggerFactory.getLogger("starfold-chunk-perf").info("PROBE PERF save-end dim {} ms {}",
                ((ServerLevel) (Object) this).dimension().location(), (System.nanoTime() - starfold$saveStart) / 1e6);
    }
}
