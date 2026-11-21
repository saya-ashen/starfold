package dev.starfold.devtools;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.ChunkWorkDiagnostics.Stage;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

final class ChunkTrace implements ChunkWorkDiagnostics.Listener {
    private static final Stage[] STAGES = Stage.values();
    private static final int PER_STAGE_LIMIT = 8192;
    private static final int MESH_STAGE_LIMIT = 32768;
    private final long origin;
    private final Object server;
    private final UUID player;
    private final int limit;
    private final LongOpenHashSet attemptedImages = new LongOpenHashSet();
    private final ArrayList<Sample> samples = new ArrayList<>();
    private final int[] recorded = new int[STAGES.length];
    private final long[] dropped = new long[STAGES.length];
    // Client load, unload and native submission all run on the client thread.
    private final LongOpenHashSet awaitingDraw = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<IntOpenHashSet> visibleSections = new Long2ObjectOpenHashMap<>();
    private boolean closed;

    private record Sample(Stage stage, long chunk, long at, long duration, Long source, Integer sectionY) {}

    ChunkTrace(long origin, Object server, UUID player) {
        this(origin, server, player, PER_STAGE_LIMIT);
    }

    ChunkTrace(long origin, Object server, UUID player, int limit) {
        this.origin = origin;
        this.server = server;
        this.player = player;
        this.limit = limit;
    }

    boolean isServer(Object candidate) { return candidate == server; }

    boolean isPlayer(UUID candidate) { return candidate.equals(player); }

    void clientUnload(long chunk) {
        awaitingDraw.remove(chunk);
        visibleSections.remove(chunk);
    }

    @Override
    public void record(Stage stage, long chunk, long at, long duration) {
        append(stage, chunk, at, duration, null, null);
    }

    @Override
    public void generationLayer(Stage stage, long center, int status, Long dependency, long at) {
        append(stage, center, at, 0, dependency, status);
    }

    @Override
    public synchronized void imageAttempt(UUID recipient, long image, long at) {
        if (!closed && isPlayer(recipient) && !attemptedImages.contains(image)
                && append(Stage.IMAGE_FIRST_ATTEMPT, image, at, 0, null, null))
            attemptedImages.add(image);
    }

    @Override
    public synchronized void imageSent(UUID recipient, long image, Long source, long at, long duration) {
        if (isPlayer(recipient)) {
            attemptedImages.remove(image);
            append(Stage.IMAGE_SENT, image, at, duration, source, null);
        }
    }

    private synchronized boolean append(Stage stage, long chunk, long at, long duration, Long source, Integer sectionY) {
        if (closed || at - origin < 0) return false;
        int index = stage.ordinal();
        int stageLimit = (stage == Stage.CLIENT_MESH_SCHEDULED || stage == Stage.CLIENT_MESH_COMPILED
                || stage == Stage.CLIENT_SECTION_VISIBLE || stage == Stage.GENERATION_LAYER_START
                || stage == Stage.GENERATION_LAYER_RESUME || stage == Stage.GENERATION_SORTER_QUEUE
                || stage == Stage.GENERATION_WORKER_QUEUE || stage == Stage.GENERATION_DISPATCH_LEVEL) && limit == PER_STAGE_LIMIT
                ? MESH_STAGE_LIMIT : limit;
        if (recorded[index] >= stageLimit) {
            dropped[index]++;
            return false;
        }
        recorded[index]++;
        samples.add(new Sample(stage, chunk, at, duration, source, sectionY));
        return true;
    }

    void clientLoad(long chunk, long now) {
        if (append(Stage.CLIENT_LOAD, chunk, now, 0, null, null)) {
            awaitingDraw.add(chunk);
            visibleSections.remove(chunk);
        }
    }

    @Override
    public void drawn(long chunk, int sectionY) {
        if (awaitingDraw.remove(chunk)) {
            visibleSections.remove(chunk);
            append(Stage.FIRST_DRAW, chunk, System.nanoTime(), 0, null, sectionY);
        }
    }

    @Override
    public void sectionVisible(long chunk, int sectionY, long at) {
        if (awaitingDraw.contains(chunk) && visibleSections.computeIfAbsent(chunk, ignored -> new IntOpenHashSet()).add(sectionY))
            append(Stage.CLIENT_SECTION_VISIBLE, chunk, at, 0, null, sectionY);
    }

    @Override
    public void meshScheduled(long chunk, int sectionY, long at) {
        append(Stage.CLIENT_MESH_SCHEDULED, chunk, at, 0, null, sectionY);
    }

    @Override
    public void meshCompiled(long chunk, int sectionY, long at, long duration) {
        append(Stage.CLIENT_MESH_COMPILED, chunk, at, duration, null, sectionY);
    }

    synchronized void close() {
        closed = true;
        awaitingDraw.clear();
        visibleSections.clear();
        attemptedImages.clear();
    }

    void csv(Writer writer) throws IOException {
        writer.write("stage,chunk_x,chunk_z,elapsed_ns,duration_ns,source_x,source_z,section_y\n");
        samples.sort(Comparator.comparingLong(Sample::at));
        for (var sample : samples) {
            writer.write(sample.stage() + "," + (int) sample.chunk() + "," + (int) (sample.chunk() >>> 32) + ","
                    + (sample.at() - origin) + "," + sample.duration() + ",");
            Long source = sample.source();
            writer.write(source == null ? ",," : source.intValue() + "," + (int) (source >>> 32) + ",");
            if (sample.sectionY() != null) writer.write(sample.sectionY().toString());
            writer.write('\n');
        }
    }

    void summary(Writer writer) throws IOException {
        writer.write("integrated_server=" + (server != null) + "\n");
        for (Stage stage : STAGES) {
            int index = stage.ordinal();
            writer.write(stage + " recorded=" + recorded[index] + " dropped=" + dropped[index]);
            long[] durations = samples.stream().filter(s -> s.stage() == stage && s.duration() > 0)
                    .mapToLong(Sample::duration).sorted().toArray();
            if (durations.length > 0)
                writer.write(String.format(Locale.ROOT, " p50=%.3f ms p95=%.3f ms max=%.3f ms",
                        durations[(durations.length - 1) / 2] / 1e6,
                        durations[(int) Math.ceil(durations.length * .95) - 1] / 1e6,
                        durations[durations.length - 1] / 1e6));
            writer.write('\n');
        }
    }
}
