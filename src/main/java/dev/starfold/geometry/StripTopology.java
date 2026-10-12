package dev.starfold.geometry;

/** A bounded fixed-width atlas. Unlike wedge same-position projection, it preserves local Y.
 * This is a test topology, not a cube-volume embedding. */
public final class StripTopology implements CellTopology {
    public static final int WIDTH = 16, HALO = 8;

    public record Cell(int x, int y, int z) {
        public Cell add(int x, int y, int z) {
            return new Cell(this.x + x, this.y + y, this.z + z);
        }
    }

    private final Cell left, right;
    private final int depth;

    public StripTopology(Cell left, Cell right) {
        this(left, right, 9);
    }

    public StripTopology(Cell left, Cell right, int depth) {
        if (depth < 1 || depth > 32) throw new IllegalArgumentException("depth");
        this.left = left;
        this.right = right;
        this.depth = depth;
    }

    public Cell canonical(Cell pos) {
        int x = pos.x - left.x, y = pos.y - left.y, z = pos.z - left.z;
        if (x >= WIDTH
                && x < WIDTH + HALO
                && y >= -16
                && y <= 32
                && z >= -HALO
                && z <= depth - 1 + HALO) return right.add(x - WIDTH, y, z);
        x = pos.x - right.x;
        y = pos.y - right.y;
        z = pos.z - right.z;
        if (x >= -HALO && x < 0 && y >= -16 && y <= 32 && z >= -HALO && z <= depth - 1 + HALO)
            return left.add(x + WIDTH, y, z);
        return pos;
    }

    /** Deliver to the real receiver, but preserve the relative source direction in its chart. */
    public record Notification(Cell target, Cell source) {}

    public Notification notification(Cell target, Cell source) {
        var real = canonical(target);
        return new Notification(
                real, source.add(real.x - target.x, real.y - target.y, real.z - target.z));
    }

    public Cell real(int x, int y, int z) {
        if (x < 0 || x >= 2 * WIDTH) throw new IllegalArgumentException("Outside test strip");
        return x < WIDTH ? left.add(x, y, z) : right.add(x - WIDTH, y, z);
    }
}
