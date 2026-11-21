package dev.starfold.devtools;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.ChunkWorkDiagnostics.Stage;

import java.util.concurrent.CompletableFuture;

public final class GenerationLayerObservation {
    public final ChunkWorkDiagnostics.Listener trace;
    private final long center;
    private final int status;
    private long latest;
    private long dependency;
    private int pending;

    public GenerationLayerObservation(ChunkWorkDiagnostics.Listener trace, long center, int status) {
        this.trace = trace;
        this.center = center;
        this.status = status;
    }

    public synchronized void watch(CompletableFuture<?> future, long chunk) {
        pending++;
        future.whenComplete((value, error) -> complete(chunk, System.nanoTime()));
    }

    private synchronized void complete(long chunk, long now) {
        pending--;
        if (latest == 0 || now - latest > 0) {
            latest = now;
            dependency = chunk;
        }
    }

    public synchronized void resume(long now) {
        if (pending == 0 && latest != 0) trace.generationLayer(Stage.GENERATION_LAYER_LAST_DEPENDENCY, center, status, dependency, latest);
        trace.generationLayer(Stage.GENERATION_LAYER_RESUME, center, status, null, now);
    }
}
