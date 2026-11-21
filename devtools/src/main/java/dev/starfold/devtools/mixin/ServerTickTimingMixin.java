package dev.starfold.devtools.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class ServerTickTimingMixin {
    @Shadow private ProfilerFiller profiler;
    @Shadow public abstract int getTickCount();
    @Unique private static final boolean starfold$TIME_TICKS = Boolean.getBoolean("starfold.traceLongTicks");
    @Unique private ActiveProfiler starfold$tickProfile;

    @Inject(method = "startMetricsRecordingTick", at = @At("RETURN"))
    private void starfold$startTickTiming(CallbackInfo ci) {
        if (!starfold$TIME_TICKS) return;
        starfold$tickProfile = new ActiveProfiler(System::nanoTime, this::getTickCount, false);
        starfold$tickProfile.startTick();
        profiler = ProfilerFiller.tee(profiler, starfold$tickProfile);
    }

    @Inject(method = "endMetricsRecordingTick", at = @At("RETURN"))
    private void starfold$reportLongTick(CallbackInfo ci) {
        if (starfold$tickProfile == null) return;
        var tick = starfold$tickProfile.getEntry("root\u001etick");
        var wait = starfold$tickProfile.getEntry("root\u001enextTickWait");
        if (tick.getDuration() >= 100_000_000L || wait.getDuration() >= 100_000_000L) {
            com.mojang.logging.LogUtils.getLogger().info("Long server tick {} ended at {} ns, tick={} ms, wait={} ms\n{}",
                    getTickCount(), System.nanoTime(), tick.getDuration() / 1e6, wait.getDuration() / 1e6,
                    starfold$tickProfile.getResults().getProfilerResults());
        }
        starfold$tickProfile = null;
    }
}
