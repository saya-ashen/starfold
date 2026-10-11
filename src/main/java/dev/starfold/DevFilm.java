package dev.starfold;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Development only, reached through a probe script: pipes the rendered frames to ffmpeg at a fixed rate, so footage
 * can be filmed without a screen recorder. {@code film start <name> [fps]} writes {@code film/<name>.mp4} in the game
 * directory; {@code film stop} ends it. The encoder is {@code -Dstarfold.ffmpeg} or {@code ffmpeg} on the path.
 */
final class DevFilm {
    private static Process encoder;
    private static OutputStream out;
    private static ByteBuffer pixels;
    private record Frame(byte[] bytes, int times) {}
    private static java.util.concurrent.ArrayBlockingQueue<Frame> queue;
    private static java.util.concurrent.ArrayBlockingQueue<byte[]> spare;
    private static Thread writer;
    private static String name;
    private static int width, height, fps;
    private static long start, written;

    static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) -> capture(Minecraft.getInstance()));
    }

    static void command(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        if (a[0].equals("stop")) { stop(); return; }
        if (!a[0].equals("start") || a.length < 2) throw new IllegalArgumentException("film start <name> [fps] | film stop");
        stop();
        var target = mc.getMainRenderTarget();
        name = a[1]; fps = a.length > 2 ? Integer.parseInt(a[2]) : 60; width = target.width; height = target.height;
        var directory = new File(mc.gameDirectory, "film");
        directory.mkdirs();
        try {
            // OpenGL rows run bottom to top; H.264 with 4:2:0 chroma needs even dimensions.
            encoder = new ProcessBuilder(System.getProperty("starfold.ffmpeg", "ffmpeg"), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba",
                "-s", width + "x" + height, "-r", Integer.toString(fps), "-i", "-", "-vf", "vflip,crop=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "12", "-pix_fmt", "yuv420p", new File(directory, name + ".mp4").getPath())
                .redirectErrorStream(true).redirectOutput(new File(directory, name + ".log")).start();
        } catch (java.io.IOException error) { throw new IllegalStateException("film: cannot start the encoder", error); }
        out = new java.io.BufferedOutputStream(encoder.getOutputStream(), 1 << 20);
        pixels = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
        // Writing to the encoder happens off the render thread: a full pipe would otherwise stall a frame, and a stalled frame is filmed twice.
        queue = new java.util.concurrent.ArrayBlockingQueue<>(6); spare = new java.util.concurrent.ArrayBlockingQueue<>(6);
        for (int i = 0; i < 6; i++) spare.add(new byte[width * height * 4]);
        var stream = out; var frames = queue; var free = spare;
        writer = new Thread(() -> {
            try {
                for (var next = frames.take(); next.times() > 0; next = frames.take()) { for (int n = 0; n < next.times(); n++) stream.write(next.bytes()); free.offer(next.bytes()); }
            } catch (java.io.IOException | InterruptedException error) { org.slf4j.LoggerFactory.getLogger("starfold-probe").error("PROBE FILM write failed", error); }
        }, "starfold-film");
        writer.setDaemon(true); writer.start();
        written = 0; start = System.nanoTime();
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE FILM start {} {}x{} {} fps", name, width, height, fps);
    }

    /** One frame for every 1/fps of wall time: a slow frame is repeated, extra frames are skipped. */
    private static void capture(Minecraft mc) {
        if (out == null) return;
        var target = mc.getMainRenderTarget();
        if (target.width != width || target.height != height) { stop(); return; }
        long due = (System.nanoTime() - start) * fps / 1_000_000_000L + 1;
        if (written >= due) return;
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        pixels.clear();
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        var frame = spare.poll();
        if (frame == null) return; // the encoder is behind: this moment is covered by repeating the next frame that gets through
        pixels.get(0, frame);
        int times = (int) Math.min(due - written, 8);
        if (queue.offer(new Frame(frame, times))) written += times; else spare.offer(frame);
    }

    static void stop() {
        if (encoder == null) return;
        try {
            if (writer != null) { queue.put(new Frame(new byte[0], 0)); writer.join(60_000); }
            if (out != null) out.close(); else encoder.getOutputStream().close();
            if (!encoder.waitFor(60, TimeUnit.SECONDS)) encoder.destroyForcibly();
        } catch (java.io.IOException | InterruptedException error) { encoder.destroyForcibly(); }
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE FILM stop {} frames {} seconds {}", name, written, String.format(java.util.Locale.ROOT, "%.1f", written / (double) fps));
        encoder = null; out = null; pixels = null; queue = null; spare = null; writer = null;
    }

    private DevFilm() {}
}
