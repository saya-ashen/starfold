package dev.starfold.devtools;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

public final class ProfileReport {
    public record Stats(
            double median, double p95, double p99, double max, double percent, int samples) {}

    public static Stats stats(long[] values, long[] frames) {
        long[] sorted = new long[values.length];
        int n = 0;
        double sum = 0, frameSum = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0) continue;
            sorted[n++] = values[i];
            sum += values[i];
            frameSum += frames[i];
        }
        Arrays.sort(sorted, 0, n);
        if (n == 0) return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
        return new Stats(
                quantile(sorted, n, .5),
                quantile(sorted, n, .95),
                quantile(sorted, n, .99),
                sorted[n - 1] / 1e6,
                frameSum == 0 ? 0 : 100 * sum / frameSum,
                n);
    }

    private static double quantile(long[] sorted, int n, double q) {
        return sorted[(int) Math.ceil(n * q) - 1] / 1e6;
    }

    private static String ms(long nanos) {
        return nanos < 0 ? "" : String.format(Locale.ROOT, "%.6f", nanos / 1e6);
    }

    public static void csv(Capture capture, Writer out) throws IOException {
        out.write("frame,frame_ms");
        for (String name : Capture.NAMES) out.write("," + name + "_cpu_ms," + name + "_gpu_ms");
        for (var metric : Capture.WORK_METRICS) {
            String name = metric.name().toLowerCase(Locale.ROOT);
            out.write("," + name);
            if (metric.timing()) out.write("," + name.substring(0, name.length() - 3) + "_ms");
        }
        out.write(",gc_pause_ms\n");
        for (int f = 0; f < capture.size(); f++) {
            out.write(f + "," + ms(capture.interval(f)));
            for (int s = 0; s < Capture.SECTIONS; s++)
                out.write("," + ms(capture.cpu(f, s)) + "," + ms(capture.gpu(f, s)));
            for (var metric : Capture.WORK_METRICS) {
                long value = capture.work(f, metric);
                out.write("," + value);
                if (metric.timing()) out.write("," + ms(value));
            }
            out.write("," + ms(capture.gcPause(f)) + "\n");
        }
    }

    public static void summary(Capture capture, Consumer<String> output) {
        long[] frames = new long[capture.size()], values = new long[capture.size()];
        for (int f = 0; f < frames.length; f++) frames[f] = capture.interval(f);
        output.accept(format("frame interval", stats(frames, frames)));
        for (int s = 0; s < Capture.SECTIONS; s++) {
            for (int f = 0; f < frames.length; f++) values[f] = capture.cpu(f, s);
            output.accept(format(Capture.NAMES[s] + " CPU", stats(values, frames)));
            for (int f = 0; f < frames.length; f++) values[f] = capture.gpu(f, s);
            output.accept(format(Capture.NAMES[s] + " GPU", stats(values, frames)));
        }
        output.accept(
                "Inclusive sections overlap; percentages are sum(section)/sum(matched frame"
                        + " intervals), not additive. Missing GPU samples are excluded; CSV blanks mean"
                        + " unavailable.");
        for (int f = 0; f < frames.length; f++) values[f] = capture.gcPause(f);
        output.accept(format("GC stop-the-world overlap", stats(values, frames)));
        output.accept("chunks = VANILLA_TERRAIN; entities includes nested entity shadows and deferred buffer submission; GPU times reflect enclosed commands, not all later buffer flushes. client_tick is CPU-only. GC overlap comes from JFR jdk.GCPhasePause intervals; concurrent GC is excluded.");
        workSummary(capture, output);
    }

    public static void workSummary(Capture capture, Consumer<String> output) {
        output.accept(
                "Fine CPU scopes: native required/listed, ready, water (palette + scan), map form"
                        + " an incomplete partition: chunk lookup, dirty iteration, early"
                        + " no-level/empty-dirty exits, collection maintenance, covered/result"
                        + " insertion and copying are outside these timers. Listed is built on first"
                        + " demand across both passes; submitted sections are repeatable section/layer"
                        + " records. FAR contains prepare (view + collect) and draw (command assembly"
                        + " outside prepare); compaction may occur outside FAR. Inclusive elapsed"
                        + " scopes overlap: do not add parent/child totals. Rebuild/reuse counts"
                        + " describe stable range selection and arena commands; fades remain dynamic."
                        + " Submitted indices are raw index counts; submitted vertices are logical quad"
                        + " vertices summed across submissions, not unique vertices or hardware"
                        + " invocations. Arena commands count merged subdraws per assembled/reused"
                        + " batch. Invalidations count events in their frame; reason flags attribute"
                        + " rebuilding prepares within one capture, may coincide, and do not sum to"
                        + " rebuilds. Zero means no observed work within documented instrumented"
                        + " scopes.");
        long[] sorted = new long[capture.size()];
        for (var metric : Capture.WORK_METRICS) {
            double total = 0;
            for (int f = 0; f < sorted.length; f++) {
                sorted[f] = capture.work(f, metric);
                total += sorted[f];
            }
            Arrays.sort(sorted);
            int n = sorted.length;
            long p95 = n == 0 ? 0 : sorted[(int) Math.ceil(n * .95) - 1];
            long max = n == 0 ? 0 : sorted[n - 1];
            double scale = metric.timing() ? 1e6 : 1;
            output.accept(
                    String.format(
                            Locale.ROOT,
                            "%s: total %.3f / mean %.3f / p95 %.3f / max %.3f %s; n=%d",
                            metric.name().toLowerCase(Locale.ROOT),
                            total / scale,
                            n == 0 ? 0 : total / n / scale,
                            p95 / scale,
                            max / scale,
                            metric.timing() ? "ms" : "count",
                            n));
        }
    }

    private static String format(String label, Stats s) {
        return String.format(
                Locale.ROOT,
                "%s: median %.3f / p95 %.3f / p99 %.3f / max %.3f ms; %.2f%% frame; n=%d",
                label,
                s.median,
                s.p95,
                s.p99,
                s.max,
                s.percent,
                s.samples);
    }

    private ProfileReport() {}
}
