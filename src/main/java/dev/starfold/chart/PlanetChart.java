package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import java.util.List;
import java.util.Objects;

/** The plane a face's observer stands on: the face itself and its four neighbours laid flat around it, for a
 *  planet of any size. GroundChart is the same net for the standard size. */
public final class PlanetChart {
    public record Tile(PlanetShape shape, Face face, int turn, int x, int z) {
        public Point point(Point p) {
            double n = shape.size();
            return ChartLabSpace.rotate(p.sub(new Point(n / 2, 0, n / 2)), turn)
                    .add(new Point((x + .5) * n, 0, (z + .5) * n));
        }

        public Point local(Point p) {
            double n = shape.size();
            return ChartLabSpace.rotate(p.sub(new Point((x + .5) * n, 0, (z + .5) * n)), -turn)
                    .add(new Point(n / 2, 0, n / 2));
        }

        public Tile adjacent(int dx, int dz) {
            double n = shape.size();
            var p = local(new Point((x + dx + .5) * n + .5, 0, (z + dz + .5) * n + .5));
            var r =
                    shape.topology()
                            .canonical(
                                    new Cell(
                                            face,
                                            (int) Math.floor(p.x()),
                                            0,
                                            (int) Math.floor(p.z())));
            return new Tile(
                    shape, r.cell().face(), Math.floorMod(turn - r.turns(), 4), x + dx, z + dz);
        }
    }

    public final PlanetShape shape;
    public final Face root;
    private final List<Tile> tiles;

    public PlanetChart(PlanetShape shape, Face root) {
        this.shape = Objects.requireNonNull(shape);
        this.root = Objects.requireNonNull(root);
        var t = new Tile(shape, root, 0, 0, 0);
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
}
