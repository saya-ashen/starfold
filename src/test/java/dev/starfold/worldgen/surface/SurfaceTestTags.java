package dev.starfold.worldgen.surface;

import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** VanillaRegistries.createLookup builds data registries, but does not load block tags. Features need real tags. */
public final class SurfaceTestTags {
    private SurfaceTestTags() {}
    public static void bind() throws Exception {
        var tags = new HashMap<TagKey<Block>,List<Holder<Block>>>();
        for (var field : BlockTags.class.getFields()) if (field.getType() == TagKey.class) {
            @SuppressWarnings("unchecked") var key = (TagKey<Block>)field.get(null);
            load(key,tags,new HashSet<>());
        }
        load(dev.starfold.api.SurfaceClass.SEASONAL_PLANTS,tags,new HashSet<>());
        BuiltInRegistries.BLOCK.bindTags(tags);
    }
    private static List<Holder<Block>> load(TagKey<Block> key, Map<TagKey<Block>,List<Holder<Block>>> tags,
                                            Set<TagKey<Block>> visiting) throws Exception {
        if (tags.containsKey(key)) return tags.get(key);
        if (!visiting.add(key)) throw new IllegalStateException("Cyclic block tag "+key);
        var result = new LinkedHashSet<Holder<Block>>();
        var path = "data/"+key.location().getNamespace()+"/tags/block/"+key.location().getPath()+".json";
        try (var stream = SurfaceTestTags.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing vanilla block tag "+path);
            var json = JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            for (var value : json.getAsJsonArray("values")) {
                String id = value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString();
                if (id.startsWith("#")) result.addAll(load(TagKey.create(Registries.BLOCK,ResourceLocation.parse(id.substring(1))),tags,visiting));
                else result.add(BuiltInRegistries.BLOCK.getHolderOrThrow(ResourceKey.create(Registries.BLOCK,ResourceLocation.parse(id))));
            }
        }
        visiting.remove(key);
        var list = List.copyOf(result); tags.put(key,list); return list;
    }
}
