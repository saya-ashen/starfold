package dev.starfold.worldgen;

import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundCoordinates;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetCore;
import dev.starfold.planet.PlanetShape;
import dev.starfold.render.GroundChunkSnapshot;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Versioned, immutable global height field. Face/chart identity never enters the noise. */
public final class PlanetTerrain {
    public static final int VERSION = 2, MIN_Y = -64, MAX_Y = 320, SEA_LEVEL = 128;
    public static final long TEST_SEED = 20261001L;
    public static final int MINING_VERSION = 3, ECOLOGY_VERSION = 4, ROCK_VERSION = 5;

    /** The one definition of a body's sea. Real columns are filled from it and every derived view reads it. */
    public record Sea(int level, BlockState fluid) {
        public double elevation() {
            return level - 128;
        }
    }

    private final long seed;
    private final int version;
    private final PlanetGeneration generation;
    private final PlanetShape shape;
    private final int floor;

    /** Above this the ground is bare stone; it rises with ground raised against the sea. */
    private final int peaks;

    private final BlockState shell;
    private final Sea sea;
    private final PlanetRock rock;
    private final PlanetUnderground underground;
    private final PlanetEcology ecology;
    private final java.util.LinkedHashMap<Long, Column> columns =
            new java.util.LinkedHashMap<>(16, .75f, true);

    public PlanetTerrain(long seed) {
        this(seed, VERSION);
    }

    public PlanetTerrain(long seed, int version) {
        this(seed, version, PlanetGeneration.defaults(version));
    }

    public PlanetTerrain(long seed, int version, PlanetGeneration generation) {
        if (!supported(version) || generation == null)
            throw new IllegalArgumentException("Unsupported planet terrain version: " + version);
        generation.validate(version);
        this.seed = seed;
        this.version = version;
        this.generation = generation;
        shape = generation.shape();
        floor = generation.floor();
        peaks = 157 + (int) Math.round(Math.max(0, generation.climate().land()));
        shell = generation.coreShell() ? PlanetCore.shell() : Blocks.BEDROCK.defaultBlockState();
        sea = generation.rocky() ? null : new Sea(SEA_LEVEL, Blocks.WATER.defaultBlockState());
        rock = generation.rocky() ? new PlanetRock(this) : null;
        underground =
                version >= MINING_VERSION && rock == null ? new PlanetUnderground(this) : null;
        ecology = version == ECOLOGY_VERSION ? new PlanetEcology(this) : null;
    }

    public static boolean supported(int version) {
        return version == VERSION
                || version == MINING_VERSION
                || version == ECOLOGY_VERSION
                || version == ROCK_VERSION;
    }

    public long seed() {
        return seed;
    }

    public int version() {
        return version;
    }

    public PlanetGeneration generation() {
        return generation;
    }

    public PlanetShape shape() {
        return shape;
    }

    public PlanetEcology ecology() {
        return ecology;
    }

    /** The lowest layer of this planet, which is its floor; MIN_Y on a planet of the standard depth. */
    public int floor() {
        return floor;
    }

    public int peaks() {
        return peaks;
    }

    /** The height that decides what a layer is made of. A deeper planet stretches the standard planet's layers over
     *  its own depth, so its ores lie proportionally as deep; on a planet of the standard depth this is y itself. */
    public int layer(int y) {
        int depth = generation.depth();
        return depth == PlanetGeneration.STANDARD_DEPTH || y >= SEA_LEVEL - 1
                ? y
                : SEA_LEVEL
                        - 1
                        - (int)
                                Math.round(
                                        (SEA_LEVEL - 1 - y)
                                                * (double) PlanetGeneration.STANDARD_DEPTH
                                                / depth);
    }

    /** Null on a dry body: no layer may assume a sea from height alone. */
    public Sea sea() {
        return sea;
    }

    /** Top block of a bare generated column: the ground, or the sea surface above it. */
    public int surfaceTop(int ground) {
        return sea == null ? ground : Math.max(sea.level() - 1, ground);
    }

    public boolean closure(Face face, int u, int v) {
        return Anomaly.column(
                shape, GroundSpace.DEFAULT_MASK, new CubeSurfaceTopology.Cell(face, u, 0, v));
    }

    public int closureTop() {
        return GroundCoordinates.FLOOR + GroundSpace.DEFAULT_MASK.height() - 1;
    }

