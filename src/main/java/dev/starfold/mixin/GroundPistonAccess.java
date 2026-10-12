package dev.starfold.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A moving piston block entity that crosses a face seam turns with it. */
@Mixin(PistonMovingBlockEntity.class)
public interface GroundPistonAccess {
    @Accessor("movedState")
    BlockState starfold$movedState();

    @Accessor("movedState")
    void starfold$movedState(BlockState state);

    @Accessor("direction")
    Direction starfold$direction();

    @Accessor("direction")
    void starfold$direction(Direction direction);
}
