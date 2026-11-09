package dev.starfold.biotech.terrain;

import dev.starfold.worldgen.plan.terrain.*;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.noise.TerrainNoise;
import java.util.*;

/** Geodesic triangular-lattice dual: spherical Voronoi walls, including twelve pentagonal defects. */
public final class HiveLandform implements TerrainRegistries.LandformType {
    public static void register() {
        TerrainRegistries.LANDFORMS.register("starfold:hive", CODEC, new HiveLandform());
    }

    static final Codec<JsonObject> CODEC = TerrainRegistries.parameters(p -> {
        TerrainRecipe.keys(p, "density", "radius_min", "radius_max", "amplitude", "operation", "spacing",
            "wall_slope", "neighbours");
        TerrainRecipe.number(p, "density", 0, 8);
        double min = TerrainRecipe.number(p, "radius_min", 16, 256);
        TerrainRecipe.number(p, "radius_max", min, 256);
        TerrainRecipe.number(p, "amplitude", 0, 128);
        TerrainRecipe.number(p, "spacing", 32, 256);
        TerrainRecipe.number(p, "wall_slope", .1, 4);
        if (!Set.of("raise", "lower").contains(p.get("operation").getAsString()))
            throw new IllegalArgumentException("Hive operation");
        if (p.has("neighbours"))
            for (var value : TerrainRecipe.array(p, "neighbours", 12)) {
                var xyz = value.getAsJsonArray();
                if (xyz.size() != 3)
                    throw new IllegalArgumentException("Hive neighbour");
                var q =
                    new Point(xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble());
                if (!q.finite() || Math.abs(q.length() - 1) > 1e-8)
                    throw new IllegalArgumentException("Hive neighbour direction");
            }
    });
    public double profile(double r, double angle, JsonObject parameters) {
        return 0;
    }
    public double evaluate(Point p, TerrainPlan.Landform form, int size, TerrainNoise noise) {
        var parameters = form.parameters();
        var center = form.center();
        double edge = Math.PI / 2;
        for (var value : parameters.getAsJsonArray("neighbours")) {
            var q = value.getAsJsonArray();
            var site = new Point(q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble());
            var normal = center.sub(site);
            double dot = p.dot(normal);
            // A Voronoi boundary belongs to exactly one cell, including exact geometric aliases.
            if (dot < -1e-12 || Math.abs(dot) <= 1e-12 && compare(center, site) > 0)
                return 0;
            edge = Math.min(edge, Math.asin(Math.clamp(dot / normal.length(), 0, 1)));
        }
        double wallDistance = size * .5 * edge;
        double wall =
            Math.max(0, 24 - parameters.get("wall_slope").getAsDouble() * Math.max(0, wallDistance - 12));
        return (wall - 4) / 24;
    }
    private static int compare(Point a, Point b) {
        int c = Double.compare(a.x(), b.x());
        if (c == 0)
            c = Double.compare(a.y(), b.y());
        return c != 0 ? c : Double.compare(a.z(), b.z());
    }
    public void place(TerrainPlanner planner, TerrainRecipe.Config config) {
        var parameters = config.parameters();
        double density=parameters.get("density").getAsDouble()*planner.recipe.value("preferences","density");
        if (density == 0)
            return;
        double phi = (1 + Math.sqrt(5)) / 2;
        var vertices = new Point[] {new Point(-1, phi, 0), new Point(1, phi, 0), new Point(-1, -phi, 0),
            new Point(1, -phi, 0), new Point(0, -1, phi), new Point(0, 1, phi), new Point(0, -1, -phi),
            new Point(0, 1, -phi), new Point(phi, 0, -1), new Point(phi, 0, 1), new Point(-phi, 0, -1),
            new Point(-phi, 0, 1)};
        int[][] triangles = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4},
            {11, 10, 2}, {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
            {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        double spacing =
            parameters.get("spacing").getAsDouble() / Math.sqrt(density);
        int divisions = Math.max(1, (int) Math.round(planner.size * .5 * 1.107 / spacing));
        var random = new SplittableRandom(planner.seed ^ 0x51fa902c346be718L);
        double turn = random.nextDouble(2 * Math.PI), tilt = random.nextDouble(2 * Math.PI);
        var sites = new ArrayList<Point>();
        for (var triangle : triangles)
            for (int i = 0; i <= divisions; i++)
                for (int j = 0; j <= divisions - i; j++) {
                    var a = unit(vertices[triangle[0]]);
                    var b = unit(vertices[triangle[1]]);
                    var c = unit(vertices[triangle[2]]);
                    var p = unit(a.scale(i).add(b.scale(j)).add(c.scale(divisions - i - j)));
                    var q = new Point(p.x() * Math.cos(turn) - p.z() * Math.sin(turn), p.y(),
                        p.x() * Math.sin(turn) + p.z() * Math.cos(turn));
                    p = new Point(q.x(), q.y() * Math.cos(tilt) - q.z() * Math.sin(tilt),
                        q.y() * Math.sin(tilt) + q.z() * Math.cos(tilt));
                    boolean duplicate = false;
                    for (var old : sites)
                        if (old.sub(p).length() < 1e-10) {
                            duplicate = true;
                            break;
                        }
                    if (!duplicate)
                        sites.add(p);
                }
        double radius = Math.min(256,
            Math.max(parameters.get("radius_min").getAsDouble(),
                Math.min(parameters.get("radius_max").getAsDouble(), spacing)));
        for (var center : sites) {
            if (!TerrainPlanner.clear(center, radius, planner.size)
                || planner.landforms.size() >= TerrainPlan.MAX_LANDFORMS)
                continue;
            var neighbours = new ArrayList<>(sites);
            neighbours.remove(center);
            neighbours.sort(Comparator.comparingDouble(p -> p.sub(center).length()));
            var saved = parameters.deepCopy();
            var list = new JsonArray();
            for (var neighbour : neighbours.subList(0, Math.min(12, neighbours.size()))) {
                var xyz = new JsonArray();
                xyz.add(neighbour.x());
                xyz.add(neighbour.y());
                xyz.add(neighbour.z());
                list.add(xyz);
            }
            saved.add("neighbours", list);
            planner.landforms.add(new TerrainPlan.Landform(config.type(), saved, center, radius,
                parameters.get("amplitude").getAsDouble(),
                TerrainPlan.Operation.valueOf(
                    parameters.get("operation").getAsString().toUpperCase(Locale.ROOT))));
        }
    }
    private static Point unit(Point p) {
        return p.scale(1 / p.length());
    }
}
