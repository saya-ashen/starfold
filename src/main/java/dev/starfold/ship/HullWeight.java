package dev.starfold.ship;

import dev.starfold.chart.GroundGap;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundTransit;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.space.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** How much a Sable body weighs at a height over a planet: all of it up to where the far view takes over, none from
 *  where a vessel is reckoned to be in space, and evenly less between. Players keep their weight at any height: off a
 *  ship they fall to the planet, as they did before there were ships. */
public final class HullWeight {
    public static final double FULL = GroundOrbit.END, NONE = GroundTransit.ENTER;

    public static double at(double height) {
        return 1 - GroundGap.smooth(FULL, NONE, height);
    }

    public static double at(Level level, Point feet) {
        if (Space.holds(feet.x(), feet.z())) return 0;
        var access = PlanetAccess.of(level);
        if (!GroundServer.is(level)
                || !access.inChart(BlockPos.containing(feet.x(), feet.y(), feet.z()))) return 1;
        return at(access.chart(feet).y());
    }

    private HullWeight() {}
}
