package dev.starfold.worldgen.lod;

import dev.starfold.geometry.CubeSurfaceTopology.Face;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** One complete captured tile. Unknown neighbours never occlude its boundary geometry. */
public final class CapturedVoxelSource implements VoxelSource {
    private final VoxelColumns image;
    private final Face face;
    private final int x, z, step;
    private final java.util.function.BiFunction<Integer, Integer, Column> halo;

    public CapturedVoxelSource(VoxelColumns image, Face face, int x, int z) {
        this(image, face, x, z, (u, v) -> null);
    }

    public CapturedVoxelSource(
            VoxelColumns image,
            Face face,
            int x,
            int z,
            java.util.function.BiFunction<Integer, Integer, Column> halo) {
        this(image, face, x, z, 1, halo);
    }

    /** Coarse cells conservatively retain occupied columns, including thin player structures. */
    public CapturedVoxelSource(
            VoxelColumns image,
            Face face,
            int x,
            int z,
            int step,
            java.util.function.BiFunction<Integer, Integer, Column> halo) {
        if (step != 1 && step != 2 && step != 4 && step != 8)
            throw new IllegalArgumentException("Captured LOD");
        this.step = step;
        this.halo = Objects.requireNonNull(halo);
        if ((x & 15) != 0 || (z & 15) != 0) throw new IllegalArgumentException("Voxel tile origin");
        this.image = Objects.requireNonNull(image);
        this.face = Objects.requireNonNull(face);
        this.x = x;
        this.z = z;
    }

    public int floor() {
        return image.floor();
    }

    public int ceiling() {
        return image.ceiling();
    }

    public Column column(Face requestedFace, int u, int v) {
        if (requestedFace != face) throw new IllegalArgumentException("Voxel tile face");
        int a = u - x, b = v - z;
        boolean inside = a >= 0 && a < 16 && b >= 0 && b < 16;
        if (!inside) {
            var neighbour = halo.apply(u, v);
            if (neighbour != null) return neighbour;
        }
        return new Column() {
            public BlockState state(int y) {
                if (!inside) return Blocks.AIR.defaultBlockState();
                int lowX = a / step * step, lowZ = b / step * step;
                var fluid = Blocks.AIR.defaultBlockState();
                for (int dz = 0; dz < step; dz++)
                    for (int dx = 0; dx < step; dx++) {
                        var state = image.state(lowX + dx, y, lowZ + dz);
                        if (state.isAir()) continue;
                        if (state.getFluidState().isEmpty()) return state;
                        fluid = state;
                    }
                return fluid;
            }

            public int ground() {
                return floor() - 1;
            }
        };
    }
}
