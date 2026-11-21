package dev.starfold.worldgen.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluid occupancy in the representative columns used by boxes, not vanilla corner interpolation.
 */
public final class VoxelFluid {
    /** A side rectangle in absolute Y and lateral coordinates. */
    public record Side(double bottom, double top, double low, double high) {}

    private VoxelFluid() {}

    public static boolean same(FluidState a, FluidState b) {
        return !a.isEmpty() && !b.isEmpty() && a.getType().isSame(b.getType());
    }

    /** Only a free surface is lowered. State/run boundaries inside one fluid have no air gap. */
    public static double top(VoxelColumn column, VoxelColumn.Run run) {
        var fluid = run.block().getFluidState();
        if (same(fluid, column.state(run.top()).getFluidState())) return run.top();
        // Match FlowingFluid.getOwnHeight in double precision. FALLING does not imply height 1:
        // a falling water block has amount 8; only same-family fluid above fills the whole cell.
        double height =
                fluid.getType() instanceof FlowingFluid
                        ? fluid.getAmount() / 9.0
                        : fluid.getOwnHeight();
        return run.top() - 1 + height;
    }

    public static Direction neighborFace(int axis) {
        return switch (axis) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.WEST;
            case 4 -> Direction.SOUTH;
            case 5 -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Fluid side axis");
        };
    }

    /**
     * Known occlusion on a column edge. Same-family fluid and opaque face shapes cover water; dry
     * plants, glass and a different fluid do not. Shapes are those of the supplied display state,
     * with horizontal coordinates scaled to its representative cell. No world reads.
     */
    public static List<Side> occluders(
            VoxelColumn column,
            FluidState fluid,
            int axis,
            double low,
            double high,
            double bottom,
            double top) {
        var out = new ArrayList<Side>();
        var face = neighborFace(axis);
        for (var run : column.runs()) {
            if (run.top() <= bottom || run.bottom() >= top) continue;
            BlockState state = run.block();
            if (same(fluid, state.getFluidState()))
                out.add(new Side(run.bottom(), top(column, run), low, high));
            if (!state.canOcclude() || VoxelMesher.plant(state)) continue;
            var shape = state.getFaceOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, face);
            for (var box : shape.toAabbs()) {
                double a = axis < 2 ? box.minZ : box.minX;
                double b = axis < 2 ? box.maxZ : box.maxX;
                a = low + (high - low) * a;
                b = low + (high - low) * b;
                // Full-height pieces can cover the whole run without expanding each block.
                if (box.minY == 0 && box.maxY == 1) {
                    out.add(new Side(run.bottom(), run.top(), a, b));
                } else {
                    int start = Math.max(run.bottom(), (int) Math.floor(bottom));
                    int end = Math.min(run.top(), (int) Math.ceil(top));
                    for (int y = start; y < end; y++)
                        out.add(new Side(y + box.minY, y + box.maxY, a, b));
                }
            }
        }
        return List.copyOf(out);
    }
}
