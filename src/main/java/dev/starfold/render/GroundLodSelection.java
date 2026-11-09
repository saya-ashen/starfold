package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.lod.VoxelLodPolicy;

import java.util.*;
import java.util.function.*;

/** Incremental nearest-first flood over canonical tiles, including all face seams/corners. */
final class GroundLodSelection {
    record Tile(long key, double distance, boolean edited) {
        Tile(long key,double distance) { this(key,distance,false); }
    }

    static final Comparator<Tile> REBUILD_ORDER=Comparator.comparing((Tile t)->!t.edited())
            .thenComparingDouble(Tile::distance).thenComparingLong(Tile::key);

    private final PriorityQueue<Tile> frontier =
            new PriorityQueue<>(
                    Comparator.comparingDouble(Tile::distance).thenComparingLong(Tile::key));
    private final BitSet visited = new BitSet(98304);
    private final int[] levels = new int[98304];
    static final long ZOOM_HOLD_NS=30_000_000_000L;
    private final long[] fineUntil=new long[98304];
    /** Selection inputs are spatial, not render-frame events; fades/uploads stay frame-driven. */
    static final class Refresh {
        private int face = -1, x, y, z, height;
        private double fov = 70, lookX, lookY, lookZ;
        private long version, zoomUntil;

        boolean update(PlanetShape.Pose pose, double fov, int height, long version,
                double lookX, double lookY, double lookZ, long now) {
            int x = (int)Math.floor(pose.point().x() / 4),
                    y = (int)Math.floor(pose.point().y() / 4),
                    z = (int)Math.floor(pose.point().z() / 4);
            boolean changed = face != pose.face().ordinal() || this.x != x || this.y != y || this.z != z
                    || this.fov != fov || this.height != height || this.version != version
                    || fov < 30 && (this.lookX != lookX || this.lookY != lookY || this.lookZ != lookZ)
                    || zoomUntil != 0 && now >= zoomUntil;
            if (this.fov < 30 && fov >= 30) zoomUntil = now + ZOOM_HOLD_NS;
            else if (zoomUntil != 0 && now >= zoomUntil) zoomUntil = 0;
            face = pose.face().ordinal(); this.x = x; this.y = y; this.z = z;
            this.fov = fov; this.height = height; this.version = version;
            this.lookX = lookX; this.lookY = lookY; this.lookZ = lookZ;
            return changed;
        }
    }

    static final double RESTART = 512;
    private Point origin, anchor;
    private PlanetShape shape;
    private double fov=70;
    private int height=720;
    private long zoomStarted;

    // Hidden tiles keep the L16–L64 fallback instead of consuming resident mesh budget.
    private LongPredicate visible = key -> true;

    /** The display's own visibility; its curved disk reaches far past the sphere's horizon. */
    void visible(LongPredicate visible) { this.visible = visible; }

    void projection(double fov,int height) { projection(fov,height,System.nanoTime()); }

    void projection(double fov,int height,long now) {
        if(this.fov==fov && this.height==height) return;
        if (this.fov >= 30 && fov < 30) zoomStarted = now;
        if (this.fov < 30 && fov >= 30)
            // Idle zoom no longer revisits every tile: retain its fine meshes from zoom-out, not
            // from the last spatial refresh. This scan runs only on the projection transition.
            for (int i=0; i<fineUntil.length; i++)
                if (fineUntil[i] >= zoomStarted + ZOOM_HOLD_NS) fineUntil[i] = now + ZOOM_HOLD_NS;
        this.fov=fov; this.height=height; anchor=null;
    }

    GroundLodSelection() {
        Arrays.fill(levels, -1);
    }

    /** Restarts the flood (and lets the caller drop queued requests) only after a long move. Small moves
     *  keep the queue: built tiles are re-levelled by maintenance, and restarting every few blocks while
     *  flying would keep the flood from ever reaching the outer rings. */
    boolean move(
            PlanetShape shape, PlanetShape.Pose pose, Point camera, LongToDoubleFunction height) {
        if (anchor != null && this.shape == shape && anchor.sub(camera).length() < RESTART) {
            origin = camera;
            return false;
        }
        long key =
                GroundOwnership.key(
                        shape,
                        pose.face().ordinal(),
                        (int) pose.point().x(),
                        (int) pose.point().z());
        anchor = origin = camera;
        this.shape=shape;
        frontier.clear();
        visited.clear();
        offer(shape, key, height);
        return true;
    }

    static double distance(PlanetShape shape, Point camera, long key, double height) {
        return VoxelLodPolicy.distance(
                shape,
                camera,
                Face.VALUES[(int) (key / 16384)],
                (int) (key % 128) * 16,
                (int) (key / 128 % 128) * 16,
                height);
    }

    int level(long key, double distance) { return level(key,distance,System.nanoTime()); }

    int level(long key,double distance,long now) {
        if(!visible.test(key)) return 0;
        int previous=levels[(int)key];
        int next=VoxelLodPolicy.step(distance,previous,fov,height);
        if(fov<30) fineUntil[(int)key]=now+ZOOM_HOLD_NS;
        if(previous>0 && (next==0||next>previous) && now<fineUntil[(int)key]) return previous;
        return levels[(int)key]=next;
    }

    void step(
            PlanetShape shape,
            long deadline,
            LongSupplier clock,
            LongToDoubleFunction height,
            BiConsumer<Tile, Integer> output) {
        int work = 0;
        while (!frontier.isEmpty() && work++ < 128 && clock.getAsLong() < deadline) {
            var tile = frontier.remove();
            int step = level(tile.key(), tile.distance());
            if (step != 0) output.accept(tile, step);
            for (long neighbour : GroundStreaming.neighbours(shape, tile.key()))
                offer(shape, neighbour, height);
        }
    }

    private void offer(PlanetShape shape, long key, LongToDoubleFunction height) {
        if (visited.get((int) key)) return;
        visited.set((int) key);
        frontier.add(new Tile(key, distance(shape, origin, key, height.applyAsDouble(key))));
    }

    boolean done() {
        return frontier.isEmpty();
    }
}
