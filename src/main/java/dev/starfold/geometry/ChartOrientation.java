package dev.starfold.geometry;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft-facing chart transport. Y/up remains local gravity in all six storage plots. */
public final class ChartOrientation {
    private ChartOrientation() {}

    public static Rotation rotation(int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    public static BlockState state(BlockState state, int turns) {
        return Math.floorMod(turns, 4) == 0 ? state : state.rotate(rotation(turns));
    }

    public static Direction direction(Direction direction, int turns) {
        return rotation(turns).rotate(direction);
    }
}
