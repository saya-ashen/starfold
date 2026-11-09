package dev.starfold.worldgen.plan.terrain;

import com.google.gson.*;
import com.mojang.serialization.*;
import dev.starfold.worldgen.PlanetMinerals;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/** Fixed recipe, saved together with the fields; no live data-pack lookup during evaluation. */
public final class TerrainRecipe {
    public static final int VERSION = 2;
    public static final Codec<JsonObject> OBJECT = Codec.PASSTHROUGH.comapFlatMap(d -> {
        var json = d.convert(JsonOps.INSTANCE).getValue();
        return json.isJsonObject() ? DataResult.success(json.getAsJsonObject())
                                   : DataResult.error(() -> "Expected object");
    }, j -> new Dynamic<>(JsonOps.INSTANCE, j));
    public static final Codec<TerrainRecipe> CODEC = OBJECT.comapFlatMap(j -> {
        try {
            return DataResult.success(new TerrainRecipe(j));
        } catch (RuntimeException e) {
            return DataResult.error(() -> "Terrain recipe: " + e.getMessage());
        }
    }, TerrainRecipe::json);
    public record Config(String type, JsonObject parameters, TerrainRegistries.MaterialRule materialRule) {
        public Config {
            parameters = parameters.deepCopy();
        }
        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }
        public net.minecraft.world.level.block.state.BlockState material(
            TerrainRegistries.MaterialContext context) {
            return materialRule.evaluate(context, parameters);
        }
        public boolean matches(TerrainRegistries.BiomeContext context) {
            return TerrainRegistries.BIOMES.get(type).implementation().matches(context, parameters);
        }
        public String biomeId() {
            return TerrainRegistries.BIOMES.get(type).implementation().biome(parameters).toString();
        }
    }
    private final JsonObject data;
    private final List<Config> landforms, volumes, materials, biomes;
    private final List<String> stages;
    private final PlanetMinerals minerals;
    private final Block liquid;
    private final double caveWidth,caveScale;
    public double caveWidth() { return caveWidth; }
    public double caveScale() { return caveScale; }
    public TerrainRecipe(JsonObject source) {
        data = source.deepCopy();
        keys(data, "version", "planner", "land_fraction", "relief", "landforms", "volumes", "erosion", "climate",
            "biomes", "materials", "strata", "ores", "caves", "water", "preferences");
        if (integer(data, "version", VERSION, VERSION) != VERSION)
            throw new IllegalArgumentException("Recipe version");
        var planner = object(data, "planner");
        keys(planner, "stages");
        var stages = new ArrayList<String>();
        for (var s : array(planner, "stages", 16)) {
            String id = s.getAsString();
            TerrainRegistries.stage(id);
            stages.add(id);
        }
        this.stages = List.copyOf(stages);
        number(data, "land_fraction", 0, 1);
        var relief = object(data, "relief");
        keys(relief, "mountains", "hills", "roughness");
        number(relief, "mountains", 0, 175);
        number(relief, "hills", 0, 64);
        number(relief, "roughness", 0, 8);
        var erosion = object(data, "erosion");
        keys(erosion, "rivers", "strength", "river_threshold");
        bool(erosion, "rivers");
        number(erosion, "strength", 0, 2);
        number(erosion, "river_threshold", 2, 4096);
        var climate = object(data, "climate");
        keys(climate, "temperature", "latitude_gradient", "humidity", "lapse_rate");
        number(climate, "temperature", -200, 200);
        number(climate, "latitude_gradient", 0, 100);
        number(climate, "humidity", -1, 1);
        number(climate, "lapse_rate", 0, .1);
        landforms = configs(data, "landforms", TerrainRegistries.LANDFORMS, 64);
        volumes = configs(data, "volumes", TerrainRegistries.VOLUMES, 64);
        materials = configs(data, "materials", TerrainRegistries.MATERIALS, 64);
        biomes = configs(data, "biomes", TerrainRegistries.BIOMES, 64);
        if (biomes.isEmpty())
            throw new IllegalArgumentException("Empty biome table");
        var mineralJson = new JsonObject();
        mineralJson.add("ores", data.get("ores"));
        mineralJson.add("strata", data.get("strata"));
        minerals = PlanetMinerals.CODEC.parse(JsonOps.INSTANCE, mineralJson).getOrThrow();
        var caves = object(data, "caves");
        keys(caves, "width", "scale");
        caveWidth=number(caves, "width", 0, .2);
        caveScale=number(caves, "scale", 4, 128);
        var water = object(data, "water");
        keys(water, "block", "sea_level", "freeze");
        this.liquid = block(water.get("block").getAsString());
        var liquid = this.liquid.defaultBlockState();
        if (!liquid.isAir() && liquid.getFluidState().isEmpty())
            throw new IllegalArgumentException("Water block must contain fluid");
        number(water, "sea_level", -1024, 300);
        bool(water, "freeze");
        var p = object(data, "preferences");
        keys(p, "desolation", "damage", "burial", "overlap", "road_continuity", "density");
        for (var e : p.entrySet()) number(p, e.getKey(), 0, 1);
    }
    private static <T> List<Config> configs(
        JsonObject root, String key, TerrainRegistries.Registry<T> registry, int max) {
        var result = new ArrayList<Config>();
        for (var value : array(root, key, max)) {
            var item = value.getAsJsonObject();
            keys(item, "type", "parameters");
            String id = item.get("type").getAsString();
            var params =
                registry.get(id).codec().parse(JsonOps.INSTANCE, item.get("parameters")).getOrThrow();
            item.add("parameters", params.deepCopy());
            var implementation = registry.get(id).implementation();
            result.add(new Config(id, params, implementation instanceof TerrainRegistries.MaterialRule rule
                ? rule.bind(params) : null));
        }
        return List.copyOf(result);
    }
    public JsonObject json() {
        return data.deepCopy();
    }
    public List<Config> landforms() {
        return landforms;
    }
    public List<Config> volumes() {
        return volumes;
    }
    public List<Config> materials() {
        return materials;
    }
    public List<Config> biomes() {
        return biomes;
    }
    public List<String> stages() {
        return stages;
    }
    public PlanetMinerals minerals() {
        return minerals;
    }
    public double value(String field) {
        return data.get(field).getAsDouble();
    }
    public double value(String group, String field) {
        return data.getAsJsonObject(group).get(field).getAsDouble();
    }
    public boolean flag(String group, String field) {
        return data.getAsJsonObject(group).get(field).getAsBoolean();
    }
    public Block liquid() {
        return liquid;
    }
    public Set<ResourceLocation> biomeIds() {
        var ids = new LinkedHashSet<ResourceLocation>();
        for (var b : biomes) ids.add(ResourceLocation.parse(b.biomeId()));
        return Set.copyOf(ids);
    }
    public static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
            .orElseThrow(() -> new IllegalArgumentException("Unknown block " + id));
    }
    public static JsonObject object(JsonObject j, String k) {
        return j.get(k).getAsJsonObject();
    }
    public static JsonArray array(JsonObject j, String k, int max) {
        var a = j.getAsJsonArray(k);
        if (a.size() > max)
            throw new IllegalArgumentException(k + " count");
        return a;
    }
    public static void keys(JsonObject j, String... allowed) {
        var keys = Set.of(allowed);
        for (String k : j.keySet())
            if (!keys.contains(k))
                throw new IllegalArgumentException("Unknown field " + k);
    }
    public static double number(JsonObject j, String k, double min, double max) {
        double n = j.get(k).getAsDouble();
        if (!Double.isFinite(n) || n < min || n > max)
            throw new IllegalArgumentException(k + " outside " + min + ".." + max);
        return n;
    }
    public static int integer(JsonObject j, String k, int min, int max) {
        double n = number(j, k, min, max);
        if (n != Math.rint(n))
            throw new IllegalArgumentException(k + " integer");
        return (int) n;
    }
    @Override
    public boolean equals(Object o) {
        return o instanceof TerrainRecipe r && data.equals(r.data);
    }
    @Override
    public int hashCode() {
        return data.hashCode();
    }
    public static boolean bool(JsonObject j, String key) {
        var value = j.getAsJsonPrimitive(key);
        if (value.isBoolean())
            return value.getAsBoolean();
        if (value.isNumber() && (value.getAsDouble() == 0 || value.getAsDouble() == 1)) {
            boolean result = value.getAsInt() == 1;
            j.addProperty(key, result);
            return result;
        }
        throw new IllegalArgumentException(key + " boolean");
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(
                k -> result.add(k, canonical(value.getAsJsonObject().get(k))));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var child : value.getAsJsonArray()) result.add(canonical(child));
            return result;
        }
        return value;
    }
    @Override
    public String toString() {
        return canonical(data).toString();
    }
}
