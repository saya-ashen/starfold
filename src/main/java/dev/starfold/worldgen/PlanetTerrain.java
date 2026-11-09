package dev.starfold.worldgen;

import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.*;
import dev.starfold.worldgen.plan.PlanetPlan;
import dev.starfold.worldgen.plan.terrain.*;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** One fixed description and one evaluator for every planet type. */
public final class PlanetTerrain {
    public static final int VERSION = 14, MIN_Y = -64, MAX_Y = 320, SEA_LEVEL = 128, SURFACE_DEPTH = 4;
    public static final long TEST_SEED = 20261001L;
    public record Sea(int level, BlockState fluid) {
        public double elevation() {
            return level - 128;
        }
    }
    private final long seed;
    private final PlanetGeneration generation;
    private final PlanetShape shape;
    private final int floor;
    private final BlockState shell;
    private final PlanetUnderground underground;
    private volatile TerrainPlan terrainPlan;
    private java.util.concurrent.CompletableFuture<dev.starfold.worldgen.surface.SurfaceFunction> surfaceFunction;
    /** Non-blocking publication of fully prepared immutable inputs to render/tick consumers. */
    public synchronized dev.starfold.worldgen.surface.SurfaceFunction readySurface() {
        if (surfaceFunction == null)
            surfaceFunction = dev.starfold.worldgen.surface.SurfaceFunction.prepare(this);
        if (surfaceFunction.isCompletedExceptionally())
            throw new IllegalStateException("Surface preparation failed", surfaceFunction.exceptionNow());
        return surfaceFunction.state() == java.util.concurrent.Future.State.SUCCESS ? surfaceFunction.resultNow() : null;
    }
    /** Background/offline coarse readers may wait; ready render inputs take resultNow only. */
    public dev.starfold.worldgen.surface.SurfaceSummary surfaceSummary(dev.starfold.worldgen.surface.SurfaceFunction function) {
        var summary = function.summary();
        return summary.state() == java.util.concurrent.Future.State.SUCCESS ? summary.resultNow() : summary.join();
    }
    private java.util.function.Supplier<PlanetPlan> plan;
    private PlanetPlan localPlan;
    private java.util.function.Supplier<dev.starfold.worldgen.surface.BiomeScatter> scatter;
    private final LinkedHashMap<Long, Column> columns = new LinkedHashMap<>(16, .75f, true);
    public PlanetTerrain(long seed) {
        this(seed, VERSION);
    }
    public PlanetTerrain(long seed, int version) {
        this(seed, version, PlanetGeneration.defaults(version));
    }
    public PlanetTerrain(long seed, int version, PlanetGeneration generation) {
        generation.validate(version);
        this.seed = seed;
        this.generation = generation;
        shape = generation.shape();
        floor = generation.floor();
        shell = generation.coreShell() ? PlanetCore.shell() : Blocks.BEDROCK.defaultBlockState();
        underground = new PlanetUnderground(this);
    }
    public static boolean supported(int version) {
        return version == VERSION;
    }
    public long seed() {
        return seed;
    }
    public int version() {
        return VERSION;
    }
    public PlanetGeneration generation() {
        return generation;
    }
    public PlanetShape shape() {
        return shape;
    }
    public int floor() {
        return floor;
    }
    public TerrainPlan terrainPlan() {
        var value = terrainPlan;
        if (value != null)
            return value;
        synchronized (this) {
            if (terrainPlan == null)
                terrainPlan = new TerrainPlanner(
                    seed, shape.size(), generation.recipe(), generation.environment().temperatureOffset())
                                  .generate();
            return terrainPlan;
        }
    }
    /** Bind before structure replay: L1 must never join the archive future it is currently constructing. */
    public synchronized void initializeTerrain(TerrainPlan value) {
        terrainPlan = value;
        surfaceFunction = null;
        columns.clear();
        localPlan = null;
    }
    public synchronized void initializePlan(java.util.function.Supplier<PlanetPlan> value) {
        plan = value;
        surfaceFunction = null;
    }
    public PlanetPlan plan() {
        if (plan != null)
            return plan.get();
        synchronized (this) {
            if (localPlan == null)
                localPlan = new PlanetPlan(shape.size(), terrainPlan(), List.of(), List.of());
            return localPlan;
        }
    }
    public void initializeSurface(net.minecraft.world.level.biome.BiomeSource biomes,
        net.minecraft.world.level.chunk.ChunkGenerator generator) {
        if (generation.features())
            scatter = com.google.common.base.Suppliers.memoize(
                () -> new dev.starfold.worldgen.surface.BiomeScatter(this, biomes, generator))::get;
    }
    public void initializeSurface(
        dev.starfold.worldgen.surface.SurfacePolicy policy, net.minecraft.resources.ResourceLocation biome) {
        scatter = com.google.common.base.Suppliers.memoize(() -> policy.bind(this, biome))::get;
    }
    public dev.starfold.worldgen.surface.BiomeScatter scatter() {
        return scatter == null ? null : scatter.get();
    }
    public Sea sea() {
        var r = terrainPlan().recipe();
        return r.liquid().defaultBlockState().isAir() || r.value("land_fraction") >= 1
            ? null
            : new Sea((int) r.value("water", "sea_level"), r.liquid().defaultBlockState());
    }
    private CubeSurfaceTopology.Cell canonical(Face f, int u, int v) {
        return shape.topology().canonical(new CubeSurfaceTopology.Cell(f, u, 0, v)).cell();
    }
    public Sea liquid(Face f, int u, int v) {
        var c = canonical(f, u, v);
        if (Anomaly.distance(unit(c.face(), c.u() + .5, c.v() + .5), shape) < Anomaly.FIELD)
            return null;
        double level = terrainPlan().waterLevel(c.face(), c.u(), c.v());
        return !Double.isFinite(level) || terrainPlan().recipe().liquid().defaultBlockState().isAir()
            ? null
            : new Sea((int) Math.ceil(level), terrainPlan().recipe().liquid().defaultBlockState());
    }
    public boolean submerged(Face f, int u, int v, int ground) {
        var liquid = liquid(f, u, v);
        return liquid != null && ground < liquid.level;
    }
    public int surfaceTop(Face f, int u, int v, int ground) {
        if (terrainPlan().localContent(f, u, v)) return composedSurface(f, u, v, ground).top();
        if (Anomaly.distance(unit(f, u + .5, v + .5), shape) < Anomaly.FIELD)
            return ground;
        var liquid = liquid(f, u, v);
        int top = liquid == null ? ground : Math.max(ground, Math.min(MAX_Y - 1, liquid.level - 1));
        return top == ground && (liquid == null || ground >= liquid.level) && temperature(f, u, v) < 0
                && generation.environment().atmosphere().density() > 0
            ? ground + 1
            : top;
    }
    public boolean closure(Face f, int u, int v) {
        return Anomaly.column(shape, GroundSpace.DEFAULT_MASK, new CubeSurfaceTopology.Cell(f, u, 0, v));
    }
    public int closureTop() {
        return GroundCoordinates.FLOOR + GroundSpace.DEFAULT_MASK.height() - 1;
    }
    public int visibleBottom(Face f, int u, int v, int ground) {
        return Integer.MAX_VALUE;
    }
    public int surfaceY(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return closure(c.face(), c.u(), c.v())
            ? closureTop()
            : surfaceTop(c.face(), c.u(), c.v(), groundY(c.face(), c.u(), c.v()));
    }
    public double temperature(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return terrainPlan().interpolate(TerrainPlan.TEMPERATURE, c.face(), c.u() + .5, c.v() + .5);
    }
    public double humidity(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return Math.clamp(
            terrainPlan().interpolate(TerrainPlan.HUMIDITY, c.face(), c.u() + .5, c.v() + .5), 0, 1);
    }
    public String biome(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return terrainPlan().biome(c.face(), c.u(), c.v());
    }
    public int layer(int y) {
        return generation.depth() == PlanetGeneration.STANDARD_DEPTH || y >= 127 ? y
                                                                                 : 127
                - (int) Math.round((127 - y) * (double) PlanetGeneration.STANDARD_DEPTH / generation.depth());
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
        public int bottom() { return floor; }
        public int end() { return floor + states.length; }
        public BlockState state(int y) {
            return y < floor || y - floor >= states.length ? Blocks.AIR.defaultBlockState()
                                                           : states[y - floor];
        }
    }
    public Column column(Face f, int u, int v) {
        var c = canonical(f, u, v);
        long key = c.u() + 2048L * (c.v() + 2048L * c.face().ordinal());
        synchronized (columns) {
            var cached = columns.get(key);
            if (cached != null)
                return cached;
        }
        var value = buildColumn(c.face(), c.u(), c.v(), floor, MAX_Y, false, groundY(c.face(), c.u(), c.v()));
        synchronized (columns) {
            columns.put(key, value);
            if (columns.size() > 2048)
                columns.remove(columns.keySet().iterator().next());
        }
        return value;
    }
    public Column surfaceColumn(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return buildColumn(c.face(), c.u(), c.v(), floor, MAX_Y, true, groundY(c.face(), c.u(), c.v()));
    }
    public Column plannedColumn(Face f, int u, int v, int ground, boolean vegetation) {
        return buildColumn(f, u, v, floor, MAX_Y, false, ground);
    }
    public Column foundationColumn(Face f, int u, int v, int ground) {
        return buildColumn(f, u, v, floor, MAX_Y, false, ground, false);
    }
    public Column foundationBand(Face f, int u, int v, int ground, int low, int high) {
        return buildColumn(f, u, v, low, high, false, ground, false);
    }
    public int foundationTop(Face f, int u, int v, int ground) {
        if(!terrainPlan().localContent(f,u,v))return surfaceTop(f,u,v,ground);
        var column = buildColumn(f, u, v, floor, MAX_Y, false, ground, false);
        int top = MAX_Y - 1;
        while (top > floor && column.state(top).isAir()) top--;
        return top;
    }
    public Column visibleBand(Face f, int u, int v, int ground, int bottom) {
        return visibleBand(f, u, v, ground, bottom, true);
    }
    public Column visibleBand(Face f, int u, int v, int ground, int bottom, boolean vegetation) {
        int top = closure(f, u, v) ? closureTop() : surfaceTop(f, u, v, ground);
        // Road cuts and carves can put the surface below ground; the band must still reach it.
        return buildColumn(f, u, v, Math.max(floor, Math.min(Math.min(bottom, ground), top)),
            Math.min(MAX_Y, top + 2), false, ground);
    }
    private Column buildColumn(Face f, int u, int v, int low, int high, boolean surfaceOnly, int ground) {
        return buildColumn(f, u, v, low, high, surfaceOnly, ground, true);
    }
    private Column buildColumn(Face f, int u, int v, int low, int high, boolean surfaceOnly, int ground, boolean routes) {
        var direction = unit(f, u + .5, v + .5);
        var cells = lattices();
        var liquid = liquid(f, u, v);
        double temp = temperature(f, u, v);
        var recipe = terrainPlan().recipe();
        int shift = (int) Math.round(terrainPlan().interpolate(TerrainPlan.STRATA_SHIFT, f, u + .5, v + .5));
        double slope =
            Math.max(Math.abs(ground - groundY(f, u + 1, v)), Math.abs(ground - groundY(f, u, v + 1)));
        String biome = biome(f, u, v);
        var shapes = terrainPlan().localVolumes(f, u, v);
        if(!shapes.isEmpty()) {
            low=Math.min(low,ground);
            high=Math.min(MAX_Y,Math.max(high,Math.max(ground+2,liquid==null?ground+2:liquid.level+1)));
            for(var volume:shapes) {
                low=Math.max(floor,Math.min(low,(int)Math.floor(volume.level()-volume.radiusY())-1));
                high=Math.min(MAX_Y,Math.max(high,(int)Math.ceil(volume.level()+volume.radiusY())+2));
            }
        }
        var states = new BlockState[high - low];
        var road = routes ? terrainPlan().road(f, u + .5, v + .5, direction) : null;
        boolean shore = liquid != null && Math.abs(ground - liquid.level) < 3;
        for (int y = low; y < high; y++) {
            BlockState state;
            if (y < floor || y >= MAX_Y)
                state = Blocks.AIR.defaultBlockState();
            else if (y == floor)
                state = shell;
            else if (y <= ground) {
                state = y >= ground - 3 ? underground.stratum(layer(y - shift))
                                        : underground.state(cells, underground.sample(direction, y), ground,
                                              y, shift, surfaceOnly && y < ground - SURFACE_DEPTH);
                if (!state.isAir())
                    state = material(recipe,
                        new TerrainRegistries.MaterialContext(biome, ground - y, slope, temp, shore, state));
            } else
                state = Blocks.AIR.defaultBlockState();
            if (y > floor) {
                for (var shape : shapes)
                    if (terrainPlan().contains(shape, direction, y))
                        state = shape.operation() == TerrainPlan.Operation.CARVE ? Blocks.AIR.defaultBlockState()
                            : material(recipe, new TerrainRegistries.MaterialContext(biome, Math.max(0, ground - y),
                                slope, temp, shore, underground.stratum(layer(y - shift))));
            }
            if(state.isAir() && liquid!=null && y<liquid.level && y>floor)state=liquid.fluid;
            states[y-low]=state;
        }
        if(temp<0 && Anomaly.distance(direction,shape)>=Anomaly.FIELD) {
            int top=high-1;
            while(top>=low && states[top-low].isAir())top--;
            if(top>=low) {
                if(recipe.flag("water","freeze") && liquid!=null && top==liquid.level-1
                    && !states[top-low].getFluidState().isEmpty())states[top-low]=Blocks.ICE.defaultBlockState();
                else if(top+1<high && top+1<MAX_Y && (liquid==null || top>=liquid.level) && states[top-low].getFluidState().isEmpty()
                    && generation.environment().atmosphere().density()>0)states[top+1-low]=Blocks.SNOW.defaultBlockState();
            }
        }
        for(int y=low;y<high;y++) {
            var state=states[y-low];
            if(y>floor && road!=null && road.mode()!=TerrainPlan.RoadMode.CUT) {
                int deck=(int)Math.round(road.height());
                if(road.mode()==TerrainPlan.RoadMode.GROUND && y>deck && y<=ground+1)state=Blocks.AIR.defaultBlockState();
                if(road.mode()==TerrainPlan.RoadMode.GROUND && y<deck && y>ground)state=underground.stratum(layer(y-shift));
                if(y==deck)state=road.mode()==TerrainPlan.RoadMode.BRIDGE?Blocks.OAK_PLANKS.defaultBlockState():Blocks.DIRT_PATH.defaultBlockState();
            }
            var field = y >= ground - Anomaly.DEPTH && y <= ground
                ? Anomaly.surface(Anomaly.distance(direction, shape))
                : null;
            if (field != null)
                state = y == ground ? field : Anomaly.ground();
            if (y >= floor && closure(f, u, v) && y <= closureTop())
                state = Anomaly.core(y);
            states[y - low] = state;
        }
        return new Column(ground, low, states);
    }
    private BlockState material(TerrainRecipe recipe, TerrainRegistries.MaterialContext context) {
        for (var rule : recipe.materials()) {
            var value = rule.material(context);
            if (value != null)
                return value;
        }
        return context.substrate();
    }
    public record Surface(int top, BlockState state) {}
    /** The one-block cap uses the same material rules as buildColumn, without rebuilding its context. */
    public Surface surface(Face f, int u, int v, int ground) {
        var direction = unit(f, u + .5, v + .5);
        double anomaly = Anomaly.distance(direction, shape);
        if (closure(f, u, v)) return new Surface(closureTop(), Anomaly.core(closureTop()));
        var fixed = terrainPlan();
        if (fixed.localContent(f, u, v)) return composedSurface(f, u, v, ground);
        var recipe = fixed.recipe();
        double level = anomaly < Anomaly.FIELD ? Double.NEGATIVE_INFINITY : fixed.waterLevel(f,u,v);
        var fluid = recipe.liquid().defaultBlockState();
        var liquid = Double.isFinite(level) && !fluid.isAir() ? new Sea((int) Math.ceil(level), fluid) : null;
        double temp = fixed.interpolate(TerrainPlan.TEMPERATURE, f, u + .5, v + .5);
        if (liquid != null && liquid.level - 1 > ground) {
            // Lake planning can exceed the storage ceiling; only an actually generated cap is visible.
            int top = Math.min(MAX_Y - 1, liquid.level - 1);
            return new Surface(top, top == liquid.level - 1 && recipe.flag("water", "freeze") && temp < 0
                ? Blocks.ICE.defaultBlockState() : liquid.fluid);
        }
        if (anomaly >= Anomaly.FIELD && temp < 0 && (liquid == null || ground >= liquid.level)
            && generation.environment().atmosphere().density() > 0)
            return new Surface(ground + 1, Blocks.SNOW.defaultBlockState());
        var field = Anomaly.surface(anomaly);
        if (field != null) return new Surface(ground, field);
        int shift = (int) Math.round(terrainPlan().interpolate(TerrainPlan.STRATA_SHIFT, f, u + .5, v + .5));
        double slope = Math.max(Math.abs(ground - groundY(f, u + 1, v)), Math.abs(ground - groundY(f, u, v + 1)));
        boolean shore = liquid != null && Math.abs(ground - liquid.level) < 3;
        return new Surface(ground, material(recipe, new TerrainRegistries.MaterialContext(
            biome(f,u,v), 0, slope, temp, shore, underground.stratum(layer(ground - shift)))));
    }
    private Surface composedSurface(Face f, int u, int v, int ground) {
        int high = Math.min(MAX_Y,Math.max(ground+2, (int)Math.ceil(terrainPlan().waterLevel(f,u,v))+1));
        int low = Math.max(floor,ground-4);
        for(var shape:terrainPlan().localVolumes(f,u,v)) {
            high = Math.min(MAX_Y,Math.max(high,(int)Math.ceil(shape.level()+shape.radiusY())+2));
            low = Math.max(floor,Math.min(low,(int)Math.floor(shape.level()-shape.radiusY())-1));
        }
        var road=terrainPlan().road(f,u+.5,v+.5,unit(f,u+.5,v+.5));
        if(road!=null) {
            high=Math.min(MAX_Y,Math.max(high,(int)Math.ceil(road.height())+1));
            low=Math.max(floor,Math.min(low,(int)Math.floor(road.height())-2));
        }
        var column = buildColumn(f, u, v, low, high, false, ground);
        int top = high - 1;
        while (top > low && column.state(top).isAir()) top--;
        if(column.state(top).isAir() && low>floor) {
            column=buildColumn(f,u,v,floor,low,false,ground);
            top=low-1;
            while(top>floor && column.state(top).isAir())top--;
        }
        return new Surface(top, column.state(top));
    }
    public BlockState surfaceState(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return surface(c.face(), c.u(), c.v(), groundY(c.face(), c.u(), c.v())).state;
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
        return ((mix(seed ^ octave ^ mix(x * 0x9e3779b97f4a7c15L) ^ mix(y * 0x632be59bd9b4e019L)
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

    /**
     * The eight values around one lattice cell of one octave. Consecutive samples down a column mostly fall
     * in the cell of the sample before, so a thread keeps the last cell of each octave: the same values,
     * hashed once.
     */
    static final class Lattice {
        PlanetTerrain owner;
        long octave;
        int a, b, c;
        final double[] v = new double[8];
    }

    private static final ThreadLocal<Lattice[]> LATTICES = ThreadLocal.withInitial(() -> {
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
        if (cell.owner != this || cell.octave != octave || cell.a != a || cell.b != b || cell.c != c) {
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
        return lerp(lerp(lerp(n[0], n[1], u), lerp(n[2], n[3], u), v),
            lerp(lerp(n[4], n[5], u), lerp(n[6], n[7], u), v), w);
    }

    public double elevation(Point unit) {
        double distance = Anomaly.distance(unit, shape);
        var sample = distance >= Anomaly.PLATEAU_OUTER ? unit : Anomaly.twisted(unit, distance);
        var chart = terrainPlan().chart(sample);
        double h = terrainPlan().height(chart.face(), chart.u(), chart.v()) - 127;
        return distance >= Anomaly.PLATEAU_OUTER ? h
                                                 : Anomaly.elevation(h, distance)
                + Anomaly.warp(distance) * (noise(sample, 70, 5) * .7 + noise(sample, 160, 6) * .3);
    }
    public double elevation(Face f, double u, double v) {
        var p = unit(f,u,v);
        if (Anomaly.distance(p,shape) >= Anomaly.PLATEAU_OUTER)
            return terrainPlan().height(f,u,v) - 127;
        return elevation(p);
    }
    public int groundY(Face f, int u, int v) {
        var c = canonical(f, u, v);
        return Math.clamp(
            127 + (int) Math.floor(elevation(c.face(), c.u() + .5, c.v() + .5)), floor + 1, MAX_Y - 2);
    }
    public static Point direction(Face f, double u, double v) {
        return direction(PlanetShape.STANDARD, f, u, v);
    }
    public static Point direction(PlanetShape shape, Face f, double u, double v) {
        double h = shape.half();
        var p = f.normal.scale(h).add(f.u.scale(u - h)).add(f.v.scale(v - h));
        return p.scale(1 / p.length());
    }
    public Point unit(Face f, double u, double v) {
        return direction(shape, f, u, v);
    }
    public BlockState state(Face f, int u, int y, int v) {
        return column(f, u, v).state(y);
    }
    public interface Image {
        int face();
        int u();
        int v();
        BlockState state(int x, int y, int z);
    }
    public boolean matches(Image image) {
        var surface = new dev.starfold.worldgen.surface.SurfaceFunction(this);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                var states =
                    surface.blocks(Face.VALUES[image.face()], image.u() * 16 + x, image.v() * 16 + z);
                for (int y = floor; y < MAX_Y; y++)
                    if (image.state(x, y, z) != states[y - floor])
                        return false;
            }
        return true;
    }
}
