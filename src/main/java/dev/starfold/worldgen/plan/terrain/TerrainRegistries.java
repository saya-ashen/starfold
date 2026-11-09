package dev.starfold.worldgen.plan.terrain;

import static dev.starfold.worldgen.plan.terrain.TerrainRecipe.*;

import com.google.gson.JsonObject;
import com.mojang.serialization.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import java.util.*;
import java.util.function.*;
import net.minecraft.world.level.block.state.BlockState;

/** The S1 extension points: registered shapes, material/biome rules, and ordered planning stages. */
public final class TerrainRegistries {
    public record Registered<T>(Codec<JsonObject> codec, T implementation) {}
    public static final class Registry<T> {
        private final Map<String, Registered<T>> entries = new LinkedHashMap<>();
        public void register(String id, Codec<JsonObject> codec, T implementation) {
            if (entries.putIfAbsent(id, new Registered<>(codec, implementation)) != null)
                throw new IllegalArgumentException("Duplicate type " + id);
        }
        public Registered<T> get(String id) {
            var found = entries.get(id);
            if (found == null)
                throw new IllegalArgumentException("Unregistered terrain type " + id);
            return found;
        }
    }
    public interface LandformType {
        double profile(double r, double angle, JsonObject parameters);
        default double evaluate(Point point, TerrainPlan.Landform form, int size,
            dev.starfold.worldgen.noise.TerrainNoise noise) {
            double r = point.sub(form.center()).length() * size * .5 / form.radius();
            return r >= 1
                ? 0
                : profile(r, Math.atan2(point.z() - form.center().z(), point.x() - form.center().x()),
                      form.parameters());
        }
        default void place(TerrainPlanner planner, TerrainRecipe.Config config) {
            planner.scatter(config);
        }
    }
    public interface VolumeType {
        boolean contains(double x, double y, double z, JsonObject parameters);
        default void place(TerrainPlanner planner, TerrainRecipe.Config config) {
            planner.scatterVolume(config);
        }
    }
    public record MaterialContext(
        String biome, int depth, double slope, double temperature, boolean shore, BlockState substrate) {}
    public record
        BiomeContext(double temperature, double humidity, double height, boolean river, boolean coast,
            double slope, double landformHeight, double region) {
        public BiomeContext(double temperature,double humidity,double height,boolean river,boolean coast,double slope,double landformHeight) {
            this(temperature,humidity,height,river,coast,slope,landformHeight,0);
        }
    }
    public interface MaterialRule {
        BlockState evaluate(MaterialContext context, JsonObject parameters);
        default MaterialRule bind(JsonObject parameters) { return this; }
        default void plan(TerrainPlanner planner, JsonObject parameters) {
        }
    }
    public interface BiomeRule {
        boolean matches(BiomeContext context, JsonObject parameters);
        net.minecraft.resources.ResourceLocation biome(JsonObject parameters);
        default void plan(TerrainPlanner planner, JsonObject parameters) {
        }
    }
    public static final Registry<LandformType> LANDFORMS = new Registry<>();
    public static final Registry<VolumeType> VOLUMES = new Registry<>();
    public static final Registry<MaterialRule> MATERIALS = new Registry<>();
    public static final Registry<BiomeRule> BIOMES = new Registry<>();
    private static final Map<String, Consumer<TerrainPlanner>> STAGES = new LinkedHashMap<>();
    public static void registerStage(String id, Consumer<TerrainPlanner> stage) {
        if (STAGES.putIfAbsent(id, stage) != null)
            throw new IllegalArgumentException("Duplicate stage " + id);
    }
    public static Consumer<TerrainPlanner> stage(String id) {
        var s = STAGES.get(id);
        if (s == null)
            throw new IllegalArgumentException("Unregistered planner stage " + id);
        return s;
    }
    public static Codec<JsonObject> parameters(Consumer<JsonObject> validate) {
        return OBJECT.comapFlatMap(j -> {
            try {
                validate.accept(j);
                return DataResult.success(j.deepCopy());
            } catch (RuntimeException e) {
                return DataResult.error(() -> e.getMessage());
            }
        }, JsonObject::deepCopy);
    }
    private static final Codec<JsonObject> SHAPE = parameters(j -> {
        keys(j, "density", "radius_min", "radius_max", "amplitude", "operation");
        number(j, "density", 0, 8);
        double min = number(j, "radius_min", 16, 256);
        number(j, "radius_max", min, 256);
        number(j, "amplitude", 0, 128);
        var op = TerrainPlan.Operation.valueOf(j.get("operation").getAsString().toUpperCase(Locale.ROOT));
        if (op != TerrainPlan.Operation.RAISE && op != TerrainPlan.Operation.LOWER)
            throw new IllegalArgumentException("Landform operation");
    });
    static {
        LANDFORMS.register("starfold:crater", SHAPE, new LandformType() {
            public double profile(double r, double a, JsonObject p) {
                r *= 1.3;
                if (r >= 1.3) return 0;
                if (r < .85) return -Math.pow(1 - r * r / (.85 * .85), 2);
                return .35 * Math.pow(Math.sin(Math.PI * (r - .85) / .45), 2);
            }
            public double evaluate(Point point, TerrainPlan.Landform form, int size,
                dev.starfold.worldgen.noise.TerrainNoise noise) {
                return LandformType.super.evaluate(point,form,size,noise) * Math.min(1,form.radius()/96);
            }
        });
        LANDFORMS.register("starfold:volcano", SHAPE, new LandformType() {
            public double profile(double r, double a, JsonObject p) {
                double cone = Math.pow(1 - r, 1.35), crater = 1 - smooth(r / .24);
                return cone - .8 * crater;
            }
            public void place(TerrainPlanner planner, TerrainRecipe.Config config) {
                planner.scatter(config, true);
            }
        });
        LANDFORMS.register("starfold:canyon", SHAPE, channel(false));
        LANDFORMS.register("starfold:glacial_rift", SHAPE, channel(true));
        LANDFORMS.register(
            "starfold:dunes", SHAPE, new LandformType() {
                public double profile(double r, double a, JsonObject p) { return 1 - smooth(r); }
                public double evaluate(Point point, TerrainPlan.Landform form, int size,
                    dev.starfold.worldgen.noise.TerrainNoise noise) {
                    var p = point.scale(size * .5);
                    double r = point.sub(form.center()).length() * size * .5 / form.radius();
                    double phase = (p.x() + p.z() * .45) * .4 + noise.sample(p, 48, 41) * 3;
                    return (1 - smooth(r)) * (Math.pow(.5 + .5 * Math.sin(phase), 3) - .3);
                }
            });
        LANDFORMS.register("starfold:plateau", SHAPE, (r, a, p) -> 1 - smooth((r - .65) / .35));
        var volume = parameters(j -> {
            keys(j, "density", "radius", "height", "operation", "slope_min", "slope_max", "cave_connection", "offset", "wall_x", "wall_z");
            number(j, "density", 0, 8);
            number(j, "radius", 2, 64);
            number(j, "height", 2, 64);
            number(j, "offset", -64, 96);
            double slope = number(j, "slope_min", 0, 200);
            number(j, "slope_max", slope, 200);
            bool(j, "cave_connection");
            if(j.has("wall_x") || j.has("wall_z")) {
                double x=number(j,"wall_x",-1,1),z=number(j,"wall_z",-1,1);
                if(Math.abs(x*x+z*z-1)>1e-6)throw new IllegalArgumentException("wall direction");
            }
            var op = TerrainPlan.Operation.valueOf(j.get("operation").getAsString().toUpperCase(Locale.ROOT));
            if (op != TerrainPlan.Operation.FILL && op != TerrainPlan.Operation.CARVE)
                throw new IllegalArgumentException("Volume operation");
        });
        VolumeType ellipsoid = (x,y,z,p) -> x*x + y*y + z*z <= 1;
        var wall = new VolumeType() {
            public boolean contains(double x,double y,double z,JsonObject p) { return ellipsoid.contains(x,y,z,p); }
            public void place(TerrainPlanner planner,TerrainRecipe.Config config) { planner.scatterVolume(config,true); }
        };
        VOLUMES.register("starfold:overhang", volume, wall);
        VOLUMES.register("starfold:undercut", volume, wall);
        VOLUMES.register("starfold:cave_mouth", volume, ellipsoid);
        VOLUMES.register("starfold:sinkhole", volume, ellipsoid);
        VOLUMES.register("starfold:floating_rock", volume, ellipsoid);
        VOLUMES.register("starfold:arch", volume, (x,y,z,p) -> {
            double ring = (Math.sqrt(x*x+y*y)-.7)/.3;
            return ring*ring+z*z <= 1;
        });
        var material = parameters(j -> {
            keys(j, "block", "depth_min", "depth_max", "temperature_min", "temperature_max", "slope_min",
                "shore", "biome");
            block(j.get("block").getAsString());
            int min = integer(j, "depth_min", 0, 1024);
            integer(j, "depth_max", min, 1024);
            double t = number(j, "temperature_min", -300, 300);
            number(j, "temperature_max", t, 300);
            number(j, "slope_min", 0, 200);
            bool(j, "shore");
            j.get("biome").getAsString();
        });
        MATERIALS.register("starfold:surface", material, new MaterialRule() {
            public BlockState evaluate(MaterialContext c, JsonObject p) { return bind(p).evaluate(c,p); }
            public MaterialRule bind(JsonObject p) {
                int low = p.get("depth_min").getAsInt(), high = p.get("depth_max").getAsInt();
                double cold = p.get("temperature_min").getAsDouble(), hot = p.get("temperature_max").getAsDouble(),
                    slope = p.get("slope_min").getAsDouble();
                boolean shore = p.get("shore").getAsBoolean();
                String biome = p.get("biome").getAsString();
                var state = block(p.get("block").getAsString()).defaultBlockState();
                return (c, ignored) -> c.depth < low || c.depth > high || c.temperature < cold
                    || c.temperature > hot || c.slope < slope || shore && !c.shore
                    || !biome.isEmpty() && !biome.equals(c.biome) ? null : state;
            }
        });
        var biome = parameters(j -> {
            keys(j, "biome", "temperature_min", "temperature_max", "humidity_min", "humidity_max",
                "height_min", "height_max", "river", "coast");
            net.minecraft.resources.ResourceLocation.parse(j.get("biome").getAsString());
            double t = number(j, "temperature_min", -300, 300);
            number(j, "temperature_max", t, 300);
            double h = number(j, "humidity_min", 0, 1);
            number(j, "humidity_max", h, 1);
            double y = number(j, "height_min", -1024, 320);
            number(j, "height_max", y, 320);
            bool(j, "river");
            bool(j, "coast");
        });
        BIOMES.register("starfold:climate", biome, new BiomeRule() {
            public net.minecraft.resources.ResourceLocation biome(JsonObject p) {
                return net.minecraft.resources.ResourceLocation.parse(p.get("biome").getAsString());
            }
            public boolean matches(BiomeContext c, JsonObject p) {
                return c.temperature >= p.get("temperature_min").getAsDouble()
                    && c.temperature <= p.get("temperature_max").getAsDouble()
                    && c.humidity >= p.get("humidity_min").getAsDouble()
                    && c.humidity <= p.get("humidity_max").getAsDouble()
                    && c.height >= p.get("height_min").getAsDouble()
                    && c.height <= p.get("height_max").getAsDouble()
                    && (!p.get("river").getAsBoolean() || c.river)
                    && (!p.get("coast").getAsBoolean() || c.coast);
            }
        });
        var terrainBiome = parameters(j -> {
            keys(j, "biome", "height_min", "height_max", "slope_min", "slope_max", "landform_min", "landform_max", "region_min", "region_max");
            if(j.has("region_min"))number(j,"region_min",0,1);
            if(j.has("region_max"))number(j,"region_max",j.has("region_min")?j.get("region_min").getAsDouble():0,1);
            net.minecraft.resources.ResourceLocation.parse(j.get("biome").getAsString());
            double y = number(j, "height_min", -1024, 320);
            number(j, "height_max", y, 320);
            double slope = number(j, "slope_min", 0, 200);
            number(j, "slope_max", slope, 200);
            double form = number(j, "landform_min", -2048, 2048);
            number(j, "landform_max", form, 2048);
        });
        BIOMES.register("starfold:terrain", terrainBiome, new BiomeRule() {
            public net.minecraft.resources.ResourceLocation biome(JsonObject p) {
                return net.minecraft.resources.ResourceLocation.parse(p.get("biome").getAsString());
            }
            public boolean matches(BiomeContext c, JsonObject p) {
                return c.height >= p.get("height_min").getAsDouble()
                    && c.height <= p.get("height_max").getAsDouble()
                    && c.slope >= p.get("slope_min").getAsDouble()
                    && c.slope <= p.get("slope_max").getAsDouble()
                    && c.landformHeight >= p.get("landform_min").getAsDouble()
                    && c.landformHeight <= p.get("landform_max").getAsDouble()
                    && (!p.has("region_min") || c.region>=p.get("region_min").getAsDouble())
                    && (!p.has("region_max") || c.region<=p.get("region_max").getAsDouble());
            }
        });
        registerStage("starfold:plates", TerrainPlanner::plates);
        registerStage("starfold:landforms", TerrainPlanner::landforms);
        registerStage("starfold:erosion", TerrainPlanner::erosion);
        registerStage("starfold:climate", TerrainPlanner::climate);
        registerStage("starfold:biomes", TerrainPlanner::biomes);
        registerStage("starfold:volumes", TerrainPlanner::volumes);
    }
    private static LandformType channel(boolean ice) {
        return new LandformType() {
            public double profile(double r, double angle, JsonObject parameters) {
                return 1 - smooth(r);
            }
            public double evaluate(Point point, TerrainPlan.Landform form, int size,
                dev.starfold.worldgen.noise.TerrainNoise noise) {
                double r = point.sub(form.center()).length() * size * .5 / form.radius();
                if (r >= 1)
                    return 0;
                var p = point.scale(size * .5);
                // Preserve the global channels and physical wavelengths of the original kernel mathematics.
                var warped = p.add(new Point(noise.sample(p, 96, 31), noise.sample(p, 96, 32),
                    noise.sample(p, 96, 33)).scale(24));
                double distance = Math.abs(noise.sample(warped, ice ? 64 : 96, ice ? 22 : 12));
                double branch = Math.abs(noise.sample(warped, ice ? 32 : 48, ice ? 24 : 14));
                distance = Math.min(distance, branch * 1.6);
                double cut = 1 - smooth((distance - (ice ? .018 : .025)) / (ice ? .055 : .11));
                double plateau = noise.sample(p, 240, ice ? 21 : 11);
                double terrace = Math.floor((plateau + 1) * 4) * 4;
                double height = ice ? 20 + terrace - 48 * cut : 16 + terrace - 64 * cut;
                return height / (ice ? 36 : 48) * (1 - smooth(r));
            }
        };
    }
    public static double smooth(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
    private TerrainRegistries() {
    }
}
