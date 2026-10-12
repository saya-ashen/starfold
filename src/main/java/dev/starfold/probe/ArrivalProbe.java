package dev.starfold.probe;

import java.util.*;

/** Opt-in arrival census. Times are inclusive (nested categories must not be added). */
public final class ArrivalProbe {
    public static final boolean ENABLED = Boolean.getBoolean("starfold.carryProbe");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-probe");
    private static final ThreadLocal<Map<String, Long>> times =
            ThreadLocal.withInitial(LinkedHashMap::new);

    public static long start() {
        return ENABLED ? System.nanoTime() : 0;
    }

    public static void end(String name, long start) {
        if (ENABLED) times.get().merge(name, System.nanoTime() - start, Long::sum);
    }

    private record Sample(Thread thread, long start) {}

    private static volatile Sample active;
    private static boolean sampling;
    private static long gcStart;
    private static final List<java.lang.management.GarbageCollectorMXBean> collectors =
            ENABLED
                    ? java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                    : List.of();

    private static long gcTime() {
        long total = 0;
        for (var bean : collectors) total += Math.max(0, bean.getCollectionTime());
        return total;
    }

    public static void tickStart() {
        if (!ENABLED) return;
        times.get().clear();
        gcStart = gcTime();
        active = new Sample(Thread.currentThread(), System.nanoTime());
        if (!sampling) {
            sampling = true;
            Thread.ofPlatform()
                    .daemon()
                    .name("starfold-arrival-sampler")
                    .start(
                            () -> {
                                while (true) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException stop) {
                                        return;
                                    }
                                    var s = active;
                                    if (s == null) continue;
                                    var stack = s.thread().getStackTrace();
                                    if (active == s)
                                        LOG.info(
                                                "PROBE ARRIVAL_STACK wall {} start {} stack {}",
                                                System.currentTimeMillis(),
                                                s.start(),
                                                Arrays.toString(stack));
                                }
                            });
        }
    }

    public static void tickEnd(int tick, long start) {
        if (ENABLED) {
            long end = System.nanoTime();
            var s = active;
            active = null;
            times.get().put("gcPause", (gcTime() - gcStart) * 1_000_000);
            LOG.info(
                    "PROBE ARRIVAL_TICK wall {} tick {} ns {} sampleStart {} costs {}",
                    System.currentTimeMillis(),
                    tick,
                    end - start,
                    s == null ? 0 : s.start(),
                    times.get());
        }
    }

    public static void chunk(long start, int x, int z, boolean load) {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - start;
        times.get().merge("chunk", elapsed, Long::sum);
        if (load && elapsed >= 1_000_000) {
            var stack = Thread.currentThread().getStackTrace();
            boolean sable =
                    Arrays.stream(stack)
                            .anyMatch(s -> s.getClassName().contains("PhysicsChunkTicketManager"));
            times.get().merge(sable ? "sableChunk" : "otherSlowChunk", elapsed, Long::sum);
            LOG.info(
                    "PROBE ARRIVAL_CHUNK wall {} x {} z {} ns {} sable {} stack {}",
                    System.currentTimeMillis(),
                    x,
                    z,
                    elapsed,
                    sable,
                    Arrays.toString(stack));
        }
    }

    private static final Map<net.minecraft.server.level.ServerLevel, Map<Long, Long>> held =
            new WeakHashMap<>();

    public static void seamHeld(
            net.minecraft.server.level.ServerLevel level, long chunk, boolean ready) {
        if (!ENABLED) return;
        var chunks = held.computeIfAbsent(level, l -> new HashMap<>());
        long now = System.nanoTime();
        if (!ready) {
            if (chunks.putIfAbsent(chunk, now) == null)
                LOG.info(
                        "PROBE SEAM_HOLD tick {} chunk {}",
                        level.getGameTime(),
                        new net.minecraft.world.level.ChunkPos(chunk));
        } else {
            var start = chunks.remove(chunk);
            if (start != null)
                LOG.info(
                        "PROBE SEAM_RELEASE tick {} chunk {} ns {} remaining {}",
                        level.getGameTime(),
                        new net.minecraft.world.level.ChunkPos(chunk),
                        now - start,
                        chunks.size());
        }
    }

    public static net.minecraft.core.BlockPos tickingBlock;

    public static void missingRead(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos query,
            net.minecraft.core.BlockPos owner) {
        if (!ENABLED
                || level.getChunkSource().getChunkNow(owner.getX() >> 4, owner.getZ() >> 4) != null)
            return;
        LOG.info(
                "PROBE SEAM_READ tick {} ticking {} query {} owner {} stack {}",
                level.getGameTime(),
                tickingBlock,
                query,
                owner,
                Arrays.toString(Thread.currentThread().getStackTrace()));
    }

    private ArrivalProbe() {}
}
