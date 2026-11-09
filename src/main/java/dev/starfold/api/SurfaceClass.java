package dev.starfold.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** CPU material identity shared by terrain meshers and render-only appearance mods. */
public final class SurfaceClass {
    public static final int UNAFFECTED = 0, GROUND = 1, LEAVES = 2, PLANTS = 3, WATER = 4;
    public static final TagKey<Block> SEASONAL_PLANTS = TagKey.create(Registries.BLOCK,
            ResourceLocation.parse("starfold:seasonal_plants"));

    public static int of(BlockState state, BlockGetter view, BlockPos pos) {
        if (state.getFluidState().is(FluidTags.WATER)) return WATER;
        if (state.is(BlockTags.LEAVES)) return LEAVES;
        if (state.is(SEASONAL_PLANTS)) return PLANTS;
        return state.isSolidRender(view, pos) && state.isCollisionShapeFullBlock(view, pos) ? GROUND : UNAFFECTED;
    }

    /** 255 stays ordinary alpha; 250..254 carry classes 0..4 on opaque/cutout sections. */
    public static int alpha(int surfaceClass) { return 250 + surfaceClass; }
    private SurfaceClass() {}
}