    public int surfaceY(Face face, int u, int v) {
        if (closure(face, u, v)) return closureTop();
        return ecology == null ? surfaceTop(groundY(face, u, v)) : ecology.surfaceY(face, u, v);
    }

    public static final class Column {
        private final int ground, floor;
        private final BlockState[] states;

        private Column(int ground, int floor, BlockState[] states) {
            this.ground = ground;
            this.floor = floor;
            this.states = states;
        }

        public int ground() {
            return ground;
        }

        public BlockState state(int y) {
            return y < floor || y >= MAX_Y ? Blocks.AIR.defaultBlockState() : states[y - floor];
        }
    }

    /** Bounded immutable baseline memo only; never stores edits or substitutes for a LevelChunk. */
    public Column column(Face face, int u, int v) {
        if (!shape.owned(u, v)) {
            var cell =
                    shape.topology().canonical(new CubeSurfaceTopology.Cell(face, u, 0, v)).cell();
            face = cell.face();
            u = cell.u();
            v = cell.v();
        }
        long key = u + 2048L * (v + 2048L * face.ordinal());
        synchronized (columns) {
            var hit = columns.get(key);
            if (hit != null) return hit;
        }
        int ground = groundY(face, u, v);
        var states = new BlockState[MAX_Y - floor];
        var direction = unit(face, u + .5, v + .5);
        var cells = lattices();
        for (int y = floor; y < MAX_Y; y++) {
            var base =
                    rock != null && generation.exploration() && y >= ground - 3 && y <= ground
                            ? rock.surface(direction, ground, y)
                            : state(ground, y);
            states[y - floor] =
                    y > floor && y < ground - 3
                            ? (rock != null
                                    ? rock.underground(
                                            cells,
                                            direction.scale(shape.half() + y - 128),
                                            ground,
                                            y)
                                    : underground != null
                                            ? underground.state(
                                                    cells,
                                                    underground.sample(direction, y),
                                                    ground,
                                                    y)
                                            : base)
                            : base;
        }
        if (ecology != null) {
            var decoration = ecology.decoration(face, u, v);
            for (int y = ground - 3; y <= ground; y++)
                states[y - floor] = decoration.soil(y, states[y - floor]);
            for (int y = ground + 1; y < Math.min(MAX_Y, ground + 11); y++)
                if (states[y - floor].isAir()) states[y - floor] = decoration.above(y);
        }
        if (generation.minerals().shallowAndesite())
            for (int y = Math.max(floor + 1, ground - PlanetMinerals.SHALLOW_DEPTH);
                    y <= ground;
                    y++)
                states[y - floor] =
                        generation
                                .minerals()
                                .shallow(this, cells, direction, ground, y, states[y - floor]);
        var field = Anomaly.surface(Anomaly.distance(direction, shape));
        if (field != null)
            for (int y = Math.max(floor + 1, ground - Anomaly.DEPTH); y <= ground; y++)
                states[y - floor] = y == ground ? field : Anomaly.ground();
        if (closure(face, u, v))
            for (int y = floor; y <= Math.min(closureTop(), MAX_Y - 1); y++)
                states[y - floor] = Anomaly.core(y);
        var result = new Column(ground, floor, states);
        synchronized (columns) {
            columns.put(key, result);
            if (columns.size() > 2048) columns.remove(columns.keySet().iterator().next());
        }
        return result;
    }

    public BlockState surfaceState(Face face, int u, int v) {
        if (closure(face, u, v)) return Anomaly.core(closureTop());
        int ground = groundY(face, u, v);
        if (version >= MINING_VERSION) {
            var field = Anomaly.surface(Anomaly.distance(unit(face, u + .5, v + .5), shape));
            if (field != null) return field;
        }
        BlockState top;
        if (rock != null && generation.exploration())
            top = rock.surface(unit(face, u + .5, v + .5), ground, ground);
        else if (ecology == null) top = state(ground, surfaceTop(ground));
        else {
            var d = ecology.decoration(face, u, v);
            top =
                    d.top() > ground
                            ? (sea != null && d.top() < sea.level()
                                    ? sea.fluid()
                                    : d.above(d.top()))
                            : d.soil(ground, solidTop(ground));
        }
        return generation
                .minerals()
                .shallow(this, lattices(), unit(face, u + .5, v + .5), ground, ground, top);
    }

