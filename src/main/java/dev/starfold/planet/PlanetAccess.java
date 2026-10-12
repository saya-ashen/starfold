package dev.starfold.planet;

import dev.starfold.chart.PlanetChart;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.ground.GroundCoordinates;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundTopology;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Explicit layout at the real Level boundary. Never infer a layout from a large X alone.
 * Legacy worlds retain their old interpretation, including empty storage gaps.
 */
public enum PlanetAccess {
    LEGACY,
    SHARED;

    public static PlanetAccess of(Level level) {
        return PlanetSystem.shared(level) ? SHARED : LEGACY;
    }

    public boolean inChart(BlockPos pos) {
        if (this == LEGACY) return GroundSeams.inChart(pos);
        int planet = PlanetStorage.find(pos.getX(), pos.getZ());
        return planet >= 0
                && GroundSeams.inChart(PlanetShape.of(planet), PlanetStorage.local(planet, pos));
    }

    public GroundTopology.Address address(BlockPos pos) {
        return this == SHARED ? PlanetStorage.address(pos).oriented() : GroundTopology.address(pos);
    }

    public Cell cell(BlockPos pos) {
        return GroundTopology.cell(
                this == SHARED ? PlanetStorage.local(PlanetStorage.planet(pos), pos) : pos);
    }

    public GroundTopology.Notification notification(BlockPos target, BlockPos source) {
        return this == SHARED
                ? PlanetStorage.notification(target, source)
                : GroundTopology.notification(target, source);
    }

    public Point feet(int planet, GroundTopology.Pose pose) {
        PlanetShape.valid(planet);
        return this == SHARED ? PlanetStorage.feet(planet, pose) : GroundTopology.feet(pose);
    }

    /** A body anchor is already a chart point, not entity feet: no 0.9 centre offset belongs here. */
    public Point bodyAnchor(int planet, GroundTopology.Pose pose) {
        PlanetShape.valid(planet);
        return origin(planet, pose.face().ordinal()).add(pose.point());
    }

    public GroundTopology.Pose body(Point feet) {
        return this == SHARED
                ? PlanetStorage.body(PlanetStorage.planet(feet.x(), feet.z()), feet)
                : GroundTopology.body(feet);
    }

    public long key(int chunkX, int chunkZ) {
        return this == SHARED
                ? PlanetStorage.key(chunkX, chunkZ)
                : dev.starfold.storage.ChunkKeys.local(chunkX, chunkZ);
    }

    public net.minecraft.world.level.ChunkPos chunk(long key) {
        if (key < 0
                || key
                        >= (this == SHARED ? (long) PlanetShape.count() : 1L)
                                * PlanetStorage.CHUNKS_PER_PLANET)
            throw new IllegalArgumentException("Layout chunk key");
        return PlanetStorage.chunk(key);
    }

    /** The planet whose storage holds a place. A legacy level holds one. */
    public int storagePlanet(double x, double z) {
        return this == SHARED ? PlanetStorage.planet(x, z) : 0;
    }

    public int storagePlanet(BlockPos pos) {
        return storagePlanet(pos.getX(), pos.getZ());
    }

    /** Shape of the planet whose storage holds a place. A legacy level holds one planet of the standard size. */
    public PlanetShape shape(double x, double z) {
        return this == SHARED ? PlanetShape.of(PlanetStorage.planet(x, z)) : PlanetShape.STANDARD;
    }

    public PlanetShape shape(BlockPos pos) {
        return shape(pos.getX(), pos.getZ());
    }

    /** The face whose chart a place is in: told from x alone, as the faces of every planet stand along x. */
    public int face(double x) {
        return GroundCoordinates.face(x - (this == SHARED ? PlanetStorage.columnOffset(x) : 0));
    }

    public Point originAt(double x, double z) {
        return origin(storagePlanet(x, z), face(x));
    }

    public Point chart(Point storage) {
        return storage.sub(originAt(storage.x(), storage.z()));
    }

    public boolean samePlanet(double ax, double az, double bx, double bz) {
        return this != SHARED || PlanetStorage.find(ax, az) == PlanetStorage.find(bx, bz);
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
        double half = (this == SHARED ? PlanetShape.of(planet) : PlanetShape.STANDARD).half();
        return new Point(o.x() + half, o.y(), o.z() + half);
    }

    public Point origin(int planet, int face) {
        return GroundCoordinates.origin(face)
                .add(this == SHARED ? PlanetStorage.origin(planet) : new Point(0, 0, 0));
    }
}
