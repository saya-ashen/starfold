package dev.starfold.worldgen.lod;

import dev.starfold.geometry.CubeSurfaceTopology.Face;

import net.minecraft.world.level.block.state.BlockState;

/** Read-only generated or snapshot columns. Availability is decided by the caller, never by air. */
public interface VoxelSource {
    interface Column {
        BlockState state(int y);

        int ground();
    }

    int floor();

    int ceiling();

    Column column(Face face, int x, int z);
}
