package dev.starfold.render;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One pool: initial cover, visible meshes, then cover refinement; FIFO per lane. */
final class GroundMeshScheduler extends ThreadPoolExecutor {
    static final GroundMeshScheduler shared = new GroundMeshScheduler(Math.clamp(Runtime.getRuntime().availableProcessors() / 3, 1, 6));
    static double lodFov = 70;

    static <T> T completed(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Far patch meshing failed", e.getCause());
        }
    }

    Future<java.util.List<dev.starfold.worldgen.lod.VoxelMesher.Quad>> farMesh(
            dev.starfold.chart.PlanetShape shape, int patch, int step,
            GroundFarMesh.Field field) {
        return submitRefinement(() -> GroundFarMesh.build(shape, patch, step, field));
    }

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong queuedNanos = new AtomicLong();
    private final AtomicLong averageNanos = new AtomicLong(2_000_000);
    private volatile long readyBytes;

    void readyBytes(long bytes) { readyBytes = bytes; }

    /** Admit before capturing inputs: no rejected Future, blocking, or render-thread meshing. */
    boolean canSubmit(boolean optional) {
        int threads = getCorePoolSize();
        return readyBytes < (optional ? 2L : 4L) * 1024 * 1024
                && getQueue().size() < threads * (optional ? 1 : 2)
                && queuedNanos.get() < threads * (optional ? 10_000_000L : 20_000_000L);
    }

    GroundMeshScheduler(int threads) {
        super(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                r -> {
                    var t = new Thread(r, "Starfold LOD worker");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 1);
                    return t;
                });
    }

    private final class Task<T> extends FutureTask<T> implements Comparable<Task<?>> {
        final int priority;
        final long order = sequence.getAndIncrement();
        final long estimate = averageNanos.get();
        private final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean(true);

        Task(Callable<T> work, int priority) {
            super(work);
            this.priority = priority;
            queuedNanos.addAndGet(estimate);
        }

        private void dequeued() {
            if (queued.compareAndSet(true, false)) queuedNanos.addAndGet(-estimate);
        }

        public void run() {
            dequeued();
            long start = System.nanoTime();
            super.run();
            if (!isCancelled()) {
                long elapsed = System.nanoTime() - start;
                averageNanos.updateAndGet(old -> old + (elapsed - old) / 8);
            }
        }

        public int compareTo(Task<?> other) {
            int p = Integer.compare(priority, other.priority);
            return p == 0 ? Long.compare(order, other.order) : p;
        }

        protected void done() {
            if (isCancelled()) {
                remove(this);
                dequeued();
            }
        }
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> work) {
        return new Task<>(work, 0);
    }

    protected <T> RunnableFuture<T> newTaskFor(Runnable work, T result) {
        return new Task<>(Executors.callable(work, result), 0);
    }

    /** Far coverage: one patch replaces the whole horizon, so it never waits behind resident tiles. */
    <T> Future<T> submitFirst(Callable<T> work) {
        var task = new Task<>(work, -1);
        execute(task);
        return task;
    }

    <T> Future<T> submitRefinement(Callable<T> work) {
        var task = new Task<>(work, 1);
        execute(task);
        return task;
    }

}
