package dev.starfold.worldgen.plan;

import com.mojang.serialization.Lifecycle;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.*;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.surface.SurfaceTestTags;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Vanilla registries and real shipped NBT templates, with no running Level or server. */
final class PlanetPlanFixtures {
    static StructureSandbox.Context context;
    static LevelStorageSource.LevelStorageAccess storage;
    static Path directory;

    static void boot() throws Exception {
        close();
        try {
            initialize();
        } catch (Exception error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static void close() throws IOException {
        context = null;
        try {
            if (storage != null) storage.close();
        } finally {
            storage = null;
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
                directory = null;
            }
        }
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void initialize() throws Exception {
        ExperienceFixtures.boot();SurfaceTestTags.bind();
        var lookup=ExperienceFixtures.registryLookup();
        var registries=new ArrayList<Registry<?>>();
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries().forEach(e->registries.add(e.value()));
        for(var key:lookup.listRegistries().toList())if(registries.stream().noneMatch(r->r.key().equals(key)))registries.add(copy(lookup,(ResourceKey)key));
        var access=new RegistryAccess.ImmutableRegistryAccess(registries);
        var biomeTags=new HashMap<net.minecraft.tags.TagKey<Biome>,List<Holder<Biome>>>();
        for(var structure:access.registryOrThrow(Registries.STRUCTURE))
            loadBiomeTag(structure.biomes().unwrapKey().orElseThrow(),access.registryOrThrow(Registries.BIOME),biomeTags);
        for(var field:net.minecraft.tags.BiomeTags.class.getFields())if(field.getType()==net.minecraft.tags.TagKey.class)
            loadBiomeTag((net.minecraft.tags.TagKey<Biome>)field.get(null),access.registryOrThrow(Registries.BIOME),biomeTags);
        ((MappedRegistry<Biome>)access.registryOrThrow(Registries.BIOME)).bindTags(biomeTags);
        reparse(registries,Registries.TEMPLATE_POOL,net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.DIRECT_CODEC);
        reparse(registries,Registries.STRUCTURE,Structure.DIRECT_CODEC);
        reparse(registries,Registries.STRUCTURE_SET,StructureSet.DIRECT_CODEC);
        access=new RegistryAccess.ImmutableRegistryAccess(registries);
        var structureTags=new HashMap<net.minecraft.tags.TagKey<Structure>,List<Holder<Structure>>>();
        for(var tag:List.of(TraditionalPlanetPlanner.RIVER_SETTLEMENTS,TraditionalPlanetPlanner.HIGHLAND_RUINS)) {
            var path=Path.of("src/main/resources/data/starfold/tags/worldgen/structure/"+tag.location().getPath()+".json");
            var entries=com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("values");
            var holders=new ArrayList<Holder<Structure>>();
            for(var id:entries)holders.add(access.registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(
                ResourceKey.create(Registries.STRUCTURE,ResourceLocation.parse(id.getAsString()))));
            structureTags.put(tag,holders);
        }
        ((MappedRegistry<Structure>)access.registryOrThrow(Registries.STRUCTURE)).bindTags(structureTags);
        var sets=(MappedRegistry<StructureSet>)access.registryOrThrow(Registries.STRUCTURE_SET);
        var json=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/starfold/tags/worldgen/structure_set/planets.json"))).getAsJsonObject();
        var holders=new ArrayList<Holder<StructureSet>>();
        for(var id:json.getAsJsonArray("values"))holders.add(sets.getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,ResourceLocation.parse(id.getAsString()))));
        sets.bindTags(Map.of(SystemChunkGenerator.STRUCTURES,holders));
        directory=Files.createTempDirectory("starfold-planet-plan-");
        storage=LevelStorageSource.createDefault(directory).createAccess("templates");
        var templates=new StructureTemplateManager(ResourceManager.Empty.INSTANCE,storage,DataFixers.getDataFixer(),BuiltInRegistries.BLOCK.asLookup()) {
            private final Map<ResourceLocation,Optional<StructureTemplate>> loaded=new HashMap<>();
            @Override public Optional<StructureTemplate> get(ResourceLocation id) {
                return loaded.computeIfAbsent(id,key->{
                    try(var stream=PlanetPlanFixtures.class.getClassLoader().getResourceAsStream("data/"+id.getNamespace()+"/structure/"+id.getPath()+".nbt")) {
                        if(stream==null)throw new IllegalArgumentException("Missing vanilla template "+id);
                        return Optional.of(readStructure(NbtIo.readCompressed(stream,NbtAccounter.create(16_000_000))));
                    } catch(IOException e) { throw new UncheckedIOException(e); }
                });
            }
        };
        var random=RandomState.create(NoiseGeneratorSettings.dummy(),lookup.lookupOrThrow(Registries.NOISE),42);
        context=new StructureSandbox.Context(access,templates,random);
    }
    private static <T> Registry<T> copy(HolderLookup.Provider lookup,ResourceKey<? extends Registry<T>> key) {
        var registry=new MappedRegistry<T>(key,Lifecycle.stable());
        lookup.lookupOrThrow(key).listElements().forEach(h->Registry.register(registry,h.key(),h.value()));
        return registry.freeze();
    }
    private static <T> void reparse(List<Registry<?>> registries,ResourceKey<? extends Registry<T>> key,com.mojang.serialization.Codec<T> codec) throws IOException {
        var parsed=new MappedRegistry<T>(key,Lifecycle.stable());
        if(key.equals(Registries.TEMPLATE_POOL)) {
            var registration=parsed.createRegistrationLookup();
            ExperienceFixtures.registryLookup().lookupOrThrow(key).listElements().forEach(h->registration.getOrThrow(h.key()));
            registries.removeIf(r->r.key().equals(key));registries.add(parsed);
        }
        var access=new RegistryAccess.ImmutableRegistryAccess(registries);
        var ops=RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,access);
        for(var entry:ExperienceFixtures.registryLookup().lookupOrThrow(key).listElements().toList()) {
            var id=entry.key().location();
            try(var stream=PlanetPlanFixtures.class.getClassLoader().getResourceAsStream("data/"+id.getNamespace()+"/"+key.location().getPath()+"/"+id.getPath()+".json")) {
                if(stream==null)throw new IOException("Missing registry JSON "+entry.key());
                var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
                Registry.register(parsed,entry.key(),codec.parse(ops,json).getOrThrow());
            }
        }
        registries.removeIf(r->r.key().equals(key));registries.add(parsed.freeze());
    }
    private static List<Holder<Biome>> loadBiomeTag(net.minecraft.tags.TagKey<Biome> key,Registry<Biome> registry,
            Map<net.minecraft.tags.TagKey<Biome>,List<Holder<Biome>>> tags) throws IOException {
        if(tags.containsKey(key))return tags.get(key);
        var result=new LinkedHashSet<Holder<Biome>>();
        try(var stream=PlanetPlanFixtures.class.getClassLoader().getResourceAsStream("data/"+key.location().getNamespace()+"/tags/worldgen/biome/"+key.location().getPath()+".json")) {
            if(stream==null)throw new IOException("Missing biome tag "+key);
            var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:json.getAsJsonArray("values")) {
                String id=value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString();
                if(id.startsWith("#"))result.addAll(loadBiomeTag(net.minecraft.tags.TagKey.create(Registries.BIOME,ResourceLocation.parse(id.substring(1))),registry,tags));
                else result.add(registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME,ResourceLocation.parse(id))));
            }
        }
        var list=List.copyOf(result);tags.put(key,list);return list;
    }
    static PlanetChunkGenerator planet(int size,String biome) {
        var holder=context.registries().registryOrThrow(Registries.BIOME).getHolderOrThrow(ResourceKey.create(Registries.BIOME,ResourceLocation.withDefaultNamespace(biome)));
        var base=PlanetGeneration.defaults(dev.starfold.worldgen.PlanetTerrain.VERSION);
        var json=base.recipe().json();json.addProperty("land_fraction",1);json.getAsJsonObject("water").addProperty("block","minecraft:air");
        json.getAsJsonObject("relief").addProperty("mountains",12);json.getAsJsonObject("relief").addProperty("hills",4);json.getAsJsonObject("relief").addProperty("roughness",.3);
        json.getAsJsonObject("erosion").addProperty("rivers",false);json.getAsJsonObject("climate").addProperty("temperature",30);json.getAsJsonObject("climate").addProperty("latitude_gradient",0);
        var rule=json.getAsJsonArray("biomes").asList().getLast().deepCopy().getAsJsonObject();rule.getAsJsonObject("parameters").addProperty("biome",holder.unwrapKey().orElseThrow().location().toString());
        var rules=new com.google.gson.JsonArray();rules.add(rule);json.add("biomes",rules);
        json.getAsJsonObject("caves").addProperty("width",.2);
        var recipe=new dev.starfold.worldgen.plan.terrain.TerrainRecipe(json);
        var policy=new PlanetGeneration("fixture",PlanetGeneration.SURFACE_VERSION,recipe,size,191,"core_shell",false,false,true,dev.starfold.worldgen.PlanetEnvironment.defaults(false,size));
        return new PlanetChunkGenerator(new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()),holder,List.of()),42,PlanetTerrain.VERSION,
                Optional.empty(),Optional.of(policy));
    }
}
