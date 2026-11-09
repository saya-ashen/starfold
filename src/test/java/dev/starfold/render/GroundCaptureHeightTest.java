package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumns;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GroundCaptureHeightTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @Test
    void actualChunkHeightmapAndCapturedTopAgreeIncludingEmptyAndDeepColumns() {
        for (int floor : new int[] {-64, -528}) {
            var chunk =
                    new ProtoChunk(
                            new ChunkPos(0, 0),
                            UpgradeData.EMPTY,
                            LevelHeightAccessor.create(floor, 320 - floor),
                            PlanetTerrainTest.biomes,
                            null);
            chunk.setBlockState(
                    new BlockPos(0, floor + 20, 0), Blocks.STONE.defaultBlockState(), false);
            chunk.setBlockState(new BlockPos(1, 127, 0), Blocks.STONE.defaultBlockState(), false);
            chunk.setBlockState(new BlockPos(2, 319, 0), Blocks.STONE.defaultBlockState(), false);
            Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
            int[] heights = new int[256], states = new int[256];
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    heights[x + 16 * z] = y;
                    states[x + 16 * z] =
                            Block.BLOCK_STATE_REGISTRY.getId(
                                    y < floor
                                            ? Blocks.AIR.defaultBlockState()
                                            : chunk.getBlockState(new BlockPos(x, y, z)));
                }
            var capture = new VoxelColumns.Capture(floor, 320);
            while (!capture.done())
                capture.step((x, y, z) -> chunk.getBlockState(new BlockPos(x, y, z)), 8192);
            var surface =
                    new GroundSurface(1, heights, states, capture.result().orElseThrow());
            assertEquals(floor + 20, surface.height(0, 0));
            assertEquals(127, surface.height(1, 0));
            assertEquals(319, surface.height(2, 0));
            assertEquals(floor - 1, surface.height(3, 0));
        }
    }
}
