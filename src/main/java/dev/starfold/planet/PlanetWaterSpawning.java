package dev.starfold.planet;

import dev.starfold.sky.Space;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.function.IntFunction;

/** Column-local sea height for vanilla aquatic and shoreline spawn predicates. */
public final class PlanetWaterSpawning {
    private PlanetWaterSpawning() {}

    public static int seaLevel(LevelAccessor world, BlockPos pos, int vanilla) {
        // WorldGenRegion implements ServerLevelAccessor too: use its owning server level.
        if (!(world instanceof ServerLevelAccessor server)) return vanilla;
        var level = server.getLevel();
        if (!PlanetLevels.is(level)
                || !(level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system))
            return vanilla;
        return seaLevel(pos, id -> system.planet(id).terrain(), vanilla);
    }

    static int seaLevel(BlockPos pos, IntFunction<PlanetTerrain> terrains, int vanilla) {
        var access = PlanetAccess.SHARED;
        if (Space.holds(pos.getX(), pos.getZ()) || !access.inChart(pos)) return vanilla;
        var terrain = terrains.apply(access.storagePlanet(pos));
        var cell = access.cell(pos);
        var liquid = terrain.liquid(cell.face(), cell.u(), cell.v());
        // A dry beach column still belongs to the authored ocean shoreline.
        if (liquid == null) liquid = terrain.sea();
        // Both APIs already return absolute level Y; no chart-floor offset belongs here.
        return liquid == null ? vanilla : liquid.level();
    }
}
