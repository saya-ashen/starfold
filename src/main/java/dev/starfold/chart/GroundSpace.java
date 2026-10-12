package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.*;

/** Six large logical faces; this experiment does not reinterpret any old spherical save. */
public final class GroundSpace {
    public static final int SIZE = 2048,
            MIN = -8,
            MAX = 56,
            VOLUME = 6 * SIZE * SIZE * (MAX - MIN),
            TILE = 16,
            ACROSS = SIZE / TILE;
    public static final int MAX_CORE = 1000;
    public static final double REACH = 5, CEILING = GroundOrbit.LIMIT;
    public static final CubeSurfaceTopology TOPOLOGY = new CubeSurfaceTopology(SIZE);

    public record Config(int core, int height) {
        public Config {
            if (core < 1 || core > MAX_CORE || height < 1)
                throw new IllegalArgumentException(
                        "core 1..1000, height must be a positive integer");
        }
    }

    /** User-accepted profile for NEW ledgers only; never overwrite a saved mask. */
    public static final Config DEFAULT_MASK = new Config(16, 128);

    public record Pose(Face face, Point point) {
        public Pose {
            if (face == null
                    || point == null
                    || !point.finite()
                    || point.x() < 0
                    || point.x() >= SIZE
                    || point.z() < 0
                    || point.z() >= SIZE
                    || point.y() < MIN
                    || point.y() > CEILING)
                throw new IllegalArgumentException("Invalid ground pose");
        }
    }

    public record Transport(Pose pose, int turns) {}

    public static Transport canonical(Face face, Point p) {
        int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
        var r = TOPOLOGY.canonical(new Cell(face, x, 0, z));
        var d = ChartLabSpace.rotate(new Point(p.x() - x - .5, 0, p.z() - z - .5), r.turns());
        return new Transport(
                new Pose(
                        r.cell().face(),
                        new Point(
                                Math.clamp(
                                        r.cell().u() + .5 + d.x(), 0, Math.nextDown((double) SIZE)),
                                p.y(),
                                Math.clamp(
                                        r.cell().v() + .5 + d.z(),
                                        0,
                                        Math.nextDown((double) SIZE)))),
                r.turns());
    }

    public static boolean stored(Cell c) {
        return c != null && TOPOLOGY.owned(c) && c.h() >= MIN && c.h() < MAX;
    }

    public static boolean column(Config c, Cell p) {
        return p != null
                && TOPOLOGY.owned(p)
                && Math.min(p.u(), SIZE - 1 - p.u()) < c.core
                && Math.min(p.v(), SIZE - 1 - p.v()) < c.core;
    }

    /** Physical overlay height is independent of editable storage and its stable integer keys. */
    public static boolean mask(Config c, Cell p) {
        return p != null && p.h() >= MIN && p.h() < c.height && column(c, p);
    }

    public static int index(Cell c) {
        if (!stored(c)) throw new IllegalArgumentException("Cell");
        return c.u() + SIZE * (c.v() + SIZE * (c.face().ordinal() + 6 * (c.h() - MIN)));
    }

    public static Cell cell(int i) {
        if (i < 0 || i >= VOLUME) throw new IllegalArgumentException("Index");
        return new Cell(
                Face.values()[i / (SIZE * SIZE) % 6],
                i % SIZE,
                MIN + i / (6 * SIZE * SIZE),
                i / SIZE % SIZE);
    }

    private GroundSpace() {}
}
