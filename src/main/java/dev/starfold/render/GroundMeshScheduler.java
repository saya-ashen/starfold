package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.lod.VoxelMesher.Quad;

import java.util.List;
import java.util.concurrent.*;

/** CPU executor for existing remote planet meshes. Ground nodes use their own scheduler. */
final class GroundMeshScheduler extends ThreadPoolExecutor {
    static final GroundMeshScheduler shared =
            new GroundMeshScheduler(
                    Math.clamp(Runtime.getRuntime().availableProcessors() / 3, 1, 6));
    static double lodFov = 70;

    private GroundMeshScheduler(int threads) {
        super(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    var thread = new Thread(r, "starfold-remote-mesh");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    Future<List<Quad>> farMesh(PlanetShape shape, int patch, int step, GroundFarMesh.Field field) {
        return submit(() -> GroundFarMesh.build(shape, patch, step, field));
    }

    static <T> T completed(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Remote patch meshing failed", failure.getCause());
        }
    }
}
