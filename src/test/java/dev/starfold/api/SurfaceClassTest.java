package dev.starfold.api;

import dev.starfold.worldgen.surface.SurfaceTestTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceClassTest {
    @Test void blockTagsNotAlbedoDecideMaterial() throws Exception {
        SurfaceTestTags.bind();
        BuiltInRegistries.FLUID.bindTags(java.util.Map.of(FluidTags.WATER,java.util.List.of(
                BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER),BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_WATER))));
        for (Block b : new Block[]{Blocks.STONE,Blocks.SAND,Blocks.GREEN_WOOL,Blocks.MOSSY_COBBLESTONE})
            assertEquals(SurfaceClass.GROUND, classify(b),b.toString());
        assertEquals(SurfaceClass.LEAVES,classify(Blocks.OAK_LEAVES));
        assertEquals(SurfaceClass.LEAVES,classify(Blocks.SPRUCE_LEAVES));
        for (Block b : new Block[]{Blocks.SHORT_GRASS,Blocks.FERN,Blocks.OAK_SAPLING,Blocks.POPPY,Blocks.VINE})
            assertEquals(SurfaceClass.PLANTS,classify(b),b.toString());
        assertEquals(SurfaceClass.WATER,classify(Blocks.WATER));
        for (Block b : new Block[]{Blocks.AIR,Blocks.GLASS,Blocks.TORCH,Blocks.STONE_SLAB})
            assertEquals(SurfaceClass.UNAFFECTED,classify(b),b.toString());
    }
    private int classify(Block block) {
        return SurfaceClass.of(block.defaultBlockState(),EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
    }
}
