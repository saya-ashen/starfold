package dev.starfold.planet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** These vetoes help callers that ask first; the live owner chunk also refuses replacements. */
public class PlanetCoreBlock extends Block {
    public static final net.minecraft.tags.TagKey<Block> IMMUTABLE =
            net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.BLOCK,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            "starfold", "immutable"));

    public PlanetCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canEntityDestroy(
            BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return false;
    }

    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {}
}
