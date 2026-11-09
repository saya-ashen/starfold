package dev.starfold.devtools;

import dev.starfold.api.RenderTimings;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import java.io.IOException;
import java.nio.file.Files;

public final class Profiler implements RenderTimings.Listener {
    private static Capture capture;
    private static boolean startRequested, stopRequested;
    private static final Profiler LISTENER = new Profiler();
    public static void start() {
        if (capture != null || startRequested) { Devtools.message("Already recording."); return; }
        startRequested = true;
        Devtools.message("Recording starts at next frame boundary; /sfprofile stop to export (auto-stop at 120000 frames).");
    }
    public static void stop() {
        if (capture == null) {
            startRequested = false;
            Devtools.message("Not recording.");
        } else stopRequested = true;
    }
    public static void frameBegin() {
        if (capture == null && !startRequested) return;
        long now = System.nanoTime();
        if (capture != null) {
            capture.boundary(now);
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
            // Bounded capture keeps the recording path allocation-free, including frame storage.
            capture = new Capture(new Capture.Queries() {
                public int create() { return GL15.glGenQueries(); }
                public void timestamp(int id) {
                    if (core) GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
                    else ARBTimerQuery.glQueryCounter(id, ARBTimerQuery.GL_TIMESTAMP);
                }
                public boolean available(int id) { return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
                public long result(int id) {
                    return core ? GL33.glGetQueryObjecti64(id, GL15.GL_QUERY_RESULT)
                            : ARBTimerQuery.glGetQueryObjecti64(id, GL15.GL_QUERY_RESULT);
                }
                public void delete(int id) { GL15.glDeleteQueries(id); }
            }, 120_000, 8, 256);
            RenderTimings.register(LISTENER);
            // Query allocation and storage setup are not part of the first recorded frame.
            now = System.nanoTime();
        }
        capture.open(now);
        begin(Capture.FRAME);
    }
    public static void frameEnd() { end(Capture.FRAME); }
    public static void begin(int section) {
        if (capture != null) capture.begin(section, System.nanoTime());
    }
    public static void end(int section) {
        if (capture != null) capture.end(section, System.nanoTime());
    }
    @Override public void begin(RenderTimings.Section section) { begin(section.ordinal()); }
    @Override public void end(RenderTimings.Section section) { end(section.ordinal()); }
    private static void export() {
        RenderTimings.register(null);
        stopRequested = false;
        Capture finished = capture;
        capture = null;
        finished.finish();
        try (finished) {
            var directory = Minecraft.getInstance().gameDirectory.toPath().resolve("starfold-devtools");
            Files.createDirectories(directory);
            var file = Files.createTempFile(directory, "profile-", ".csv");
            try (var writer = Files.newBufferedWriter(file)) { ProfileReport.csv(finished, writer); }
            Devtools.message("Saved " + finished.size() + " frames to " + file);
            ProfileReport.summary(finished, Devtools::message);
        } catch (IOException e) {
            Devtools.LOGGER.error("Cannot write render profile", e);
            Devtools.message("Profile export failed: " + e.getMessage());
        }
    }
    private Profiler() {}
}
