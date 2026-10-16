package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetSpace;

import org.joml.Quaterniond;

/** One planet's size, and everything that follows from it. A planet is six square faces of size blocks; its
 *  radius, its topology and the mapping between a face and a direction on the sphere are all derived here, so
 *  that a planet of another size is another instance and not another set of constants. Storage charts are laid
 *  out for faces of up to MAX blocks, which a smaller planet simply does not fill. */
public record PlanetShape(int size) {
    public static final int MIN = 256, MAX = GroundSpace.SIZE;

    /** A vessel in deep space is still placed over the planet it belongs to, however far that is: suns stand up to
     *  a million blocks apart, several cells away. */
    public static final double MAX_HEIGHT = 1.0e7;

    /** No planet's floor lies lower than this: a pose is only checked against the range any planet may have. */
    public static final double LOWEST = -1024;

    /** Blocks from the nominal surface down to the floor nobody digs through, as every planet had before depths were
     *  saved: the vanilla range, whatever the planet's size. */
    public static final int STANDARD_DEPTH = 191;

    /** As many planets as one level's storage addresses. */
    public static final int MAX_PLANETS = 810_000;

    private static final CubeSurfaceTopology[] TOPOLOGIES = new CubeSurfaceTopology[MAX / 16 + 1];

    static {
        for (int size = MIN; size <= MAX; size += 16)
            TOPOLOGIES[size / 16] = new CubeSurfaceTopology(size);
    }

    /** The size every planet had before sizes were saved with a planet's generation. Code that still assumes one
     *  size for all planets says so by naming this. */
    public static final PlanetShape STANDARD = new PlanetShape(2048);

    /** The shapes of the planets of the system now running: set from the saved generator when its level loads, and on
     *  a client from the server. Addressing that only knows which planet a position belongs to reads them here. */
    private static volatile PlanetShape[] SYSTEM = {STANDARD, STANDARD};

    public static PlanetShape of(int planet) {
        return SYSTEM[valid(planet)];
    }

    /** How many planets the running system has. */
    public static int count() {
        return SYSTEM.length;
    }

    public static int valid(int planet) {
        if (planet < 0 || planet >= SYSTEM.length) throw new IllegalArgumentException("Planet");
        return planet;
    }

    /** Replaces the whole table: one shape and one depth for each planet of a system, in storage order. */
    public static void system(int[] sizes, int[] depths) {
        if (sizes.length < 1 || sizes.length > MAX_PLANETS || depths.length != sizes.length)
            throw new IllegalArgumentException("Planet count " + sizes.length);
        var shapes = new PlanetShape[sizes.length];
        for (int id = 0; id < sizes.length; id++) {
            shapes[id] = new PlanetShape(sizes[id]);
            if (depths[id] < STANDARD_DEPTH || depths[id] >= shapes[id].radius())
                throw new IllegalArgumentException("Planet depth " + depths[id]);
        }
        DEPTHS = depths.clone();
        SYSTEM = shapes;
    }

    /** How deep each of those planets goes below its nominal surface, set along with its shape. */
    private static volatile int[] DEPTHS = {STANDARD_DEPTH, STANDARD_DEPTH};

    public static void assign(int planet, PlanetShape shape) {
        assign(planet, shape, STANDARD_DEPTH);
    }

    public static void assign(int planet, PlanetShape shape, int depth) {
        if (depth < STANDARD_DEPTH || depth >= shape.radius())
            throw new IllegalArgumentException("Planet depth " + depth);
        SYSTEM[valid(planet)] = shape;
        DEPTHS[planet] = depth;
    }

    public static void reset() {
        SYSTEM = new PlanetShape[] {STANDARD, STANDARD};
        DEPTHS = new int[] {STANDARD_DEPTH, STANDARD_DEPTH};
    }

    public static int depth(int planet) {
        return DEPTHS[valid(planet)];
    }

    /** Height of a planet's floor, measured like a pose's from the sphere of its radius. */
    public static double floorHeight(int planet) {
        return -depth(planet) - 1;
    }

    /** Everything within this distance of a planet's centre lies below its floor: solid, whatever was dug above. */
    public static double coreRadius(int planet) {
        return of(planet).radius() + floorHeight(planet) - .01;
    }

    /** A chart shows the face and one face's width of each neighbour around it. */
    public boolean inChart(int u, int v) {
        return topology().inHalo(u, v);
    }