    public int cachedColumns() {
        synchronized (columns) {
            return columns.size();
        }
    }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private double value(int x, int y, int z, long octave) {
        return ((mix(
                                                seed
                                                        ^ octave
                                                        ^ mix(x * 0x9e3779b97f4a7c15L)
                                                        ^ mix(y * 0x632be59bd9b4e019L)
                                                        ^ mix(z * 0x94d049bb133111ebL))
                                        >>> 11)
                                * 0x1.0p-53)
                        * 2
                - 1;
    }

    private static double fade(double x) {
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double x) {
        return a + (b - a) * x;
    }

    /** The eight values around one lattice cell of one octave. Consecutive samples down a column mostly fall in the
     *  cell of the sample before, so a thread keeps the last cell of each octave: the same values, hashed once. */
    static final class Lattice {
        PlanetTerrain owner;
        long octave;
        int a, b, c;
        final double[] v = new double[8];
    }

    private static final ThreadLocal<Lattice[]> LATTICES =
            ThreadLocal.withInitial(
                    () -> {
                        var cells = new Lattice[64];
                        for (int i = 0; i < cells.length; i++) cells[i] = new Lattice();
                        return cells;
                    });

    /** A thread's cells, fetched once by whoever samples a whole column. */
    static Lattice[] lattices() {
        return LATTICES.get();
    }

    double noise(Point p, double scale, long octave) {
        return noise(LATTICES.get(), p, scale, octave);
    }

    double noise(Lattice[] cells, Point p, double scale, long octave) {
        double x = p.x() * scale, y = p.y() * scale, z = p.z() * scale;
        int a = (int) Math.floor(x), b = (int) Math.floor(y), c = (int) Math.floor(z);
        double u = fade(x - a), v = fade(y - b), w = fade(z - c);
        var cell = cells[(int) (octave & 63)];
        var n = cell.v;
        if (cell.owner != this
                || cell.octave != octave
                || cell.a != a
                || cell.b != b
                || cell.c != c) {
            cell.owner = this;
            cell.octave = octave;
            cell.a = a;
            cell.b = b;
            cell.c = c;
            n[0] = value(a, b, c, octave);
            n[1] = value(a + 1, b, c, octave);
            n[2] = value(a, b + 1, c, octave);
            n[3] = value(a + 1, b + 1, c, octave);
            n[4] = value(a, b, c + 1, octave);
            n[5] = value(a + 1, b, c + 1, octave);
            n[6] = value(a, b + 1, c + 1, octave);
            n[7] = value(a + 1, b + 1, c + 1, octave);
        }
        return lerp(
                lerp(lerp(n[0], n[1], u), lerp(n[2], n[3], u), v),
                lerp(lerp(n[4], n[5], u), lerp(n[6], n[7], u), v),
                w);
    }

    public double elevation(Point unit) {
        if (!unit.finite() || Math.abs(unit.length() - 1) > 1e-8)
            throw new IllegalArgumentException("Planet direction");
        double anomaly = Anomaly.distance(unit, shape);
        if (anomaly >= Anomaly.PLATEAU_OUTER) return generated(unit);
        var twisted = Anomaly.twisted(unit, anomaly);
        return Anomaly.elevation(generated(twisted), anomaly)
                + Anomaly.warp(anomaly)
                        * (field(twisted, 70, 5) * .7 + field(twisted, 160, 6) * .3);
    }

    private double generated(Point unit) {
        if (rock != null) return rock.elevation(unit);
        double continental = field(unit, 2.1, 1), ridge = 1 - Math.abs(field(unit, 4.8, 2));
        double mountains = Math.max(0, continental + .18) * ridge * ridge * 46;
        double land = generation.climate().land();
        // Ground raised against the sea keeps its relief: the heights it may reach rise, and the
        // depths sink, with it.
        return Math.clamp(
                continental * 24
                        - 4
                        + land
                        + mountains
                        + field(unit, 18, 3) * 1.5
                        + field(unit, 43, 4) * .5,
                -24 + Math.min(0, land),
                48 + Math.max(0, land));
    }

    /** Noise over the sphere, in features of the same size in blocks whatever the planet's size. */
    double field(Point unit, double scale, long octave) {
        return noise(unit, scale * (shape.size() / (double) PlanetShape.STANDARD.size()), octave);
    }

    /** Direction of a face point of a planet of the standard size; the face's plane is extended beyond its edges. */
    public static Point direction(Face face, double u, double v) {
        return direction(PlanetShape.STANDARD, face, u, v);
    }

