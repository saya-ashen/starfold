package dev.starfold.ship;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGap;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Hull weight fades between the body's full-weight and weightless heights. Players keep their
 * weight at any height; space-region hulls remain weightless. */
public final class HullWeight {
    public static double at(GroundEphemeris.Body body, double height) {
        return 1 - GroundGap.smooth(body.weightFull(), body.weightNone(), height);
    }

    public static double at(Level level, Point feet) {
        if (Space.holds(feet.x(), feet.z())) return 0;
        var access = PlanetAccess.of(level);
        if (!PlanetLevels.is(level)
                || !access.inChart(BlockPos.containing(feet.x(), feet.y(), feet.z()))) return 1;
        var model = GroundClock.get((ServerLevel) level).clock.model;
        int planet = access.storagePlanet(feet.x(), feet.z());
        return at(model.body(planet), access.chart(feet).y());
    }

    private HullWeight() {}
}
