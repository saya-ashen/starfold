package dev.starfold.dev;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** Development-script instrumentation only; no driver queries or per-frame logging. */
public final class DevMeshProbe {
    public interface TaskAccess {
        Trace starfold$trace();
        void starfold$trace(Trace trace);
    }
    public interface SectionAccess {
        void starfold$published(Trace trace);
        void starfold$drawn();
    }
    public static final class Trace {
        public final long created = System.nanoTime();
        public final long origin, generation;
        public final int window;
        public long scheduled, polled, built, published;
        public boolean cancelled;
        public Trace(long origin, long generation) {
            this.origin = origin;
            this.generation = generation;
            this.window = DevMeshProbe.window;
        }
    }
    public static final class Times {
        private final long[] bins = new long[64];
        private long count, total, max;
        public synchronized void add(long ns) {
            bins[ns == 0 ? 0 : 64 - Long.numberOfLeadingZeros(ns)]++;
            count++;
            total += ns;
            max = Math.max(max, ns);
        }
        synchronized void clear() { Arrays.fill(bins, 0); count = total = max = 0; }
        private double percentile(double p) {
            long target = (long) Math.ceil(count * p), n = 0;
            for (int i = 0; i < bins.length; i++) {
                n += bins[i];
                if (n >= target) return Math.scalb(1.0, i) / 1e6;
            }
            return 0;
        }
        synchronized String summary() {
            return String.format(Locale.ROOT, "n=%d total_ms=%.3f mean_ms=%.3f p50_upper_ms=%.3f p95_upper_ms=%.3f max_ms=%.3f",
                    count, total / 1e6, count == 0 ? 0 : total / (count * 1e6),
                    count == 0 ? 0 : percentile(.5), count == 0 ? 0 : percentile(.95), max / 1e6);
        }
    }
    public static final Times PREPARE = new Times(), DIRTY_WAIT = new Times(), QUEUE = new Times(),
            EXECUTOR = new Times(), COMPILE = new Times(), UPLOAD_WAIT = new Times(), UPLOAD = new Times(),
            PUBLISH = new Times(), DRAW = new Times(), TOTAL = new Times(), FRAME_COMPILE = new Times(), SYNC = new Times();
    private static final Times[] TIMES = {PREPARE, DIRTY_WAIT, QUEUE, EXECUTOR, COMPILE, UPLOAD_WAIT, UPLOAD,
            PUBLISH, DRAW, TOTAL, FRAME_COMPILE, SYNC};
    private static final String[] NAMES = {"prepare", "dirty_wait", "queue", "executor", "compile", "upload_wait", "upload",
            "publish_after_build", "first_draw_after_publish", "create_to_draw", "compileSections", "near_sync"};
    private static final LongOpenHashSet UNIQUE = new LongOpenHashSet();
    private static volatile int window;
    private static volatile boolean measuring;
    private static long created, first, cancelled, cancelledBuilt, published, draws, bytes, sorted, dirty, playerDirty;
    private static long frames, pc, pu, ab;
    private static int maxPc, maxPu, minAb;

    static synchronized void begin() {
        measuring = false;
        window++;
        for (var times : TIMES) times.clear();
        UNIQUE.clear();
        created = first = cancelled = cancelledBuilt = published = draws = bytes = sorted = dirty = playerDirty = 0;
        frames = pc = pu = ab = maxPc = maxPu = 0;
        minAb = Integer.MAX_VALUE;
        measuring = true;
    }
    public static boolean active(Trace t) { return measuring && t != null && t.window == window; }
    public static synchronized void created(Trace t, boolean initial, long prepare, long dirtySince) {
        if (!active(t)) return;
        created++; if (initial) first++;
        UNIQUE.add(t.origin);
        PREPARE.add(prepare);
        if (dirtySince != 0) DIRTY_WAIT.add(t.created - dirtySince);
    }
    public static synchronized void dirty(boolean player) {
        if (!measuring) return;
        dirty++; if (player) playerDirty++;
    }
    public static synchronized void cancelled(Trace t) {
        if (!active(t) || t.cancelled || t.published != 0) return;
        t.cancelled = true;
        cancelled++; if (t.built != 0) cancelledBuilt++;
    }
    public static synchronized void published(Trace t) {
        t.published = System.nanoTime();
        if (!active(t)) return;
        published++;
        if (t.built != 0) PUBLISH.add(t.published - t.built);
    }
    public static synchronized void drawn(Trace t) {
        if (!active(t)) return;
        draws++;
        long now = System.nanoTime();
        DRAW.add(now - t.published); TOTAL.add(now - t.created);
    }
    public static Runnable upload(Runnable original, long bytes, boolean index) {
        long enqueued = System.nanoTime();
        int sample = window;
        return () -> {
            long start = System.nanoTime();
            original.run();
            if (measuring && sample == window) {
                UPLOAD_WAIT.add(start - enqueued);
                UPLOAD.add(System.nanoTime() - start);
                uploaded(bytes, index);
            }
        };
    }
    public static synchronized void uploaded(long size, boolean index) {
        if (!measuring) return;
        bytes += size; if (index) sorted++;
    }
    public static synchronized void frame(SectionRenderDispatcher dispatcher) {
        if (!measuring) return;
        int c = dispatcher.getToBatchCount(), u = dispatcher.getToUpload(), b = dispatcher.getFreeBufferCount();
        frames++; pc += c; pu += u; ab += b;
        maxPc = Math.max(maxPc, c); maxPu = Math.max(maxPu, u); minAb = Math.min(minAb, b);
    }
    static synchronized void end(String label) {
        if (!measuring) return;
        measuring = false;
        var log = org.slf4j.LoggerFactory.getLogger("starfold-mesh-probe");
        log.info("PROBE MESH {} created={} unique_origins={} initial={} cancelled={} cancelled_after_build={} published={} first_draw={} upload_bytes={} sort_uploads={} dirty_calls={} player_dirty={}",
                label, created, UNIQUE.size(), first, cancelled, cancelledBuilt, published, draws, bytes, sorted, dirty, playerDirty);
        log.info("PROBE MESH {} frames={} pC_mean={} pC_max={} pU_mean={} pU_max={} aB_mean={} aB_min={}",
                label, frames, pc / (double) frames, maxPc, pu / (double) frames, maxPu, ab / (double) frames, minAb);
        for (int i = 0; i < TIMES.length; i++) log.info("PROBE MESH {} {} {}", label, NAMES[i], TIMES[i].summary());
    }
    private DevMeshProbe() {}
}
