package dev.starfold.devtools;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

public final class ProfileReport {
    public record Stats(double median, double p95, double p99, double max, double percent, int samples) {}
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
        return new Stats(quantile(sorted, n, .5), quantile(sorted, n, .95),
                quantile(sorted, n, .99), sorted[n - 1] / 1e6, frameSum == 0 ? 0 : 100 * sum / frameSum, n);
    }
    private static double quantile(long[] sorted, int n, double q) {
        return sorted[(int) Math.ceil(n * q) - 1] / 1e6;
    }
    private static String ms(long nanos) { return nanos < 0 ? "" : String.format(Locale.ROOT, "%.6f", nanos / 1e6); }
    public static void csv(Capture capture, Writer out) throws IOException {
        out.write("frame,frame_ms");
        for (String name : Capture.NAMES) out.write("," + name + "_cpu_ms," + name + "_gpu_ms");
        out.write("\n");
        for (int f = 0; f < capture.size(); f++) {
            out.write(f + "," + ms(capture.interval(f)));
            for (int s = 0; s < Capture.SECTIONS; s++)
                out.write("," + ms(capture.cpu(f, s)) + "," + ms(capture.gpu(f, s)));
            out.write("\n");
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
        output.accept("Inclusive sections overlap; percentages are sum(section)/sum(matched frame intervals), not additive. Missing GPU samples are excluded; CSV blanks mean unavailable.");
    }
    private static String format(String label, Stats s) {
        return String.format(Locale.ROOT, "%s: median %.3f / p95 %.3f / p99 %.3f / max %.3f ms; %.2f%% frame; n=%d",
                label, s.median, s.p95, s.p99, s.max, s.percent, s.samples);
    }
    private ProfileReport() {}
}
