package dev.starfold.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A path carried across a face seam keeps its speed and recomputation target. */
@Mixin(PathNavigation.class)
public interface NativeGroundNavigationAccess {
    @Accessor("speedModifier") double starfold$speed();
    @Accessor("targetPos") BlockPos starfold$target();
    @Accessor("targetPos") void starfold$target(BlockPos pos);
}
