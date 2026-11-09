package dev.starfold.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.*;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetSpawn;
import dev.starfold.universe.PlanetWorldPreset;

import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

public class PlanetBiomeFixtures {
    public static final ResourceKey<Biome> CUSTOM_FOREST = ResourceKey.create(
            Registries.BIOME, ResourceLocation.fromNamespaceAndPath("starfold", "custom_forest"));
    public static Registry<Biome> palette;
    public static HolderLookup.Provider lookup;
    public static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);
    public final PlanetTerrain terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION);

    @BeforeAll
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var r = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
            Registry.register(r,holder.key(),holder.value()));
        var forest = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.FOREST).value();
        Registry.register(r, CUSTOM_FOREST, new Biome.BiomeBuilder()
                .hasPrecipitation(true).temperature(.7f).downfall(.8f)
                .specialEffects(forest.getSpecialEffects())
                .mobSpawnSettings(forest.getMobSettings())
                .generationSettings(forest.getGenerationSettings()).build());
        palette = r.freeze();
        var base = lookup;
        lookup =
                HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                base.listRegistries()
                                        .filter(k -> !k.equals(Registries.BIOME))
                                        .map(
                                                k ->
                                                        (HolderLookup.RegistryLookup<?>)
                                                                base.lookupOrThrow(k)),
                                java.util.stream.Stream.of(palette.asLookup())));
    }

    public static dev.starfold.worldgen.plan.terrain.TerrainRecipe recipe(String type) {
        try {
            var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("worlds/src/main/resources/data/starfold_worlds/starfold/planet_type/"+type+".json"))).getAsJsonObject();
            return dev.starfold.worldgen.plan.terrain.TerrainRecipe.CODEC.parse(JsonOps.INSTANCE,json.get("terrain")).getOrThrow();
        } catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    public PlanetBiomes source(long seed) {
        return new PlanetBiomes(seed, PlanetGeneration.defaults(PlanetTerrain.VERSION).recipe().biomeIds().stream()
                .sorted(java.util.Comparator.comparing(ResourceLocation::toString))
                .map(id -> (Holder<Biome>) palette.getHolderOrThrow(ResourceKey.create(Registries.BIOME, id)))
                .toList());
    }

    public PlanetChunkGenerator generator() {
        var settings =
                new FlatLevelGeneratorSettings(
                        Optional.of(HolderSet.direct()),
                        palette.getHolderOrThrow(Biomes.PLAINS),
                        List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        return new PlanetChunkGenerator(
                settings,
                terrain.seed(),
                PlanetTerrain.VERSION,
                Optional.of(source(terrain.seed())));
    }

    ProtoChunk chunk(PlanetChunkGenerator g, int x, int z) {
        var c = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, HEIGHT, palette, null);
        g.createBiomes(null, null, null, c).join();
        c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        dev.starfold.worldgen.SystemGeneration.fill(g, c).join();
        return c;
    }

    public int[] values(ChunkAccess c) {
        var out = new int[GroundChunkSnapshot.VALUES];
        for (int y = -64; y < 320; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    out[x + 16 * (z + 16 * (y + 64))] =
                            Block.BLOCK_STATE_REGISTRY.getId(
                                    c.getBlockState(
                                            new BlockPos(
                                                    c.getPos().getMinBlockX() + x,
                                                    y,
                                                    c.getPos().getMinBlockZ() + z)));
        return out;
    }

    public static PlanetTerrain tinted(java.util.List<ResourceLocation> ids) {
        var base=PlanetGeneration.defaults(PlanetTerrain.VERSION);var json=base.recipe().json();
        var rules=new com.google.gson.JsonArray();for(var id:ids){var rule=json.getAsJsonArray("biomes").asList().getLast().deepCopy().getAsJsonObject();rule.getAsJsonObject("parameters").addProperty("biome",id.toString());rules.add(rule);}
        json.add("biomes",rules);var recipe=new dev.starfold.worldgen.plan.terrain.TerrainRecipe(json);
        var terrain=new PlanetTerrain(42,PlanetTerrain.VERSION,base.withRecipe(recipe));
        var planner=new dev.starfold.worldgen.plan.terrain.TerrainPlanner(42,base.size(),recipe);var plan=planner.generate();
        var fields=new float[6][plan.nodeCount()];var biomes=new short[plan.nodeCount()];var water=new byte[plan.nodeCount()];
        for(int n=0;n<plan.nodeCount();n++){for(int f=0;f<6;f++)fields[f][n]=plan.field(f,n);biomes[n]=(short)Math.floorMod((int)Math.floor(plan.position(n).x()*16),ids.size());water[n]=(byte)plan.water(n).ordinal();}
        terrain.initializeTerrain(new dev.starfold.worldgen.plan.terrain.TerrainPlan(42,base.size(),recipe,fields,biomes,water,java.util.List.of(),java.util.List.of()));return terrain;
    }
}
