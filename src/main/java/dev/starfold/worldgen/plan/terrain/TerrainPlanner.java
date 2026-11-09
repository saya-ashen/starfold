package dev.starfold.worldgen.plan.terrain;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.worldgen.noise.TerrainNoise;
import java.util.*;

/** Deterministic spherical graph producer. Each registered stage sees the preceding stage's result. */
public final class TerrainPlanner {
    public final long seed;
    public final int size;
    public final TerrainRecipe recipe;
    public final Grid grid;
    public final float[][] fields;
    public final short[] biomes;
    public final byte[] water;
    public final List<Landform> landforms = new ArrayList<>();
    public final List<Path> paths = new ArrayList<>();
    public final List<Volume> volumes = new ArrayList<>();
    private final TerrainNoise noise;
    private final SplittableRandom random;
    private final int[][] neighbours;
    private float[] finalHeight;
    private TerrainPlan volumeSurface;
    private final double temperatureOffset;
    public TerrainPlanner(long seed, int size, TerrainRecipe recipe) {
        this(seed, size, recipe, 0);
    }
    public TerrainPlanner(long seed, int size, TerrainRecipe recipe, double temperatureOffset) {
        this.temperatureOffset = temperatureOffset;
        this.seed = seed;
        this.size = size;
        this.recipe = recipe;
        grid = new Grid(size);
        fields = new float[6][grid.positions.length];
        biomes = new short[grid.positions.length];
        water = new byte[grid.positions.length];
        noise = new TerrainNoise(seed);
        random = new SplittableRandom(seed ^ 0x5445525241494eL);
        neighbours = grid.neighbours();
    }
    public TerrainPlan generate() {
        for (String stage : recipe.stages()) TerrainRegistries.stage(stage).accept(this);
        return new TerrainPlan(seed, size, recipe, fields, biomes, water, landforms, paths, List.of(), volumes, List.of());
    }
    public void plates() {
        int count = 18;
        var centers = new Point[count];
        var movement = new Point[count];
        var land = new boolean[count];
        for (int i = 0; i < count; i++) {
            centers[i] = sphere();
            movement[i] = sphere();
            land[i] = random.nextDouble() < recipe.value("land_fraction");
        }
        double sea = recipe.value("water", "sea_level"), mountains = recipe.value("relief", "mountains"),
               hills = recipe.value("relief", "hills");
        for (int n = 0; n < grid.positions.length; n++) {
            var p = grid.positions[n];
            var physical = p.scale(size * .5);
            double warpScale = Math.max(96, size * .18);
            var warped = p.add(new Point(
                noise.sample(physical, warpScale, 101) + .45 * noise.sample(physical, warpScale * .43, 104),
                noise.sample(physical, warpScale, 102) + .45 * noise.sample(physical, warpScale * .43, 105),
                noise.sample(physical, warpScale, 103) + .45 * noise.sample(physical, warpScale * .43, 106)).scale(.23));
            warped = warped.scale(1 / warped.length());
            int first = 0, second = 1;
            if (warped.dot(centers[second]) > warped.dot(centers[first])) {
                first = 1;
                second = 0;
            }
            for (int i = 2; i < count; i++) {
                if (warped.dot(centers[i]) > warped.dot(centers[first])) {
                    second = first;
                    first = i;
                } else if (warped.dot(centers[i]) > warped.dot(centers[second]))
                    second = i;
            }
            var boundary = centers[first].sub(centers[second]);
            double distance = Math.abs(warped.dot(boundary)) / boundary.length() * size * .5;
            double convergence =
                movement[first].sub(movement[second]).dot(centers[second].sub(centers[first]));
            double beltWidth = Math.max(24, size * .045);
            double belt = Math.exp(-distance * distance / (beltWidth * beltWidth));
            double interior = noise.sample(physical, Math.max(96, size * .24), 1)
                + .5 * noise.sample(physical, Math.max(48, size * .11), 2)
                + .25 * noise.sample(physical, Math.max(24, size * .05), 3);
            double continent = land[first] ? 16 : -48;
            if (land[first] != land[second]) {
                double coast = TerrainRegistries.smooth(distance / Math.max(24, size * .065));
                continent = land[first] ? 16 * coast : -8 - 40 * coast * coast;
            }
            double ridge = noise.ridged(physical, Math.max(48, size * .12), 111);
            double mountain = convergence > 0 ? mountains * belt * (.12 + 1.1 * ridge)
                                              : -hills * .7 * belt;
            // Ocean crust retains shelf/slope/basin relief rather than inheriting continental ranges.
            if (!land[first]) mountain *= .18;
            fields[HEIGHT][n] = (float) Math.clamp(sea + continent + mountain
                + hills * interior * (land[first] ? 1 : .35), sea - 90, sea + 175);
            fields[ROUGHNESS][n] = (float) (recipe.value("relief", "roughness") * (.6 + 1.2 * belt));
            fields[STRATA_SHIFT][n] = (float) (mountain * .6 + hills * interior);
        }
    }
    private Point sphere() {
        double y = random.nextDouble(-1, 1), angle = random.nextDouble(2 * Math.PI), r = Math.sqrt(1 - y * y);
        return new Point(r * Math.cos(angle), y, r * Math.sin(angle));
    }
    public void scatter(TerrainRecipe.Config config) {
        scatter(config, false);
    }
    public void scatter(TerrainRecipe.Config config, boolean chain) {
        var p = config.parameters();
        double density = p.get("density").getAsDouble() * recipe.value("preferences", "density");
        int count = (int) Math.ceil(6.0 * size * size / (256 * 256) * density);
        Point anchor = sphere(), tangent = sphere();
        for (int i = 0; i < count && landforms.size() < MAX_LANDFORMS; i++) {
            Point center;
            if (chain) {
                if (i % 6 == 0) { anchor = sphere(); tangent = sphere(); }
                var q = anchor.add(tangent.scale(i % 6 * 100.0 / size));
                center = q.scale(1 / q.length());
            } else center = sphere();
            double min = p.get("radius_min").getAsDouble(), max = p.get("radius_max").getAsDouble();
            double radius = min == max ? min : 1 / (1 / min - random.nextDouble() * (1 / min - 1 / max));
            if (!clear(center, radius, size))
                continue;
            landforms.add(new Landform(config.type(), p, center, radius, p.get("amplitude").getAsDouble(),
                Operation.valueOf(p.get("operation").getAsString().toUpperCase(Locale.ROOT))));
        }
    }
    /** Entire spherical cap, not just its centre, must clear every anomalous corner. */
    public static boolean clear(Point center, double radius, int size) {
        double angular = 2 * Math.asin(Math.min(1, radius / size));
        double cornerDistance = Anomaly.distance(center, new PlanetShape(size));
        double reach = angular / (Math.sqrt(2) / (3 * (size * .5)));
        return cornerDistance >= Anomaly.CLEAR + reach;
    }
    public void landforms() {
        for (var config : recipe.landforms())
            TerrainRegistries.LANDFORMS.get(config.type()).implementation().place(this, config);
    }
    private float[] heights() {
        var plan = new TerrainPlan(seed, size, recipe, fields, biomes, water, landforms, List.of());
        var values = new float[grid.positions.length];
        for (int n = 0; n < values.length; n++) {
            var c = plan.chart(grid.positions[n]);
            values[n] =
                fields[HEIGHT][n] + (float) plan.landformHeight(grid.positions[n], c.face(), c.u(), c.v());
        }
        return values;
    }
    public void erosion() {
        finalHeight = heights();
        int n = finalHeight.length;
        double strength = recipe.value("erosion", "strength");
        // Two simultaneous thermal passes conserve transferred material and never depend on node visitation
        // order.
        for (int pass = 0; pass < 2; pass++) {
            var delta = new float[n];
            for (int a = 0; a < n; a++)
                for (int b : neighbours[a])
                    if (a < b) {
                        double excess = Math.abs(finalHeight[a] - finalHeight[b]) - 24;
                        if (excess <= 0)
                            continue;
                        float flow =
                            (float) (Math.signum(finalHeight[a] - finalHeight[b]) * excess * .06 * strength);
                        delta[a] -= flow;
                        delta[b] += flow;
                    }
            for (int a = 0; a < n; a++) {
                fields[HEIGHT][a] += delta[a];
                finalHeight[a] += delta[a];
            }
        }
        var filled = finalHeight.clone();
        var seen = new boolean[n];
        var parent = new int[n];
        Arrays.fill(parent, -1);
        var order = new ArrayList<Integer>();
        record Flood(int node, float level) {}
        var queue =
            new PriorityQueue<Flood>(Comparator.comparingDouble(Flood::level).thenComparingInt(Flood::node));
        double sea = recipe.value("water", "sea_level");
        boolean wet = !recipe.liquid().defaultBlockState().isAir();
        for (int a = 0; a < n; a++)
            if (wet && filled[a] < sea) {
                seen[a] = true;
                filled[a] = (float) sea;
                water[a] = (byte) Water.OCEAN.ordinal();
                queue.add(new Flood(a, filled[a]));
            }
        if (queue.isEmpty()) {
            int lowest = 0;
            for (int a = 1; a < n; a++)
                if (filled[a] < filled[lowest])
                    lowest = a;
            seen[lowest] = true;
            queue.add(new Flood(lowest, filled[lowest]));
        }
        while (!queue.isEmpty()) {
            var current = queue.remove();
            int a = current.node;
            order.add(a);
            for (int b : neighbours[a])
                if (!seen[b]) {
                    seen[b] = true;
                    parent[b] = a;
                    filled[b] = Math.max(finalHeight[b], filled[a]);
                    queue.add(new Flood(b, filled[b]));
                }
        }
        // On sloping ground follow the steepest descent; the flood tree only routes filled flats.
        for (int a = 0; a < n; a++) {
            double best = 0;
            for (int b : neighbours[a]) {
                double drop = (filled[a] - filled[b]) / grid.positions[a].sub(grid.positions[b]).length();
                if (drop > best) { best = drop; parent[a] = b; }
            }
        }
        Collections.reverse(order);
        order.sort(Comparator.<Integer>comparingDouble(a -> filled[a]).reversed());
        var accumulation = new int[n];
        Arrays.fill(accumulation, 1);
        for (int i = 0; i < order.size(); i++) {
            int a = order.get(i), b = parent[a];
            if (b >= 0)
                accumulation[b] += accumulation[a];
        }
        boolean rivers = recipe.flag("erosion", "rivers");
        double threshold = recipe.value("erosion", "river_threshold");
        for (int a = 0; a < n; a++) {
            fields[WATER_LEVEL][a] = filled[a];
            if (water[a] != (byte) Water.OCEAN.ordinal() && filled[a] > finalHeight[a] + 1)
                water[a] = (byte) (wet ? Water.LAKE : Water.DRY_VALLEY).ordinal();
            int b = parent[a];
            if (b < 0 || water[a] == Water.OCEAN.ordinal() || accumulation[a] < threshold)
                continue;
            if (!rivers) {
                if (water[a] == Water.DRY.ordinal())
                    water[a] = (byte) Water.DRY_VALLEY.ordinal();
                continue;
            }
            double depth = Math.min(10, 2 + Math.log1p(accumulation[a]) * strength),
                   width = Math.min(12, 2 + Math.sqrt(accumulation[a]) * .2);
            double extent = grid.positions[a].sub(grid.positions[b]).length() * size * .5 + width;
            if (!clear(grid.positions[a], extent, size) || !clear(grid.positions[b], extent, size))
                continue;
            // One graph edge per path retains junctions and seam endpoints exactly; parent forest has no
            // cycles.
            paths.add(new Path(
                List.of(a, b), width, depth, Math.min(finalHeight[a], finalHeight[b]) - .5, Operation.CARVE));
        }
    }
    public void volumes() {
        volumeSurface = new TerrainPlan(seed, size, recipe, fields, biomes, water, landforms, paths);
        for (var config : recipe.volumes())
            TerrainRegistries.VOLUMES.get(config.type()).implementation().place(this, config);
    }
    public void scatterVolume(TerrainRecipe.Config config) { scatterVolume(config,false); }
    public void scatterVolume(TerrainRecipe.Config config,boolean attached) {
        var p = config.parameters();
        int count = (int) Math.ceil(6.0 * size * size / (256 * 256) * p.get("density").getAsDouble()
            * recipe.value("preferences", "density"));
        var surface = volumeSurface;
        for (int i = 0; i < count && volumes.size() < 4096; i++)
            for (int attempt = 0; attempt < 128; attempt++) {
                int node = random.nextInt(grid.positions.length);
                var address = surface.chart(grid.positions[node]);
                var center = dev.starfold.worldgen.PlanetTerrain.direction(new PlanetShape(size), address.face(),
                    Math.clamp((int)address.u(),0,size-1)+.5,Math.clamp((int)address.v(),0,size-1)+.5);
                double radius = p.get("radius").getAsDouble();
                if (!clear(center, volumeReach(radius, radius*.55, size), size)) continue;
                var chart = surface.chart(center);
                double ground = Math.clamp(surface.height(chart.face(), chart.u(), chart.v()), -63, 318);
                double slope = 0;
                for (int next : neighbours[node])
                    slope = Math.max(slope, Math.abs(finalHeight[node] - finalHeight[next])
                        / (center.sub(grid.positions[next]).length() * size * .5));
                if (slope < p.get("slope_min").getAsDouble() || slope > p.get("slope_max").getAsDouble()) continue;
                double height = p.get("height").getAsDouble(), level = ground + p.get("offset").getAsDouble();
                var parameters=p.deepCopy();
                if(attached) {
                    var axis=Math.abs(center.y())<.8?Face.TOP.normal:Face.EAST.normal;
                    var tangent=center.cross(axis);tangent=tangent.scale(1/tangent.length());
                    var across=center.cross(tangent);
                    double best=0,wallX=0,wallZ=0,high=0,low=0;
                    for(int direction=0;direction<8;direction++) {
                        double x=Math.cos(direction*Math.PI/4),z=Math.sin(direction*Math.PI/4);
                        var offset=tangent.scale(x).add(across.scale(z)).scale(radius*.65/(size*.5));
                        var back=center.sub(offset);back=back.scale(1/back.length());
                        var front=center.add(offset);front=front.scale(1/front.length());
                        var bc=surface.chart(back);var fc=surface.chart(front);
                        double h=surface.height(bc.face(),bc.u(),bc.v()),l=surface.height(fc.face(),fc.u(),fc.v());
                        if(h-l>best) {best=h-l;wallX=x;wallZ=z;high=h;low=l;}
                    }
                    if(best<Math.max(height*1.5,radius*.5))continue;
                    level=Math.min(ground,high-height*.6)+p.get("offset").getAsDouble();
                    if(high<level || low>level-height*.4)continue;
                    parameters.addProperty("wall_x",wallX);parameters.addProperty("wall_z",wallZ);
                }
                if (p.get("cave_connection").getAsBoolean()) {
                    int cave = Integer.MIN_VALUE;
                    double width = recipe.value("caves", "width"), scale = recipe.value("caves", "scale");
                    for (int y = (int) ground - 6; y >= Math.max(-58, ground - 80); y--) {
                        var sample = center.scale(size * .5 + y - 128);
                        if (Math.abs(noise.sample(sample, scale, 101)) < width
                            && Math.abs(noise.sample(sample, scale, 102)) < width) { cave = y; break; }
                    }
                    if (cave == Integer.MIN_VALUE) continue;
                    level = (ground + cave) * .5;
                    height = (ground - cave) * .5 + 3;
                }
                if (level + height >= 319 || level - height <= -63) continue;
                volumes.add(new Volume(config.type(), parameters, center, level, radius, height, radius * .55,
                    Operation.valueOf(p.get("operation").getAsString().toUpperCase(Locale.ROOT))));
                break;
            }
    }
    public void climate() {
        if (finalHeight == null)
            finalHeight = heights();
        int count = grid.positions.length;
        var distance = new int[count];
        Arrays.fill(distance, Integer.MAX_VALUE);
        var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < count; i++)
            if (water[i] == Water.OCEAN.ordinal()) {
                distance[i] = 0;
                queue.add(i);
            }
        while (!queue.isEmpty()) {
            int a = queue.remove();
            for (int b : neighbours[a])
                if (distance[b] > distance[a] + 1) {
                    distance[b] = distance[a] + 1;
                    queue.add(b);
                }
        }
        for (int i = 0; i < count; i++) {
            var p = grid.positions[i];
            double latitude = Math.abs(p.y());
            fields[TEMPERATURE][i] = (float) (temperatureOffset + recipe.value("climate", "temperature")
                - recipe.value("climate", "latitude_gradient") * Math.pow(latitude, 3)
                - recipe.value("climate", "lapse_rate")
                    * Math.max(0, finalHeight[i] - recipe.value("water", "sea_level")));
            double wind = latitude < .5 ? 1 : -1;
            double rain = 0;
            for (int b : neighbours[i]) {
                double east = grid.positions[b].x() - p.x();
                if (east * wind < 0)
                    rain += Math.clamp((finalHeight[b] - finalHeight[i]) / 100, -.1, .1);
            }
            double ocean = distance[i] == Integer.MAX_VALUE ? 0 : Math.exp(-distance[i] / 20.0);
            fields[HUMIDITY][i] = (float) Math.clamp(.10 + .48 * ocean + recipe.value("climate", "humidity")
                    + .22 * Math.cos(latitude * 6 * Math.PI) - rain
                    + .35 * noise.sample(p.scale(size * .5), Math.max(64, size * .17), 701),
                0, 1);
        }
    }
    public void biomes() {
        if (finalHeight == null)
            finalHeight = heights();
        for (var config : recipe.biomes())
            TerrainRegistries.BIOMES.get(config.type()).implementation().plan(this, config.parameters());
        for (var config : recipe.materials())
            TerrainRegistries.MATERIALS.get(config.type()).implementation().plan(this, config.parameters());
        var riverNodes = new boolean[biomes.length];
        for (var path : paths)
            if (path.operation() == Operation.CARVE)
                for (int node : path.nodes()) {
                    riverNodes[node] = true;
                    // The neighbouring grid cell is not itself a river biome.

                }
        var scores=new double[biomes.length];
        boolean regions=recipe.biomes().stream().anyMatch(rule->rule.parameters().has("region_max"));
        if(regions)for(int n=0;n<scores.length;n++) {
            var point=grid.positions[n].scale(size*.5);
            double slope=0;
            for(int b:neighbours[n])slope=Math.max(slope,Math.abs(finalHeight[n]-finalHeight[b])
                /(grid.positions[n].sub(grid.positions[b]).length()*size*.5));
            scores[n]=noise.sample(point,192,715)*.8 + Math.tanh((finalHeight[n]-fields[HEIGHT][n])/32)*.12
                + Math.tanh(slope)*.05 + Math.tanh((finalHeight[n]-128)/64)*.08;
        }
        var ranked=scores.clone();Arrays.sort(ranked);
        for (int n = 0; n < biomes.length; n++) {
            boolean river = riverNodes[n];
            boolean coast = false;
            double slope = 0;
            for (int b : neighbours[n]) {
                if ((water[b] == Water.OCEAN.ordinal()) != (water[n] == Water.OCEAN.ordinal()))
                    coast = true;
                slope = Math.max(slope, Math.abs(finalHeight[n] - finalHeight[b])
                    / (grid.positions[n].sub(grid.positions[b]).length() * size * .5));
            }
            // Biome elevation describes the surface that can actually be generated, including tall overlaps.
            var context = new TerrainRegistries.BiomeContext(fields[TEMPERATURE][n], fields[HUMIDITY][n],
                Math.min(finalHeight[n], dev.starfold.worldgen.PlanetTerrain.MAX_Y - 2), river, coast, slope,
                finalHeight[n] - fields[HEIGHT][n],regions?(Arrays.binarySearch(ranked,scores[n])+.5)/ranked.length:0);
            biomes[n] = -1;
            for (short i = 0; i < recipe.biomes().size(); i++) {
                var rule = recipe.biomes().get(i);
                if (rule.matches(context)) {
                    biomes[n] = i;
                    break;
                }
            }
            if (biomes[n] < 0)
                throw new IllegalStateException("No biome rule matches node " + n + ": " + context);
        }
    }
}
