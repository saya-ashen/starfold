package dev.starfold.worldgen;
import dev.starfold.geometry.BentStripGeometry.Point;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
/** Recipe-driven 3D caves, ores and translated strata. */
public final class PlanetUnderground {
    private final PlanetTerrain terrain;
    private PlanetMinerals.Table table;
    public PlanetUnderground(PlanetTerrain terrain) {
        this.terrain = terrain;
    }
    private PlanetMinerals.Table table() {
        if (table == null)
            table = new PlanetMinerals.Table(terrain.terrainPlan().recipe().minerals());
        return table;
    }
    public Point sample(Point direction, int y) {
        return direction.scale(terrain.shape().half() + y - 128);
    }
    public boolean cave(Point p, int ground, int y) {
        return cave(PlanetTerrain.lattices(), p, ground, y);
    }
    boolean cave(PlanetTerrain.Lattice[] cells, Point p, int ground, int y) {
        var recipe = terrain.terrainPlan().recipe();
        double width = recipe.caveWidth(), scale = recipe.caveScale();
        return y > terrain.floor() + 4 && y < ground - 5
            && Math.abs(terrain.noise(cells, p, 1 / scale, 101)) < width
            && Math.abs(terrain.noise(cells, p, 1 / scale, 102)) < width;
    }
    public BlockState stratum(int layer) {
        var value = table().rock(layer);
        return value == null ? Blocks.STONE.defaultBlockState() : value;
    }
    public BlockState state(Point p, int ground, int y) {
        return state(PlanetTerrain.lattices(), p, ground, y, 0, false);
    }
    BlockState state(PlanetTerrain.Lattice[] cells, Point p, int ground, int y, int shift, boolean plain) {
        if (!plain && cave(cells, p, ground, y))
            return Blocks.AIR.defaultBlockState();
        int layer = terrain.layer(y - shift);
        var m = table();
        if (!plain)
            for (int i = 0; i < m.ore.length; i++)
                if (layer <= m.top[i] && layer >= m.bottom[i]
                    && terrain.noise(cells, p, 1.0 / 5, 201 + i) > m.rarity[i])
                    return layer < m.deepLine ? m.deepOre[i] : m.ore[i];
        return stratum(layer);
    }
}
