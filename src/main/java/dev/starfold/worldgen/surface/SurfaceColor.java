package dev.starfold.worldgen.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** MapColor converted from sRGB to linear light before mixing. Packed channels are linear UNORM8. */
public final class SurfaceColor {
    private SurfaceColor() {}
    private static final ConcurrentHashMap<BlockState, Integer> COLORS = new ConcurrentHashMap<>();
    public static double linear(int channel) {
        double s = channel / 255.;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }
    public static int srgb(double linear) {
        double c = Math.clamp(linear, 0, 1);
        return (int)Math.round(255 * (c <= .0031308 ? 12.92*c : 1.055*Math.pow(c, 1/2.4)-.055));
    }
    public static int color(BlockState block) {
        return COLORS.computeIfAbsent(block, b -> b.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col);
    }
    static double[] mix(List<SurfaceFunction.Coverage> materials) {
        var result = new double[3];
        for (var m : materials) {
            int rgb = color(m.block());
            for (int i = 0; i < 3; i++) result[i] += linear(rgb >> (16-8*i) & 255)*m.share();
        }
        return result;
    }
    static int pack(double r, double g, double b) {
        return (int)Math.round(Math.clamp(r,0,1)*255)<<16
                | (int)Math.round(Math.clamp(g,0,1)*255)<<8 | (int)Math.round(Math.clamp(b,0,1)*255);
    }
    public static double channel(SurfaceFunction.Cell cell, int i) {
        return cell.linearRgb() < 0 ? mix(cell.materials())[i] : (cell.linearRgb() >> (16-8*i) & 255)/255.;
    }
    public static int display(SurfaceFunction.Cell cell) {
        var crown = mix(cell.canopyMaterials());
        double a = cell.canopyCoverage();
        return srgb(channel(cell,0)*(1-a)+crown[0]*a)<<16
                | srgb(channel(cell,1)*(1-a)+crown[1]*a)<<8
                | srgb(channel(cell,2)*(1-a)+crown[2]*a);
    }
}