    public static Point direction(PlanetShape shape, Face face, double u, double v) {
        double h = shape.half();
        return face.normal
                .scale(h)
                .add(face.u.scale(u - h))
                .add(face.v.scale(v - h))
                .scale(1 / Math.sqrt(h * h + (u - h) * (u - h) + (v - h) * (v - h)));
    }

    /** Direction of a face point of this planet. */
    public Point unit(Face face, double u, double v) {
        return direction(shape, face, u, v);
    }

    public double elevation(Face face, double u, double v) {
        return elevation(unit(face, u, v));
    }

    /** Cells sample their centre; shared edge nodes sample the very same global direction. */
    /** The share of a planet's surface that lies under its sea, measured over a grid of its own ground. */
    public double oceanShare() {
        if (sea == null) return 0;
        int under = 0, all = 0;
        for (var face : Face.values())
            for (int a = 0; a < SAMPLES; a++)
                for (int b = 0; b < SAMPLES; b++) {
                    double u = (a + .5) * shape.size() / SAMPLES,
                            v = (b + .5) * shape.size() / SAMPLES;
                    all++;
                    if (127 + (int) Math.floor(elevation(face, u, v)) < sea.level()) under++;
                }
        return under / (double) all;
    }

    private static final int SAMPLES = 24;

    /** How far the ground must be raised for that share of this planet to lie under its sea: the height below which
     *  that share of its ground lies is brought to the shore. Measured on the planet as its seed makes it. */
    public double landFor(double ocean) {
        if (sea == null || generation.climate().land() != 0)
            throw new IllegalStateException(
                    "Measured on a planet with a sea and its ground where it was");
        var heights = new double[6 * SAMPLES * SAMPLES];
        int at = 0;
        for (var face : Face.values())
            for (int a = 0; a < SAMPLES; a++)
                for (int b = 0; b < SAMPLES; b++)
                    heights[at++] =
                            elevation(
                                    face,
                                    (a + .5) * shape.size() / SAMPLES,
                                    (b + .5) * shape.size() / SAMPLES);
        java.util.Arrays.sort(heights);
        // No sea at all: the ground is raised as far as it goes, above what a grid of samples could
        // miss.
        if (ocean <= 0) return PlanetClimate.MAX_LAND;
        return Math.clamp(
                1 - heights[(int) Math.min(heights.length - 1, Math.floor(ocean * heights.length))],
                -PlanetClimate.MAX_LAND,
                PlanetClimate.MAX_LAND);
    }

    public int groundY(Face face, int u, int v) {
        return 127 + (int) Math.floor(elevation(face, u + .5, v + .5));
    }

    public BlockState solidTop(int y) {
        if (rock != null) return Blocks.STONE.defaultBlockState();
        return y < SEA_LEVEL + 2
                ? Blocks.SAND.defaultBlockState()
                : y > peaks
                        ? Blocks.STONE.defaultBlockState()
                        : Blocks.GRASS_BLOCK.defaultBlockState();
    }

    public BlockState state(int ground, int y) {
        if (y < floor || y >= MAX_Y) return Blocks.AIR.defaultBlockState();
        if (y == floor) return shell;
        if (y <= ground) {
            if (y == ground) return solidTop(ground);
            if (y >= ground - 3)
                return rock != null
                        ? Blocks.STONE.defaultBlockState()
                        : ground < SEA_LEVEL + 2
                                ? Blocks.SAND.defaultBlockState()
                                : ground > peaks
                                        ? Blocks.STONE.defaultBlockState()
                                        : Blocks.DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        return sea != null && y < sea.level() ? sea.fluid() : Blocks.AIR.defaultBlockState();
    }

    public BlockState state(Face face, int u, int y, int v) {
        if (y >= floor && y <= closureTop() && closure(face, u, v)) return Anomaly.core(y);
        return version == VERSION ? state(groundY(face, u, v), y) : column(face, u, v).state(y);
    }

    public boolean matches(GroundChunkSnapshot image) {
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                var face = Face.values()[image.face()];
                int u = image.u() * 16 + x, v = image.v() * 16 + z;
                int ground = groundY(face, u, v);
                var col = version >= MINING_VERSION ? column(face, u, v) : null;
                for (int y = MIN_Y; y < MAX_Y; y++)
                    if (image.state(x, y, z) != (col == null ? state(face, u, y, v) : col.state(y)))
                        return false;
            }
        return true;
    }
}
