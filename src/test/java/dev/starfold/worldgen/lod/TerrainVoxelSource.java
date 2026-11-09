package dev.starfold.worldgen.lod;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** The saved seed/version/generation's actual columns, including native procedural ecology. */
public final class TerrainVoxelSource implements VoxelSource {
    private final PlanetTerrain terrain;

    public TerrainVoxelSource(PlanetTerrain terrain) {
        this.terrain = Objects.requireNonNull(terrain);
    }

    public int floor() {
        return terrain.floor();
    }

    public int ceiling() {
        return PlanetTerrain.MAX_Y;
    }

    public Column column(Face face, int x, int z) {
        var column = terrain.column(face, x, z);
        return new Column() {
            public BlockState state(int y) {
                return column.state(y);
            }

            public int ground() {
                return column.ground();
            }
        };
    }
}
