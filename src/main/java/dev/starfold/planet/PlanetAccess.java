package dev.starfold.planet;

import dev.starfold.chart.PlanetChart;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Storage addresses and face charts for the planets held in one level. */
public final class PlanetAccess {
    public static final PlanetAccess SHARED = new PlanetAccess();

    private PlanetAccess() {}

    public static PlanetAccess of(Level level) {
        return SHARED;
    }

    public boolean inChart(BlockPos pos) {
        int planet = PlanetStorage.find(pos.getX(), pos.getZ());
        return planet >= 0
                && GroundTopology.inChart(PlanetShape.of(planet), PlanetStorage.local(planet, pos));
    }

    public GroundTopology.Address address(BlockPos pos) {
        return PlanetStorage.address(pos).oriented();
    }

    public Cell cell(BlockPos pos) {
        return GroundTopology.cell(PlanetStorage.local(PlanetStorage.planet(pos), pos));
    }

    public GroundTopology.Notification notification(BlockPos target, BlockPos source) {
        return PlanetStorage.notification(target, source);
    }

    public Point feet(int planet, PlanetShape.Pose pose) {
        PlanetShape.valid(planet);
        return PlanetStorage.feet(planet, pose);
    }

    /** A body anchor is already a chart point, not entity feet: no 0.9 centre offset belongs here. */
    public Point bodyAnchor(int planet, PlanetShape.Pose pose) {
        PlanetShape.valid(planet);
        return origin(planet, pose.face().ordinal()).add(pose.point());
    }

    public PlanetShape.Pose body(Point feet) {
        return PlanetStorage.body(PlanetStorage.planet(feet.x(), feet.z()), feet);
    }

    public long key(int chunkX, int chunkZ) {
        return PlanetStorage.key(chunkX, chunkZ);
    }

    public net.minecraft.world.level.ChunkPos chunk(long key) {
        if (key < 0 || key >= (long) PlanetShape.count() * PlanetStorage.CHUNKS_PER_PLANET)
            throw new IllegalArgumentException("Layout chunk key");
        return PlanetStorage.chunk(key);
    }

    /** The planet whose storage holds a place. */
    public int storagePlanet(double x, double z) {
        return PlanetStorage.planet(x, z);
    }

    public int storagePlanet(BlockPos pos) {
        return storagePlanet(pos.getX(), pos.getZ());
    }

    /** Shape of the planet whose storage holds a place. */
    public PlanetShape shape(double x, double z) {
        return PlanetShape.of(PlanetStorage.planet(x, z));
    }

    public PlanetShape shape(BlockPos pos) {
        return shape(pos.getX(), pos.getZ());
    }

    /** The face whose chart a place is in: told from x alone, as the faces of every planet stand along x. */
    public int face(double x) {
        return GroundCoordinates.face(x - PlanetStorage.columnOffset(x));
    }

    public Point originAt(double x, double z) {
        return origin(storagePlanet(x, z), face(x));
    }

    public Point chart(Point storage) {
        return storage.sub(originAt(storage.x(), storage.z()));
    }

    public boolean samePlanet(double ax, double az, double bx, double bz) {
        return PlanetStorage.find(ax, az) == PlanetStorage.find(bx, bz);
    }

    /** Where a place of another face is drawn in the chart of the face an observer is on, or null. */
    public Point image(double observerX, double observerZ, Point target) {
        if (!samePlanet(observerX, observerZ, target.x(), target.z())) return null;
        var root = dev.starfold.geometry.CubeSurfaceTopology.Face.values()[face(observerX)];
        var tile =
                new PlanetChart(shape(observerX, observerZ), root)
                        .tile(
                                dev.starfold.geometry.CubeSurfaceTopology.Face.values()[
                                        face(target.x())]);
        return tile == null ? null : originAt(observerX, observerZ).add(tile.point(chart(target)));
    }

    /** A place in the middle of a face of a planet: an observer there sees that face's chart. */
    public Point middle(int planet, int face) {
        var o = origin(planet, face);
        double half = PlanetShape.of(planet).half();
        return new Point(o.x() + half, o.y(), o.z() + half);
    }

    public Point origin(int planet, int face) {
        return GroundCoordinates.origin(face).add(PlanetStorage.origin(planet));
    }
}
