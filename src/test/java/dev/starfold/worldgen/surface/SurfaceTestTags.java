package dev.starfold.worldgen.surface;

import com.google.gson.JsonParser;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** VanillaRegistries.createLookup omits block/fluid tags; native features and survival checks need both. */
public final class SurfaceTestTags {
    private SurfaceTestTags() {}

    public static void bind() throws Exception {
        var tags = new HashMap<TagKey<Block>, List<Holder<Block>>>();
        for (var field : BlockTags.class.getFields())
            if (field.getType() == TagKey.class) {
                @SuppressWarnings("unchecked")
                var key = (TagKey<Block>) field.get(null);
                load(key, BuiltInRegistries.BLOCK, tags, new HashSet<>());
            }
        load(
                dev.starfold.api.SurfaceClass.SEASONAL_PLANTS,
                BuiltInRegistries.BLOCK,
                tags,
                new HashSet<>());
        BuiltInRegistries.BLOCK.bindTags(tags);
        var fluids = new HashMap<TagKey<Fluid>, List<Holder<Fluid>>>();
        load(FluidTags.WATER, BuiltInRegistries.FLUID, fluids, new HashSet<>());
        load(FluidTags.LAVA, BuiltInRegistries.FLUID, fluids, new HashSet<>());
        BuiltInRegistries.FLUID.bindTags(fluids);
    }

    private static <T> List<Holder<T>> load(
            TagKey<T> key,
            Registry<T> registry,
            Map<TagKey<T>, List<Holder<T>>> tags,
            Set<TagKey<T>> visiting)
            throws Exception {
        if (tags.containsKey(key)) return tags.get(key);
        if (!visiting.add(key)) throw new IllegalStateException("Cyclic tag " + key);
        var result = new LinkedHashSet<Holder<T>>();
        var path =
                "data/"
                        + key.location().getNamespace()
                        + "/tags/"
                        + key.registry().location().getPath()
                        + "/"
                        + key.location().getPath()
                        + ".json";
        try (var stream = SurfaceTestTags.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing vanilla tag " + path);
            var json =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var value : json.getAsJsonArray("values")) {
                String id =
                        value.isJsonPrimitive()
                                ? value.getAsString()
                                : value.getAsJsonObject().get("id").getAsString();
                if (id.startsWith("#"))
                    result.addAll(
                            load(
                                    TagKey.create(
                                            key.registry(),
                                            ResourceLocation.parse(id.substring(1))),
                                    registry,
                                    tags,
                                    visiting));
                else
                    result.add(
                            registry.getHolderOrThrow(
                                    ResourceKey.create(
                                            key.registry(), ResourceLocation.parse(id))));
            }
        }
        visiting.remove(key);
        var list = List.copyOf(result);
        tags.put(key, list);
        return list;
    }
}
