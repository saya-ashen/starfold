package dev.starfold.worldgen.plan.terrain;

import com.google.gson.*;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrain;
import java.io.*;
import java.util.*;

/** Shared 16-block nodes and bounded spherical features, independent of chart ownership. */
public final class TerrainPlan {
    public static final int FORMAT = 3, SPACING = 16, MAX_LANDFORMS = 4096, MAX_PATHS = 100_000;
    public enum Operation { RAISE, LOWER, FILL, CARVE, PLACE, BURY, CUT, DAMAGE }
    public enum Water { DRY, OCEAN, LAKE, DRY_VALLEY }
    private static final Water[] WATER_VALUES = Water.values();
    public record Landform(String type, JsonObject parameters, Point center, double radius, double amplitude,
        Operation operation) {
        public Landform {
            parameters = parameters.deepCopy();
        }
        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }
    }
    public record Path(List<Integer> nodes, double width, double depth, double level, Operation operation) {
        public Path {
            nodes = List.copyOf(nodes);
        }
    }
    public enum Kind { LANDFORM, VOLUME, RIVER, ROAD, SITE }
    public record Ref(Kind kind, long id) {}
    public record Relation(Ref source, String relation, Ref target) {}
    public record Volume(String type, JsonObject parameters, Point center, double level,
                         double radiusU, double radiusY, double radiusV, Operation operation) {
        public Volume { parameters = parameters.deepCopy(); }
        @Override public JsonObject parameters() { return parameters.deepCopy(); }
    }
    public enum RoadMode { GROUND, BRIDGE, CUT }
    public record Road(List<Integer> nodes, List<Double> levels, List<RoadMode> modes, double width) {
        public Road { nodes = List.copyOf(nodes); levels = List.copyOf(levels); modes = List.copyOf(modes); }
    }
    public record RoadSample(double height, RoadMode mode) {}
    private final int size, intervals;
    private volatile TemperatureField temperatureField;
    public TemperatureField temperatureField() {
        var value=temperatureField;
        if(value!=null)return value;
        synchronized(this) {
            if(temperatureField==null)temperatureField=new TemperatureField(this);
            return temperatureField;
        }
    }
    private final TerrainRecipe recipe;
    private final long seed;
    private final Point[] positions;
    private final int[][] aliases;
    private final Map<Long, Integer> nodes;
    private final int[][] halo;
    private final String[] biomeIds;
    private final double detailHills, seaLevel;
    private final float[][] fields;
    private final short[] biomes;
    private final byte[] water;
    private final List<Landform> landforms;
    private final List<Path> paths;
    private final List<Relation> relations;
    private final List<Volume> volumes;
    private final List<Road> roads;
    private final TerrainFeatures features;
    private final dev.starfold.worldgen.noise.TerrainNoise noise;
    public static final int HEIGHT = 0, ROUGHNESS = 1, TEMPERATURE = 2, HUMIDITY = 3, WATER_LEVEL = 4,
                            STRATA_SHIFT = 5;
    public TerrainPlan(long seed, int size, TerrainRecipe recipe, float[][] fields, short[] biomes,
        byte[] water, List<Landform> landforms, List<Path> paths) {
        this(seed, size, recipe, fields, biomes, water, landforms, paths, List.of(), List.of(), List.of());
    }
    public TerrainPlan(long seed, int size, TerrainRecipe recipe, float[][] fields, short[] biomes,
        byte[] water, List<Landform> landforms, List<Path> paths, List<Relation> relations,
        List<Volume> volumes, List<Road> roads) {
        this.seed = seed;
        this.size = size;
        this.intervals = size / SPACING;
        this.recipe = recipe;
        detailHills = recipe.value("relief", "hills");
        seaLevel = recipe.value("water", "sea_level");
        var grid = new Grid(size);
        positions = grid.positions;
        aliases = grid.aliases;
        nodes = grid.nodes;
        halo = new int[6][(intervals + 5) * (intervals + 5)];
        for (var f : Face.VALUES)
            for (int u = -2; u <= intervals + 2; u++)
                for (int v = -2; v <= intervals + 2; v++)
                    halo[f.ordinal()][(u + 2) * (intervals + 5) + v + 2] =
                        TerrainGrid.nodeIndex(size, intervals, aliases, nodes, f, u, v);
        biomeIds = recipe.biomes().stream().map(TerrainRecipe.Config::biomeId).toArray(String[]::new);
        this.fields = new float[fields.length][];
        for (int i = 0; i < fields.length; i++) {
            this.fields[i] = fields[i].clone();
            for (int n = 0; n < fields[i].length; n++)
                this.fields[i][n] = Math.round(fields[i][n] * TerrainPlanCodec.precision(i)) / TerrainPlanCodec.precision(i);
        }
        this.biomes = biomes.clone();
        this.water = water.clone();
        this.landforms = List.copyOf(landforms);
        this.paths = List.copyOf(paths);
        this.relations = List.copyOf(relations);
        this.volumes = List.copyOf(volumes);
        this.roads = List.copyOf(roads);
        noise = new dev.starfold.worldgen.noise.TerrainNoise(seed);
        features = new TerrainFeatures(this, positions, noise);
    }
    public TerrainPlan withContent(List<Relation> links, List<Volume> shapes, List<Road> routes) {
        return new TerrainPlan(seed, size, recipe, fields, biomes, water, landforms, paths, links, shapes, routes);
    }
    public List<Volume> localVolumes(Face face, double u, double v) {
        return features.localVolumes(face, u, v);
    }
    public boolean localContent(Face face, int u, int v) {
        return features.localContent(face, u, v);
    }
    public static double volumeReach(double u, double v, int size) {
        return TerrainFeatures.volumeReach(u, v, size);
    }
    public boolean contains(Volume volume, Point p, int y) {
        return features.contains(volume, volume.parameters, p, y);
    }
    /** Shared spherical cubic: planning crossings, road lookup and offline overlays use these same arcs. */
    public static Point roadPoint(Point[] positions, List<Integer> nodes, int edge, double t) {
        return TerrainFeatures.roadPoint(positions, nodes, edge, t);
    }
    public Point roadPoint(Road road, int edge, double t) {
        return features.roadPoint(road, edge, t);
    }
    public RoadSample road(Face face, double u, double v, Point p) {
        return features.road(face, u, v, p);
    }
    /** Public planning API retained for existing content modules. */
    public static final class Grid extends TerrainGrid {
        public Grid(int size) { super(size); }
    }
    public int node(Face face, int u, int v) {
        return halo[face.ordinal()][(u + 2) * (intervals + 5) + v + 2];
    }
    public int nodeCount() {
        return positions.length;
    }
    public Point position(int node) {
        return positions[node];
    }
    public float field(int field, int node) {
        return fields[field][node];
    }
    public int biome(int node) {
        return biomes[node];
    }
    public Water water(int node) {
        return WATER_VALUES[water[node]];
    }
    public TerrainRecipe recipe() {
        return recipe;
    }
    public long seed() {
        return seed;
    }
    public int size() {
        return size;
    }
    public List<Landform> landforms() {
        return landforms;
    }
    public List<Path> paths() {
        return paths;
    }
    public List<Relation> relations() {
        return relations;
    }
    public List<Volume> volumes() {
        return volumes;
    }
    public List<Road> roads() {
        return roads;
    }
    public double interpolate(int field, Face face, double u, double v) {
        // A unique face at exact edges makes both aliases use the same bicubic halo, including corners.
        if (u <= 0 || v <= 0 || u >= size || v >= size) {
            var p = PlanetTerrain.direction(new PlanetShape(size), face, u, v);
            var chart = chart(p);
            face = chart.face;
            u = chart.u;
            v = chart.v;
        }
        double x = u / 16, z = v / 16;
        int a = (int) Math.floor(x), b = (int) Math.floor(z);
        double tx = x - a, tz = z - b;
        var ids = halo[face.ordinal()];
        var values = fields[field];
        int stride = intervals + 5, k = (a + 1) * stride + b + 1;
        return cubic(row(values, ids, k, stride, tx), row(values, ids, k + 1, stride, tx),
            row(values, ids, k + 2, stride, tx), row(values, ids, k + 3, stride, tx), tz);
    }
    private static double row(float[] values, int[] ids, int k, int stride, double t) {
        return cubic(values[ids[k]], values[ids[k + stride]], values[ids[k + 2 * stride]],
            values[ids[k + 3 * stride]], t);
    }
    private static double cubic(double a, double b, double c, double d, double t) {
        return b + .5 * t * (c - a + t * (2 * a - 5 * b + 4 * c - d + t * (3 * (b - c) + d - a)));
    }
    public record Chart(Face face, double u, double v) {}
    public Chart chart(Point p) {
        Face f = Face.TOP;
        double best = -1;
        for (var face : Face.VALUES)
            if (p.dot(face.normal) > best + 1e-12) {
                best = p.dot(face.normal);
                f = face;
            }
        return new Chart(f, size * .5 * (1 + p.dot(f.u) / best), size * .5 * (1 + p.dot(f.v) / best));
    }
    public double landformHeight(Point p, Face f, double u, double v) {
        return features.landformHeight(p, f, u, v);
    }
    public double pathHeight(double height, Face f, double u, double v, Point p) {
        return features.pathHeight(height, f, u, v, p);
    }
    public List<Ref> riverCrossings(Face face, double u, double v, Point point) {
        return features.riverCrossings(face, u, v, point);
    }
    public double riverLevel(Face f, double u, double v, Point p) {
        return features.riverLevel(f, u, v, p);
    }
    public double height(Face f, double u, double v) {
        var p = PlanetTerrain.direction(new PlanetShape(size), f, u, v);
        double h = interpolate(HEIGHT, f, u, v) + landformHeight(p, f, u, v)
            + noise.sample(p.scale(size * .5), 40, 901) * detailHills
                * Math.max(0, interpolate(ROUGHNESS, f, u, v));
        return pathHeight(h, f, u, v, p);
    }
    public String biome(Face f, int u, int v) {
        return biome(f, u + .5, v + .5);
    }
    /** Geometric addresses share the same perturbation and owner chart at every edge and corner. */
    public String biome(Face f, double u, double v) {
        var p = PlanetTerrain.direction(new PlanetShape(size), f, u, v);
        double warp = noise.sample(p.scale(size * .5), 24, 902) * 18;
        var chart = chart(p.add(new Point(warp, -warp, warp * .5).scale(2.0 / size)));
        int a = Math.clamp((int) Math.round(chart.u / 16), 0, intervals),
            b = Math.clamp((int) Math.round(chart.v / 16), 0, intervals);
        return biomeIds[biomes[node(chart.face, a, b)]];
    }
    public Water water(Face f, int u, int v) {
        int a = Math.clamp((u + 8) / 16, 0, intervals), b = Math.clamp((v + 8) / 16, 0, intervals);
        return water(node(f, a, b));
    }
    public double waterLevel(Face f, int u, int v) {
        if (water(f, u, v) == Water.OCEAN)
            return seaLevel;
        var p = PlanetTerrain.direction(new PlanetShape(size), f, u + .5, v + .5);
        double river = riverLevel(f, u + .5, v + .5, p);
        if (water(f, u, v) == Water.LAKE)
            return Math.max(river, interpolate(WATER_LEVEL, f, u + .5, v + .5));
        return river;
    }
    public void write(DataOutputStream out) throws IOException {
        TerrainPlanCodec.write(this, out);
    }

    public static TerrainPlan read(DataInputStream in, int size) throws IOException {
        return TerrainPlanCodec.read(in, size);
    }
    public boolean validReference(Ref ref, Set<Long> sites, boolean includeSites) {
        return features.validReference(ref, sites, includeSites);
    }
    public void validateReferences(Set<Long> sites, boolean includeSites) throws IOException {
        features.validateReferences(sites, includeSites);
    }
    public boolean cuts(Ref ref, Point point, int y) {
        return features.cuts(this, ref, point, y);
    }
}
