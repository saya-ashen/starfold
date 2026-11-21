package dev.starfold.devtools;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.RenderTimings;
import dev.starfold.api.RenderWorkDiagnostics;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.io.IOException;
import java.nio.file.Files;

public final class Profiler implements RenderTimings.Listener {
    private static Capture capture;
    private static ChunkTrace chunks;
    private static ProfileFrames frames;
    private static jdk.jfr.Recording gcRecording;
    private static java.time.Instant captureStart;
    private static boolean startRequested, stopRequested;
    private static final Profiler LISTENER = new Profiler();

    public static void start() {
        if (capture != null || startRequested) {
            Devtools.message("Already recording.");
            return;
        }
        startRequested = true;
        Devtools.message(
                "Recording starts at next frame boundary; /sfprofile stop to export (auto-stop at"
                        + " 120000 frames).");
    }

    public static void stop() {
        if (capture == null) {
            startRequested = false;
            Devtools.message("Not recording.");
        } else {
            stopRequested = true;
            frames.stop(System.nanoTime());
            RenderTimings.register(null);
            stopChunks();
        }
    }

    public static void frameBegin() {
        if (capture == null && !startRequested) return;
        long now = System.nanoTime();
        if (capture != null) {
            frames.boundary(now);
            if (stopRequested || capture.full()) {
                export();
                return;
            }
        } else {
            startRequested = false;
            var caps = GL.getCapabilities();
            boolean core = caps.OpenGL33;
            if (!core && !caps.GL_ARB_timer_query) {
                Devtools.message("GL33/ARB_timer_query unavailable; recording not started.");
                return;
            }
            gcRecording = new jdk.jfr.Recording();
            gcRecording.enable("jdk.GCPhasePause").withThreshold(java.time.Duration.ZERO);
            gcRecording.enable("minecraft.ChunkGeneration").withThreshold(java.time.Duration.ZERO);
            gcRecording.setToDisk(true);
            gcRecording.start();
            // Bounded capture keeps the recording path allocation-free, including frame storage.
            capture =
                    new Capture(
                            new Capture.Queries() {
                                public int create() {
                                    return GL15.glGenQueries();
                                }

                                public void timestamp(int id) {
                                    if (core) GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
                                    else
                                        ARBTimerQuery.glQueryCounter(
                                                id, ARBTimerQuery.GL_TIMESTAMP);
                                }

                                public boolean available(int id) {
                                    return GL15.glGetQueryObjecti(
                                                    id, GL15.GL_QUERY_RESULT_AVAILABLE)
                                            != 0;
                                }

                                public long result(int id) {
                                    return core
                                            ? GL33.glGetQueryObjecti64(id, GL15.GL_QUERY_RESULT)
                                            : ARBTimerQuery.glGetQueryObjecti64(
                                                    id, GL15.GL_QUERY_RESULT);
                                }

                                public void delete(int id) {
                                    GL15.glDeleteQueries(id);
                                }
                            },
                            120_000,
                            8,
                            256);
            RenderTimings.register(LISTENER);
            frames = new ProfileFrames(capture);
            // Query allocation and storage setup are not part of the first recorded frame.
            now = System.nanoTime();
            captureStart = java.time.Instant.now();
            var mc = Minecraft.getInstance();
            chunks = new ChunkTrace(now, mc.getSingleplayerServer(), mc.player == null ? null : mc.player.getUUID());
            ChunkWorkDiagnostics.register(chunks);
        }
        frames.open(now);
    }

    public static void frameEnd() {
        if (frames != null) frames.frameEnd(System.nanoTime());
    }

    public static void begin(int section) {
        if (frames != null) frames.begin(section, System.nanoTime());
    }

    public static void beginCpu(int section) {
        if (frames != null) frames.beginCpu(section, System.nanoTime());
    }

    public static void end(int section) {
        if (frames != null) frames.end(section, System.nanoTime());
    }

    @Override
    public void begin(RenderTimings.Section section) {
        begin(section.ordinal());
    }

    @Override
    public void end(RenderTimings.Section section) {
        end(section.ordinal());
    }

    private static void stopChunks() {
        ChunkWorkDiagnostics.register(null);
        chunks.close();
    }

    private static void export() {
        if (!stopRequested) stopChunks();
        ChunkTrace chunkTrace = chunks;
        chunks = null;
        RenderTimings.register(null);
        RenderWorkDiagnostics.register(null);
        stopRequested = false;
        Capture finished = capture;
        capture = null;
        frames = null;
        finished.finish();
        try (finished) {
            var directory =
                    Minecraft.getInstance().gameDirectory.toPath().resolve("starfold-devtools");
            Files.createDirectories(directory);
            var file = Files.createTempFile(directory, "profile-", ".csv");
            var stageFile = file.resolveSibling("generation-" + file.getFileName().toString().substring("profile-".length()));
            try (var recording = gcRecording) {
                gcRecording = null;
                recording.stop();
                var events = Files.createTempFile(directory, "gc-", ".jfr");
                try {
                    recording.dump(events);
                    try (var input = new jdk.jfr.consumer.RecordingFile(events);
                            var writer = Files.newBufferedWriter(stageFile)) {
                        writer.write("status,chunk_x,chunk_z,elapsed_ns,duration_ns\n");
                        while (input.hasMoreEvents()) {
                            var event = input.readEvent();
                            long offset = java.time.Duration.between(captureStart, event.getStartTime()).toNanos();
                            long duration = event.getDuration().toNanos();
                            switch (event.getEventType().getName()) {
                                case "jdk.GCPhasePause" -> finished.recordGcPause(offset, duration);
                                case "minecraft.ChunkGeneration" -> {
                                    if (offset >= 0 && event.getString("level").endsWith(" / starfold:native_planet]"))
                                        writer.write(event.getString("status") + "," + event.getInt("chunkPosX") + ","
                                                + event.getInt("chunkPosZ") + "," + offset + "," + duration + "\n");
                                }
                            }
                        }
                    }
                } finally { Files.deleteIfExists(events); }
            }
            try (var writer = Files.newBufferedWriter(file)) {
                ProfileReport.csv(finished, writer);
            }
            var chunkFile = file.resolveSibling("chunks-" + file.getFileName().toString().substring("profile-".length()));
            try (var writer = Files.newBufferedWriter(chunkFile)) {
                chunkTrace.csv(writer);
            }
            var summary = file.resolveSibling(file.getFileName() + ".summary.txt");
            try (var writer = Files.newBufferedWriter(summary)) {
                ProfileReport.summary(
                        finished,
                        line -> {
                            try {
                                writer.write(line + "\n");
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
                writer.write("\nPlanet chunk events\n");
                chunkTrace.summary(writer);
            }
            Devtools.message("Saved " + finished.size() + " frames to " + file);
            Devtools.message("Planet chunk events: " + chunkFile);
            Devtools.message("Aggregate statistics: " + summary);
            ProfileReport.summary(finished, Devtools::message);
        } catch (IOException | java.io.UncheckedIOException e) {
            Devtools.LOGGER.error("Cannot write render profile", e);
            Devtools.message("Profile export failed: " + e.getMessage());
        }
    }

    private Profiler() {}
}
