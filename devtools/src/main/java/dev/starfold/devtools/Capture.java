package dev.starfold.devtools;

import java.util.Arrays;

/** Render-thread-only capture; all storage and query names are allocated before recording. */
public final class Capture implements AutoCloseable {
    public static final String[] NAMES = {"mesh", "far", "ownership", "native", "sky", "remote",
            "remote_work", "gas", "frame", "level", "chunks"};
    public static final int FRAME = 8, LEVEL = 9, CHUNKS = 10, SECTIONS = NAMES.length;
    public interface Queries {
        int create();
        void timestamp(int query);
        boolean available(int query);
        long result(int query);
        void delete(int query);
    }
    private static final int DELAY = 3;
    private final Queries queries;
    private final Slot[] ring;
    private final long[] starts = new long[SECTIONS];
    private final int[] pairs = new int[SECTIONS];
    private final long[] cpu, gpu, intervals;
    private int row = -1, completed;
    private long frameStart;
    private Slot active;

    private static final class Slot {
        final int[] ids, sections;
        final int fence;
        int row = -1, used;
        Slot(Queries queries, int capacity) {
            fence = queries.create();
            ids = new int[capacity * 2];
            sections = new int[capacity];
            for (int i = 0; i < ids.length; i++) ids[i] = queries.create();
        }
    }
    public Capture(Queries queries, int frames, int slots, int pairsPerFrame) {
        this.queries = queries;
        cpu = new long[frames * SECTIONS];
        gpu = new long[cpu.length];
        intervals = new long[frames];
        ring = new Slot[slots];
        for (int i = 0; i < slots; i++) ring[i] = new Slot(queries, pairsPerFrame);
        Arrays.fill(pairs, -1);
    }
    /** Close the previous complete frame at the next frame's start (includes pacing/swap). */
    public void boundary(long now) {
        if (row >= 0) {
            intervals[row] = now - frameStart;
            completed = row + 1;
            if (active != null) queries.timestamp(active.fence);
        }
        poll(completed, false);
        active = null;
        row = -1;
    }
    public boolean full() { return completed == intervals.length; }
    public void open(long now) {
        row = completed;
        frameStart = now;
        Slot slot = ring[row % ring.length];
        if (slot.row < 0) {
            active = slot;
            slot.row = row;
            slot.used = 0;
        } else {
            Arrays.fill(gpu, row * SECTIONS, (row + 1) * SECTIONS, -1);
        }
    }
    public void begin(int section, long now) {
        if (row < 0) return;
        starts[section] = now;
        if (active != null && active.used < active.sections.length) {
            int pair = active.used++;
            pairs[section] = pair;
            active.sections[pair] = section;
            queries.timestamp(active.ids[pair * 2]);
        } else {
            gpu[row * SECTIONS + section] = -1;
        }
    }
    public void end(int section, long now) {
        if (row < 0) return;
        cpu[row * SECTIONS + section] += now - starts[section];
        int pair = pairs[section];
        if (pair >= 0) {
            queries.timestamp(active.ids[pair * 2 + 1]);
            pairs[section] = -1;
        }
    }
    private void poll(int age, boolean stopping) {
        for (Slot slot : ring) {
            if (slot.row < 0 || (!stopping && age - slot.row < DELAY)) continue;
            // A separate end-of-frame timestamp follows every nested section's end.
            if (!queries.available(slot.fence)) continue;
            for (int i = 0; i < slot.used; i++) {
                int index = slot.row * SECTIONS + slot.sections[i];
                long elapsed = queries.result(slot.ids[i * 2 + 1]) - queries.result(slot.ids[i * 2]);
                if (gpu[index] >= 0) gpu[index] += elapsed;
            }
            slot.row = -1;
        }
    }
    public void finish() {
        poll(completed, true);
        for (Slot slot : ring) {
            if (slot.row < 0) continue;
            for (int i = 0; i < slot.used; i++) gpu[slot.row * SECTIONS + slot.sections[i]] = -1;
        }
    }
    public int size() { return completed; }
    public long interval(int frame) { return intervals[frame]; }
    public long cpu(int frame, int section) { return cpu[frame * SECTIONS + section]; }
    public long gpu(int frame, int section) { return gpu[frame * SECTIONS + section]; }
    @Override public void close() {
        for (Slot slot : ring) {
            for (int id : slot.ids) queries.delete(id);
            queries.delete(slot.fence);
        }
    }
}
