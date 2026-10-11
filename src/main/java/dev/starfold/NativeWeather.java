package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Weather is still shared by the Level, but exists only below the top of the chart air. */
public final class NativeWeather {
    static boolean aboveAir(double y){return y-NativeGroundCoordinates.FLOOR>=GroundOrbit.END;}
    public static boolean absent(Level level,BlockPos pos){return NativeGroundServer.is(level)&&(NativeSpace.holds(pos.getX(),pos.getZ())||aboveAir(pos.getY())&&NativePlanetAccess.of(level).inChart(pos));}
    private NativeWeather(){}
}
