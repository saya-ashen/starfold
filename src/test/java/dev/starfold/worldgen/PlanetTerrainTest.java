package dev.starfold.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.render.GroundSurfaceIndex;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetSpawn;

import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** Real isolated ProtoChunks and derived contracts; not a Minecraft server/GPU acceptance. */
public class PlanetTerrainTest {
    public static Registry<Biome> biomes;
    public static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);
    final PlanetTerrain terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);

    @BeforeAll
    public static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var r = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var biome =
                new Biome.BiomeBuilder()
                        .hasPrecipitation(false)
                        .temperature(.8f)
                        .downfall(0)
                        .specialEffects(
                                new BiomeSpecialEffects.Builder()
                                        .fogColor(0)
                                        .waterColor(0x3f76e4)
                                        .waterFogColor(0)
                                        .skyColor(0)
                                        .build())
                        .mobSpawnSettings(MobSpawnSettings.EMPTY)
                        .generationSettings(BiomeGenerationSettings.EMPTY)
                        .build();
        Registry.register(r, Biomes.PLAINS, biome);
        biomes = r.freeze();
    }

    PlanetChunkGenerator generator(long seed) {
        var settings =
                new FlatLevelGeneratorSettings(
                        Optional.of(HolderSet.direct()),
                        biomes.getHolderOrThrow(Biomes.PLAINS),
                        List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        return currentGenerator(settings, seed);
    }

    static PlanetChunkGenerator currentGenerator(FlatLevelGeneratorSettings settings, long seed) {
        var biome = settings.getBiome();
        return new PlanetChunkGenerator(settings,seed,dev.starfold.worldgen.PlanetTerrain.VERSION,Optional.of(new PlanetBiomes(seed,java.util.List.of(biome,biome,biome,biome))));
    }

    ProtoChunk chunk(PlanetChunkGenerator g, int x, int z) {
        var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, HEIGHT, biomes, null);
        assertSame(chunk, dev.starfold.worldgen.SystemGeneration.fill(g, chunk).join());
        return chunk;
    }

    int[] values(ChunkAccess c) {
        var states = new int[GroundChunkSnapshot.VALUES];
        var p = c.getPos();
        for (int y = -64; y < 320; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    states[x + 16 * (z + 16 * (y + 64))] =
                            Block.BLOCK_STATE_REGISTRY.getId(
                                    c.getBlockState(
                                            new BlockPos(
                                                    p.getMinBlockX() + x,
                                                    y,
                                                    p.getMinBlockZ() + z)));
        return states;
    }

    @Test void unifiedTerrainUsesOnlyCurrentVersion(){assertTrue(PlanetTerrain.supported(PlanetTerrain.VERSION));assertFalse(PlanetTerrain.supported(4));}
}
