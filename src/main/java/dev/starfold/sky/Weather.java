package dev.starfold.sky;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.ground.GroundCoordinates;
import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.space.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Weather is still shared by the Level, but exists only below the top of the chart air. */
public final class Weather {
    static boolean aboveAir(double y) {
        return y - GroundCoordinates.FLOOR >= GroundOrbit.END;
    }

    public static boolean absent(Level level, BlockPos pos) {
        return GroundServer.is(level)
                && (Space.holds(pos.getX(), pos.getZ())
                        || aboveAir(pos.getY()) && PlanetAccess.of(level).inChart(pos));
    }

    private Weather() {}
}
