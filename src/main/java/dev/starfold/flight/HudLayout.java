package dev.starfold.flight;

import java.util.Locale;

/** Pure GUI-pixel layout and instrument policy; no Minecraft/bootstrap dependency. */
public final class HudLayout {
    public enum Widget {
        RETICLE,
        MODE,
        SPEED,
        CLEARANCE,
        SPEED_TAPE,
        CLOSING,
        TARGET,
        VELOCITY,
        PLANETS,
        BEARING,
        THRUST,
        LANDING_DETAIL
    }

    public static boolean shows(int tier, Widget widget) {
        return tier
                >= switch (widget) {
                    case RETICLE, MODE, SPEED, CLEARANCE -> 1;
                    case SPEED_TAPE, CLOSING, TARGET, VELOCITY, PLANETS, BEARING -> 2;
                    case THRUST, LANDING_DETAIL -> 3;
                };
    }

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        boolean overlaps(Rect b) {
            return x < b.right() && right() > b.x && y < b.bottom() && bottom() > b.y;
        }
    }

    public record Layout(
            int width,
            int height,
            Rect mode,
            Rect view,
            Rect speed,
            Rect closing,
            Rect thrust,
            Rect bearing,
            Rect centre) {}

    public static Layout physical(int width, int height, double scale) {
        scale = Math.max(1, Math.min(scale, Math.min(width / 320, height / 240)));
        return layout((int) Math.ceil(width / scale), (int) Math.ceil(height / scale));
    }

    public static Layout layout(int width, int height) {
        int side = Math.min(100, width / 4 - 8),
                top = Math.max(54, height * 27 / 100),
                bottom = Math.min(height - 82, height * 74 / 100),
                plate = Math.min(width - 32, 240);
        return new Layout(
                width,
                height,
                new Rect((width - plate) / 2, 8, plate, 32),
                new Rect((width - plate) / 2, 41, plate, 10),
                new Rect(8, top, side, Math.max(32, bottom - top)),
                new Rect(width - 8 - side, top, side, Math.max(32, bottom - top)),
                new Rect(width / 2 - 58, height - 26, 116, 24),
                new Rect(16, height - 62, 72, 54),
                new Rect(width / 3, height / 3, width / 3, height / 3));
    }

    public static Rect occlusion(Layout l) {
        return new Rect(
                (l.width() - Math.min(240, l.width() - 32)) / 2,
                65,
                Math.min(240, l.width() - 32),
                10);
    }

    public static String number(double value) {
        return Double.isFinite(value) ? Long.toString(Math.round(value)) : "—";
    }

    public static double fill(double value, double cap) {
        return !Double.isFinite(value) || !Double.isFinite(cap) || cap <= 0
                ? 0
                : Math.clamp(value / cap, 0, 1);
    }

    public static String eta(double distance, double closing) {
        if (!Double.isFinite(distance)
                || !Double.isFinite(closing)
                || distance < 0
                || closing <= .1) return "—";
        double seconds = distance / closing;
        if (seconds > 5999) return ">99:59";
        long s = (long) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private HudLayout() {}
}
