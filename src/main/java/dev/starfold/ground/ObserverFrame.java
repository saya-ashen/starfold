package dev.starfold.ground;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.ChartOrientation;
import dev.starfold.planet.PlanetShape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** One observer's continuous client coordinates ("plane") over the cube surface.
 * The client world is the development of the faces along the path actually travelled: the plane
 * equals the root face's storage chart rotated by {@code turns} quarter turns about Y and shifted
 * by a chunk-aligned translation. Crossing a face seam re-roots the frame without moving anything
 * on the plane, so the client is never teleported and its chunks are never reloaded.
 * Storage, simulation and saving stay canonical; this is a wire/display relabelling only. */
public record ObserverFrame(int planet, int root, int turns, int tx, int tz) {
    public ObserverFrame {
        PlanetShape.valid(planet);
        if (root < 0 || root >= 6) throw new IllegalArgumentException("Frame root");
        turns = Math.floorMod(turns, 4);
        if ((tx & 15) != 0 || (tz & 15) != 0)
            throw new IllegalArgumentException("Frame translation must keep the chunk grid");
    }

    /** Plane == storage: the frame of a fresh session, a far teleport or another planet. */
    public static ObserverFrame identity(int planet, int root) {
        return new ObserverFrame(planet, root, 0, 0, 0);
    }

    public boolean isIdentity() {
        return turns == 0 && tx == 0 && tz == 0;
    }

    private static Point rotate(Point p, int turns) {
        return ChartLabSpace.rotate(p, turns);
    }

    /** Storage position in the root chart (halo included) -> plane. */
    public Point toClient(Point storage) {
        var r = rotate(storage, turns);
        return new Point(r.x() + tx, r.y(), r.z() + tz);
    }

    public Point toStorage(Point client) {
        return rotate(new Point(client.x() - tx, client.y(), client.z() - tz), -turns);
    }

    /** Block cells map by their centres, so a rotated cell keeps exactly one image cell. */
    public BlockPos toClient(BlockPos storage) {
        var p = toClient(new Point(storage.getX() + .5, storage.getY(), storage.getZ() + .5));
        return new BlockPos((int) Math.floor(p.x()), storage.getY(), (int) Math.floor(p.z()));
    }

    public BlockPos toStorage(BlockPos client) {
        var p = toStorage(new Point(client.getX() + .5, client.getY(), client.getZ() + .5));
        return new BlockPos((int) Math.floor(p.x()), client.getY(), (int) Math.floor(p.z()));
    }

    public Point vectorToClient(Point storage) {
        return rotate(storage, turns);
    }

    public Point vectorToStorage(Point client) {
        return rotate(client, -turns);
    }

    public float yawToClient(float storage) {
        return storage + turns * 90f;
    }

    public float yawToStorage(float client) {
        return client - turns * 90f;
    }

    public BlockState stateToClient(BlockState storage) {
        return ChartOrientation.state(storage, turns);
    }

    public Direction directionToStorage(Direction client) {
        return ChartOrientation.direction(client, -turns);
    }

    /** Re-root after the observer's body moved from a root-chart position to the same physical
     * place expressed in another face's storage chart. {@code transport} is the quarter turn that
     * carries old-chart vectors into the new chart. Every plane position keeps its meaning. */
    public ObserverFrame cross(int newRoot, Point oldStorage, Point newStorage, int transport) {
        int next = Math.floorMod(turns - transport, 4);
        var a = rotate(oldStorage, turns);
        var b = rotate(newStorage, next);
        long x = Math.round(tx + a.x() - b.x()), z = Math.round(tz + a.z() - b.z());
        // Faces are chunk aligned; only a clamped sub-block residue can remain.
        return new ObserverFrame(
                planet,
                newRoot,
                next,
                (int) (Math.round(x / 16.0) * 16),
                (int) (Math.round(z / 16.0) * 16));
    }
}
