package dev.starfold.dev;

import com.sun.management.ThreadMXBean;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Wall-clock flight benchmark, enabled only by a development probe command. */
final class DevFlightPerfProbe {
    private static final long[] FRAMES = new long[65536];
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final java.util.List<java.lang.management.GarbageCollectorMXBean> GC =
            ManagementFactory.getGarbageCollectorMXBeans();
    private static String label;
    private static long start, last, duration, allocated, collections, pause;
    private static int count;
    private static double altitude;
    private static boolean stationary;
    private static long nextSample;
    private static final StringBuilder SAMPLES = new StringBuilder();

    static void begin(Minecraft mc, String arg) {
        String[] a = arg.split("\\s+");
        label = a[0];
        duration = Long.parseLong(a[1]) * 1_000_000_000L;
        altitude =
                a.length > 2
                        ? Double.parseDouble(a[2])
                        : label.equals("low") ? ground(mc) + 20 : 300;
        stationary = a.length > 3 && a[3].equals("still");
        nextSample = 0;
        SAMPLES.setLength(0);
        SAMPLES.append("wall_ms,x,y,z,controlled,pending\n");
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE GL vendor={} renderer={} version={}",
                        org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR),
                        org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),
                        org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
        start = last = 0;
        count = 0;
        mc.options.renderDistance().set(10);
        mc.options.framerateLimit().set(260);
        mc.options.enableVsync().set(false);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1920, 1080);
        org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
        mc.player.getAbilities().flying = true;
        mc.player.onUpdateAbilities();
        mc.player.setYRot(-90);
        mc.player.setXRot(0);
    }

    private static int ground(Minecraft mc) {
        return mc.level.getHeight(
                Heightmap.Types.MOTION_BLOCKING, mc.player.getBlockX(), mc.player.getBlockZ());
    }

    static boolean tick(Minecraft mc) {
        if (label == null) return false;
        if (!mc.player.getAbilities().flying) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
        long now = System.nanoTime();
        if (mc.level.getGameTime() % 100 == 0)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE FLIGHT_STATE {} pos {} ground {} screen {} input {} flying {} active {}",
                            label,
                            mc.player.position(),
                            ground(mc),
                            mc.screen,
                            mc.player.input.forwardImpulse,
                            mc.player.getAbilities().flying,
                            mc.isWindowActive());
        if (start == 0 && mc.player.getY() >= altitude - 1) {
            start = now;
            DevMeshProbe.begin();
            allocated = THREADS.getTotalThreadAllocatedBytes();
            collections = gc(false);
            pause = gc(true);
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE FLIGHT_BEGIN {} pos {} target_y {} window {}x{}",
                            label,
                            mc.player.position(),
                            altitude,
                            mc.getWindow().getWidth(),
                            mc.getWindow().getHeight());
        }
        if (start != 0 && now >= nextSample) {
            nextSample = now + 1_000_000_000L;
            SAMPLES.append(System.currentTimeMillis())
                    .append(',')
                    .append(mc.player.getX())
                    .append(',')
                    .append(mc.player.getY())
                    .append(',')
                    .append(mc.player.getZ())
                    .append(',')
                    .append(dev.starfold.flight.GroundFlightClient.controlled())
                    .append(',')
                    .append("na")
                    .append('\n');
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE FLIGHT_PROGRESS {} elapsed={} baseProgress={} flight={} {}",
                            label,
                            (now - start) / 1e9,
                            dev.starfold.render.GroundViewClient.terrain() == null
                                    ? -1
                                    : dev.starfold.render.GroundBaseCache.surface(
                                                    dev.starfold.render.GroundViewClient.terrain(),
                                                    64)
                                            .progress(),
                            dev.starfold.flight.GroundFlightClient.probeReading(),
                            dev.starfold.render.GroundRenderer.memory());
        }
        if (start != 0 && now - start >= duration) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            long bytes = THREADS.getTotalThreadAllocatedBytes() - allocated;
            long gcCount = gc(false) - collections, gcMs = gc(true) - pause;
            long[] sorted = Arrays.copyOf(FRAMES, count);
            Arrays.sort(sorted);
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            String.format(
                                    Locale.ROOT,
                                    "PROBE FLIGHT %s seconds=%.3f n=%d p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f max_ms=%.3f gc_count=%d gc_ms=%d client_alloc_MB_s=%.3f end=%s",
                                    label,
                                    (now - start) / 1e9,
                                    count,
                                    percentile(sorted, .5) / 1e6,
                                    percentile(sorted, .95) / 1e6,
                                    percentile(sorted, .99) / 1e6,
                                    sorted[count - 1] / 1e6,
                                    gcCount,
                                    gcMs,
                                    bytes * 1e3 / (now - start),
                                    mc.player.position()));
            try {
                var out = new StringBuilder("frame_ns\n");
                for (int i = 0; i < count; i++) out.append(FRAMES[i]).append('\n');
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("flight-" + label + ".csv"), out);
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("positions-" + label + ".csv"), SAMPLES);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            DevMeshProbe.end(label);
            label = null;
            return false;
        }
        if (start != 0 && label.equals("low")) altitude = ground(mc) + 20;
        mc.options.keyUp.setDown(start != 0 && !stationary);
        mc.options.keyJump.setDown(mc.player.getY() < altitude - 1);
        mc.options.keyShift.setDown(mc.player.getY() > altitude + 1);
        return true;
    }

    static long percentile(long[] sorted, double fraction) {
        return sorted[(int) Math.ceil(sorted.length * fraction) - 1];
    }

    static void frame() {
        if (label == null || start == 0) return;
        long now = System.nanoTime();
        if (last != 0 && count < FRAMES.length) FRAMES[count++] = now - last;
        last = now;
    }

    private static long gc(boolean time) {
        long value = 0;
        for (var bean : GC) value += time ? bean.getCollectionTime() : bean.getCollectionCount();
        return value;
    }

    private DevFlightPerfProbe() {}
}
