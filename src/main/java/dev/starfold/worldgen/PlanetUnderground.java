package dev.starfold.worldgen;

import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Pure version-3 noise sampling. Sphere coordinates are noise coordinates, not voxel geometry. */
public final class PlanetUnderground {
    private final PlanetTerrain terrain;
    private final PlanetMinerals.Table minerals;

    public PlanetUnderground(PlanetTerrain terrain) {
        this.terrain = terrain;
        minerals = new PlanetMinerals.Table(terrain.generation().minerals());
    }

    public Point sample(Point direction, int y) {
        return direction.scale(terrain.shape().half() + y - 128);
    }

    public boolean cave(Point p, int ground, int y) {
        return cave(PlanetTerrain.lattices(), p, ground, y);
    }

    boolean cave(PlanetTerrain.Lattice[] cells, Point p, int ground, int y) {
        if (y <= terrain.floor() + 4 || y > ground - 6) return false;
        return (Math.abs(terrain.noise(cells, p, 1.0 / 36, 101)) < .115
                        && Math.abs(terrain.noise(cells, p, 1.0 / 36, 102)) < .115)
                || terrain.noise(cells, p, 1.0 / 48, 103) > .58;
    }

    public BlockState state(Point p, int ground, int y) {
        return state(PlanetTerrain.lattices(), p, ground, y);
    }

    BlockState state(PlanetTerrain.Lattice[] cells, Point p, int ground, int y) {
        if (cave(cells, p, ground, y)) return Blocks.AIR.defaultBlockState();
        y = terrain.layer(y);
        boolean deep = y < minerals.deepLine;
        if (minerals.ore.length > 0) {
            for (int i = 0; i < minerals.ore.length; i++)
                if (y <= minerals.top[i]
                        && y >= minerals.bottom[i]
                        && ore(cells, p, 201 + i, minerals.rarity[i]))
                    return deep ? minerals.deepOre[i] : minerals.ore[i];
        } else if (y >= 0 && ore(cells, p, 201, .82))
            return deep
                    ? Blocks.DEEPSLATE_COAL_ORE.defaultBlockState()
                    : Blocks.COAL_ORE.defaultBlockState();
        else if (y <= 112 && ore(cells, p, 202, .84))
            return deep
                    ? Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()
                    : Blocks.IRON_ORE.defaultBlockState();
        else if (y >= 32 && y <= 112 && ore(cells, p, 203, .82))
            return Blocks.COPPER_ORE.defaultBlockState();
        else if (y <= 32 && ore(cells, p, 204, .89))
            return deep
                    ? Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState()
                    : Blocks.GOLD_ORE.defaultBlockState();
        else if (y <= 8 && ore(cells, p, 205, .85))
            return deep
                    ? Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState()
                    : Blocks.REDSTONE_ORE.defaultBlockState();
        else if (y <= 40 && ore(cells, p, 206, .90))
            return deep
                    ? Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState()
                    : Blocks.LAPIS_ORE.defaultBlockState();
        else if (y <= -16 && ore(cells, p, 207, .94))
            return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
        var stratum = minerals.rock(y);
        return stratum != null
                ? stratum
                : deep ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private boolean ore(PlanetTerrain.Lattice[] cells, Point p, long salt, double threshold) {
        return terrain.noise(cells, p, 1.0 / 5, salt) > threshold;
    }
}