    public PlanetShape {
        if (size < MIN || size > MAX || size % 16 != 0)
            throw new IllegalArgumentException("Planet size " + size);
    }

    public double half() {
        return size / 2.0;
    }

    /** A face's edge is a quarter of a great circle. */
    public double radius() {
        return size * 2 / Math.PI;
    }

    /** Chunks along a face's edge. */
    public int chunks() {
        return size / 16;
    }

    public CubeSurfaceTopology topology() {
        return TOPOLOGIES[size / 16];
    }

    public boolean owned(double u, double v) {
        return u >= 0 && u < size && v >= 0 && v < size;
    }

    /** A point of any face's chart, on or beyond the face, as the face and point that own it. */
    public Transport canonical(Face root, Point point) {
        if (root == null || point == null || !point.finite())
            throw new IllegalArgumentException("Native point");
        int u = (int) Math.floor(point.x()), v = (int) Math.floor(point.z());
        var route = topology().canonical(new Cell(root, u, 0, v));
        var d =
                ChartLabSpace.rotate(
                        new Point(point.x() - u - .5, 0, point.z() - v - .5), route.turns());
        double last = Math.nextDown((double) size);
        return new Transport(
                new Pose(
                        route.cell().face(),
                        new Point(
                                Math.clamp(route.cell().u() + .5 + d.x(), 0, last),
                                point.y(),
                                Math.clamp(route.cell().v() + .5 + d.z(), 0, last))),
                route.turns());
    }

    /** Unit direction from the planet's centre through a point of a face. */
    public Point direction(Face face, double u, double v) {
        if (u < 0 || u > size || v < 0 || v > size) {
            var p = canonical(face, new Point(u, 0, v)).pose();
            face = p.face();
            u = p.point().x();
            v = p.point().z();
        }
        return PlanetSpace.unit(
                face.normal
                        .scale(half())
                        .add(face.u.scale(u - half()))
                        .add(face.v.scale(v - half())));
    }

    /** Planet-centred position of a pose: its height is measured from the sphere of this planet's radius. */
    public Point position(Pose pose) {
        return direction(pose.face(), pose.point().x(), pose.point().z())
                .scale(radius() + pose.point().y());
    }

    public Pose locate(Point planet) {
        if (planet == null
                || !planet.finite()
                || planet.length() < radius() + LOWEST
                || planet.length() > radius() + MAX_HEIGHT)
            throw new IllegalArgumentException("Outside planet shell");
        Face face = Face.TOP;
        for (var other : Face.values())
            if (planet.dot(other.normal) > planet.dot(face.normal)) face = other;
        double scale = half() / planet.dot(face.normal), last = Math.nextDown((double) size);
        return new Pose(
                face,
                new Point(
                        Math.clamp(half() + planet.dot(face.u) * scale, 0, last),
                        planet.length() - radius(),
                        Math.clamp(half() + planet.dot(face.v) * scale, 0, last)));
    }

    /** A place on a planet: the face that owns it, and a point of that face with its height from the nominal surface. */
    public record Pose(Face face, Point point) {
        public Pose {
            if (face == null
                    || point == null
                    || !point.finite()
                    || point.x() < 0
                    || point.x() >= GroundSpace.SIZE
                    || point.z() < 0
                    || point.z() >= GroundSpace.SIZE
                    || point.y() < LOWEST
                    || point.y() > MAX_HEIGHT) throw new IllegalArgumentException("Native pose");
        }

        /** The pose the planet under a vessel is drawn from. Deep between suns the vessel is further from its planet than
         *  that view reaches; the planet is then drawn from the edge of its reach, a speck either way. */
        public GroundSpace.Pose renderPose() {
            return new GroundSpace.Pose(
                    face,
                    new Point(
                            point.x(),
                            Math.clamp(point.y(), GroundSpace.MIN, GroundSpace.CEILING),
                            point.z()));
        }
    }

    public record Transport(Pose pose, int turns) {
        public Transport {
            turns = Math.floorMod(turns, 4);
        }

        public Point vector(Point chart) {
            return ChartLabSpace.rotate(chart, turns);
        }

        public Quaterniond aim(Quaterniond chart) {
            return new Quaterniond().rotationY(-turns * Math.PI / 2).mul(chart).normalize();
        }
    }
}
