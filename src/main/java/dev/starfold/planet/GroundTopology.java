package dev.starfold.planet;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartOrientation;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Addresses, not copied blocks. Native vertical storage extends below the old proxy ledger. */
public final class GroundTopology {
    public static final double MIN_HEIGHT = -192;

    /** Only the region below native storage is an unconditional solid solar occluder.
     * Terrain/roofs above it are depth-tested from actual chunk-derived geometry. */
    public static final double SOLAR_CORE_RADIUS = GroundOrbit.RADIUS + MIN_HEIGHT - .01;

    public record Address(BlockPos owner, int turns) {
        public Address {
            owner = owner.immutable();
            turns = Math.floorMod(turns, 4);
        }

        public BlockState read(BlockState canonical) {
            return ChartOrientation.state(canonical, -turns);
        }

        public BlockState write(BlockState query) {
            return ChartOrientation.state(query, turns);
        }
    }

    public record Notification(BlockPos target, BlockPos source, int turns) {}

    // Each function below exists for a planet of a given shape; the form without one is the
    // standard size, which is
    // every planet of a world saved before sizes existed.
    public static PlanetShape.Transport canonical(Face root, Point point) {
        return canonical(PlanetShape.STANDARD, root, point);
    }

    public static PlanetShape.Transport canonical(PlanetShape shape, Face root, Point point) {
        return shape.canonical(root, point);
    }

    public static boolean ownedSection(net.minecraft.world.phys.AABB box, int observer) {
        return ownedSection(PlanetShape.STANDARD, box, observer);
    }

    public static boolean ownedSection(
            PlanetShape shape, net.minecraft.world.phys.AABB box, int observer) {
        var p = box.getCenter();
        return GroundCoordinates.face(p.x) == observer
                && GroundCoordinates.owned(shape, new Point(p.x, p.y, p.z));
    }

    public static Point world(PlanetShape.Pose pose) {
        return GroundCoordinates.world(pose.face(), pose.point());
    }

    public static PlanetShape.Pose body(Point feet) {
        return body(PlanetShape.STANDARD, feet);
    }

    public static PlanetShape.Pose body(PlanetShape shape, Point feet) {
        int face = GroundCoordinates.face(feet.x());
        return canonical(
                        shape,
                        Face.values()[face],
                        feet.sub(GroundCoordinates.origin(face)).add(new Point(0, .9, 0)))
                .pose();
    }

    public static Point feet(PlanetShape.Pose pose) {
        return world(pose).sub(new Point(0, .9, 0));
    }

    public static Cell cell(BlockPos position) {
        int face = GroundCoordinates.face(position.getX());
        return new Cell(
                Face.values()[face],
                position.getX() - face * GroundCoordinates.STRIDE,
                position.getY() - GroundCoordinates.FLOOR,
                position.getZ());
    }

    public static BlockPos block(Cell cell) {
        return block(PlanetShape.STANDARD, cell);
    }

    public static BlockPos block(PlanetShape shape, Cell cell) {
        if (!shape.topology().owned(cell))
            throw new IllegalArgumentException("Unowned native address");
        return new BlockPos(
                cell.face().ordinal() * GroundCoordinates.STRIDE + cell.u(),
                GroundCoordinates.FLOOR + cell.h(),
                cell.v());
    }

    public static Address address(BlockPos query) {
        return address(PlanetShape.STANDARD, query);
    }

    public static Address address(PlanetShape shape, BlockPos query) {
        var r = shape.topology().canonical(cell(query));
        return new Address(block(shape, r.cell()), r.turns());
    }

    /** Source stays relative in the TARGET chart, including a ghost source at an edge. */
    public static Notification notification(BlockPos target, BlockPos source) {
        return notification(PlanetShape.STANDARD, target, source);
    }

    public static Notification notification(PlanetShape shape, BlockPos target, BlockPos source) {
        var t = cell(target);
        var delta = source.subtract(target);
        var n = shape.topology().notification(t, t.add(delta.getX(), delta.getY(), delta.getZ()));
        var real = block(shape, n.target());
        var d =
                new BlockPos(
                        n.source().u() - n.target().u(),
                        n.source().h() - n.target().h(),
                        n.source().v() - n.target().v());
        return new Notification(real, real.offset(d), n.turns());
    }

    public static Point planet(PlanetShape.Pose pose) {
        return GroundOrbit.direction(pose.face(), pose.point().x(), pose.point().z())
                .scale(GroundOrbit.RADIUS + pose.point().y());
    }

    public static Point planet(PlanetShape shape, PlanetShape.Pose pose) {
        return shape.position(pose);
    }

    public static PlanetShape.Pose locate(Point planet) {
        var p = GroundOrbit.locate(planet);
        return new PlanetShape.Pose(p.face(), p.point());
    }

    public static PlanetShape.Pose locate(PlanetShape shape, Point planet) {
        return shape.locate(planet);
    }

    /** Exact differential: chart units are not assumed to equal radial-body units. */
    private static Point[] jacobian(PlanetShape shape, PlanetShape.Pose pose) {
        var f = pose.face();
        var p = pose.point();
        var cube =
                f.normal
                        .scale(shape.half())
                        .add(f.u.scale(p.x() - shape.half()))
                        .add(f.v.scale(p.z() - shape.half()));
        var n = PlanetSpace.unit(cube);
        double scale = (shape.radius() + p.y()) / cube.length();
        return new Point[] {
            f.u.sub(n.scale(f.u.dot(n))).scale(scale), n, f.v.sub(n.scale(f.v.dot(n))).scale(scale)
        };
    }

    public static Point planetVelocity(PlanetShape.Pose pose, Point chartVelocity) {
        return planetVelocity(PlanetShape.STANDARD, pose, chartVelocity);
    }

    public static Point planetVelocity(
            PlanetShape shape, PlanetShape.Pose pose, Point chartVelocity) {
        var j = jacobian(shape, pose);
        return j[0].scale(chartVelocity.x())
                .add(j[1].scale(chartVelocity.y()))
                .add(j[2].scale(chartVelocity.z()));
    }

    public static Point chartVelocity(PlanetShape.Pose pose, Point planetVelocity) {
        return chartVelocity(PlanetShape.STANDARD, pose, planetVelocity);
    }

    public static Point chartVelocity(
            PlanetShape shape, PlanetShape.Pose pose, Point planetVelocity) {
        var j = jacobian(shape, pose);
        double a = j[0].dot(j[0]), b = j[0].dot(j[2]), c = j[2].dot(j[2]), det = a * c - b * b;
        if (!(det > 1e-12)) throw new IllegalArgumentException("Degenerate native tangent");
        double u = planetVelocity.dot(j[0]), v = planetVelocity.dot(j[2]);
        return new Point((c * u - b * v) / det, planetVelocity.dot(j[1]), (a * v - b * u) / det);
    }

    private GroundTopology() {}

    /** Whether a storage position lies in a chart: on its face or in the one face's width shown around it. */
    public static boolean inChart(BlockPos pos) {
        return inChart(PlanetShape.STANDARD, pos);
    }

    public static boolean inChart(PlanetShape shape, BlockPos pos) {
        var cell = cell(pos);
        return shape.inChart(cell.u(), cell.v());
    }
}
