package dev.starfold.mining;

/** The bounded work volume, independent of Minecraft and world/chunk access. */
final class MiningVolume {
    static final int WIDTH = 5, DEPTH = 512, LAYER = WIDTH * WIDTH, TOTAL = LAYER * DEPTH;

    record Offset(int x, int y, int z) {}

    static Offset offset(int cursor) {
        if (cursor < 0 || cursor >= TOTAL) throw new IllegalArgumentException("Mining cursor");
        return new Offset(
                cursor % WIDTH - WIDTH / 2,
                -1 - cursor / LAYER,
                cursor % LAYER / WIDTH - WIDTH / 2);
    }

    private MiningVolume() {}
}
