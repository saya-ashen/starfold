package dev.starfold.render;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Independent, versioned node builds. CPU workers never touch the tree or any GL object. */
final class GroundQuadtreeScheduler<T extends AutoCloseable> implements AutoCloseable {
    record Ticket(int node, int step, long version, long session) {}

    record Completed<T>(Ticket ticket, T mesh, Throwable failure, GroundLodBuildTrace ready) {}

    record Request<T>(Ticket ticket, double priority, BooleanSupplier current, Callable<T> build,
            Callable<T> lookup) {
        Request(Ticket ticket, double priority, BooleanSupplier current, Callable<T> build) {
            this(ticket, priority, current, build, null);
        }
    }

    private final ThreadPoolExecutor workers, readers;
    private final Set<Integer> reading = new HashSet<>();
    private final Queue<Request<T>> misses = new ConcurrentLinkedQueue<>();
    private final int readCapacity;
    private final ConcurrentLinkedQueue<Completed<T>> completedQueue =
            new ConcurrentLinkedQueue<>();
    private final Map<Integer, Ticket> active = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Set<Future<?>> finishing = new HashSet<>();
    private final int capacity;

    GroundQuadtreeScheduler(int threads) {
        capacity = Math.max(1, threads) * 2;
        int readThreads = Math.clamp(threads / 2, 1, 2);
        // Completed hits retain a slot until the next render-thread poll. Keep a bounded
        // window across that frame boundary without increasing disk-reader concurrency.
        readCapacity = Math.max(1, threads) * 4;
        readers = new ThreadPoolExecutor(readThreads, readThreads, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    var t = new Thread(r, "starfold-quadtree-cache");
                    t.setDaemon(true);
                    return t;
                });
        workers =
                new ThreadPoolExecutor(
                        Math.max(1, threads),
                        Math.max(1, threads),
                        0,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        r -> {
                            var t = new Thread(r, "starfold-quadtree");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /** Scores apply at every FOV, including ordinary wide views. */
    static double priority(
            double deltaSse, double directionDot, int readySiblings, boolean missing) {
        return (1 + 1.5 * Math.max(0, directionDot))
                * Math.max(.001, deltaSse)
                * (1 + .5 * Math.clamp(readySiblings, 0, 3))
                * (missing ? 1_000_000 : 1);
    }

    void schedule(Collection<Request<T>> demand) {
        promoteMisses();
        var ordered = new ArrayList<>(demand);
        ordered.sort(Comparator.comparingDouble((Request<T> r) -> r.priority()).reversed());
        for (var request : ordered) {
            jobs();
            boolean lookup = request.lookup() != null;
            if (lookup ? reading.size() >= readCapacity : buildJobs() >= capacity) continue;
            var ticket = request.ticket();
            if (active.containsKey(ticket.node()) || !request.current().getAsBoolean()) continue;
            active.put(ticket.node(), ticket);
            if (lookup) reading.add(ticket.node());
            execute(request, lookup);
        }
    }

    private int buildJobs() {
        return active.size() - reading.size() + finishing.size();
    }

    int nodeCapacity() { return capacity + readCapacity; }

    int readersActive() { return readers.getActiveCount(); }
    int readersQueued() { return readers.getQueue().size(); }

    /** Snapshot only inputs that can enter the cache lane in this frame. */
    int lookupSlots() {
        promoteMisses();
        return readCapacity - reading.size();
    }

    private void promoteMisses() {
        jobs();
        while (buildJobs() < capacity) {
            var request = misses.poll();
            if (request == null) break;
            reading.remove(request.ticket().node());
            execute(request, false);
        }
    }

    private void execute(Request<T> request, boolean lookup) {
        var ticket = request.ticket();
        long observed = generation.get();
        var queued = GroundLodBuildTrace.start(ticket, "worker-queue");
        (lookup ? readers : workers).execute(
                () -> {
                    GroundLodBuildTrace.finish(queued, "started", 0);
                    var working = GroundLodBuildTrace.start(ticket, "worker");
                    T mesh = null;
                    Throwable failure = null;
                    try {
                        if (observed == generation.get() && request.current().getAsBoolean())
                            mesh = (lookup ? request.lookup() : request.build()).call();
                    } catch (CancellationException cancelled) {
                        // Cooperative ticket cancellation is not a failed terrain build.
                    } catch (Throwable ex) {
                        failure = ex;
                    }
                    GroundLodBuildTrace.finish(working,
                            failure != null ? "failure" : mesh == null ? "cancelled" : "built", 0);
                    synchronized (completedQueue) {
                        if (observed != generation.get()) release(mesh);
                        else if (lookup && mesh == null && failure == null
                                && request.current().getAsBoolean()) misses.add(request);
                        else completedQueue.add(new Completed<>(ticket, mesh, failure,
                                GroundLodBuildTrace.start(ticket, "ready-wait")));
                    }
                });
    }

    Completed<T> poll() {
        promoteMisses();
        var result = completedQueue.poll();
        if (result != null) {
            active.remove(result.ticket().node(), result.ticket());
            reading.remove(result.ticket().node());
            GroundLodBuildTrace.finish(result.ready(), "polled", 0);
        }
        return result;
    }

    boolean pending(int node) {
        return active.containsKey(node);
    }

    /** Continuations share the existing pool and admission bound; callers own CPU-only inputs. */
    boolean finish(Runnable packing) {
        jobs();
        if (buildJobs() >= capacity) return false;
        finishing.add(workers.submit(packing));
        return true;
    }

    int jobs() {
        finishing.removeIf(Future::isDone);
        return active.size() + finishing.size();
    }

    int completed() {
        return completedQueue.size();
    }

    /** Reset does not wait for a running CPU job; stale native payloads release on completion. */
    void reset() {
        synchronized (completedQueue) {
            generation.incrementAndGet();
            finishing.forEach(job -> job.cancel(true));
            finishing.clear();
            workers.getQueue().clear();
            readers.getQueue().clear();
            misses.clear();
            reading.clear();
            Completed<T> result;
            while ((result = completedQueue.poll()) != null) {
                GroundLodBuildTrace.finish(result.ready(), "reset", 0);
                release(result.mesh());
            }
        }
        active.clear();
    }

    static void release(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public void close() {
        reset();
        workers.shutdown();
        readers.shutdown();
    }
}
