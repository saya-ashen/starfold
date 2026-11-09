package dev.starfold.worldgen.surface;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import java.util.Set;

/** Classification of biome features supported by the shared surface scatter. */
public final class VisibleFeatures {
    private VisibleFeatures() {}
    public static final Set<Feature<?>> TYPES = Set.of(
            Feature.TREE, Feature.HUGE_RED_MUSHROOM, Feature.HUGE_BROWN_MUSHROOM,
            Feature.ICE_SPIKE, Feature.ICEBERG, Feature.FOREST_ROCK, Feature.BAMBOO,
            Feature.HUGE_FUNGUS, Feature.CHORUS_PLANT, Feature.END_ISLAND,
            Feature.BASALT_COLUMNS, Feature.BASALT_PILLAR, Feature.BLOCK_PILE,
            Feature.REPLACE_BLOBS, Feature.VEGETATION_PATCH, Feature.WATERLOGGED_VEGETATION_PATCH,
            Feature.ROOT_SYSTEM, Feature.GLOWSTONE_BLOB, Feature.WEEPING_VINES,
            Feature.TWISTING_VINES, Feature.LARGE_DRIPSTONE, Feature.DESERT_WELL,
            Feature.BLOCK_COLUMN, Feature.END_SPIKE, Feature.DELTA_FEATURE,
            Feature.RANDOM_PATCH, Feature.FLOWER, Feature.NO_BONEMEAL_FLOWER, Feature.SIMPLE_BLOCK);
    public static boolean pointwise(Feature<?> f) {
        return f==Feature.RANDOM_PATCH || f==Feature.FLOWER || f==Feature.NO_BONEMEAL_FLOWER
                || f==Feature.SIMPLE_BLOCK || f==Feature.BLOCK_COLUMN;
    }
    public static boolean visible(Feature<?> feature) {
        var id = net.minecraft.core.registries.BuiltInRegistries.FEATURE.getKey(feature);
        // Unadapted mod features must not sneak visible objects past the shared surface layer.
        return TYPES.contains(feature) || id == null || !id.getNamespace().equals("minecraft");
    }
    public static boolean visible(PlacedFeature placed) {
        return placed.getFeatures().anyMatch(c -> visible(c.feature()));
    }
    /** Count/chance expectation only; spatial/height/biome filters become pointwise root conditions. */
    public static double density(PlacedFeature placed) {
        double count = 1;
        for (var modifier : placed.placement()) {
            String name=net.minecraft.core.registries.BuiltInRegistries.PLACEMENT_MODIFIER_TYPE.getKey(modifier.type()).getPath();
            if(!Set.of("count","count_on_every_layer","rarity_filter","noise_based_count","noise_threshold_count").contains(name))continue;
            var json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE,modifier).getOrThrow().getAsJsonObject();
            String type = json.get("type").getAsString();
            if (type.equals("minecraft:count") || type.equals("minecraft:count_on_every_layer"))
                count *= mean(json.get("count"));
            else if (type.equals("minecraft:rarity_filter")) count /= json.get("chance").getAsDouble();
            else if (type.equals("minecraft:noise_based_count")) count *= Math.max(0,json.get("noise_to_count_ratio").getAsDouble()/2);
            else if (type.equals("minecraft:noise_threshold_count"))
                count *= (json.get("below_noise").getAsDouble()+json.get("above_noise").getAsDouble())/2;
        }
        return Math.max(0,count/256.);
    }
    private static double mean(JsonElement value) {
        if (value == null) return 1;
        var provider = net.minecraft.util.valueproviders.IntProvider.CODEC.parse(JsonOps.INSTANCE,value).getOrThrow();
        var random = net.minecraft.util.RandomSource.create(0x434f554e54L);
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += provider.sample(random);
        return sum/256.;
    }
}
