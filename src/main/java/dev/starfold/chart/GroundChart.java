package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology.*;

import java.util.*;

/** The original rigid local net and unit-contact frame. GroundGap retains accepted low-height
 * display; GroundAir can flatten the whole visible chart above a chosen height. No contact edits. */
public final class GroundChart {
    public record Tile(Face face, int turn, int x, int z) {
        public Point point(Point p) {
            double n = GroundSpace.SIZE;
            return ChartLabSpace.rotate(p.sub(new Point(n / 2, 0, n / 2)), turn)
                    .add(new Point((x + .5) * n, 0, (z + .5) * n));
        }

        public Point local(Point p) {
            double n = GroundSpace.SIZE;
            return ChartLabSpace.rotate(p.sub(new Point((x + .5) * n, 0, (z + .5) * n)), -turn)
                    .add(new Point(n / 2, 0, n / 2));
        }

        public Tile adjacent(int dx, int dz) {
            double n = GroundSpace.SIZE;
            var p = local(new Point((x + dx + .5) * n + .5, 0, (z + dz + .5) * n + .5));
            var r =
                    GroundSpace.TOPOLOGY.canonical(
                            new Cell(face, (int) Math.floor(p.x()), 0, (int) Math.floor(p.z())));
            return new Tile(r.cell().face(), Math.floorMod(turn - r.turns(), 4), x + dx, z + dz);
        }
    }

    public final Face root;
    private final List<Tile> tiles;

    public GroundChart(Face root) {
        this.root = Objects.requireNonNull(root);
        var t = new Tile(root, 0, 0, 0);
        tiles =
                List.of(
                        t,
                        t.adjacent(1, 0),
                        t.adjacent(-1, 0),
                        t.adjacent(0, 1),
                        t.adjacent(0, -1));
    }

    public List<Tile> tiles() {
        return tiles;
    }

    public Tile tile(Face face) {
        for (var t : tiles) if (t.face == face) return t;
        return null;
    }

    public Tile at(int x, int z) {
        int a = Math.floorDiv(x, GroundSpace.SIZE), b = Math.floorDiv(z, GroundSpace.SIZE);
        for (var t : tiles) if (t.x == a && t.z == b) return t;
        return null;
    }

    public Cell owner(int x, int y, int z) {
        var t = at(x, z);
        if (t == null) return null;
        var p = t.local(new Point(x + .5, y, z + .5));
        return new Cell(t.face, (int) Math.floor(p.x()), y, (int) Math.floor(p.z()));
    }

    public Point point(GroundSpace.Pose p) {
        var t = tile(p.face());
        if (t == null) throw new IllegalArgumentException("Unrepresented face");
        return t.point(p.point());
    }
}
