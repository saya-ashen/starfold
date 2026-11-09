package dev.starfold.render;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubicColor;
import dev.starfold.mixin.SpriteAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Resource-pack texture albedo × vanilla block/biome tint, never MapColor.
 * Atlas images are borrowed read-only; averages are discarded on resource reload. */
public final class GroundAlbedo {
    // Read by LOD workers too; sprites keep identity equality.
    private static final Map<TextureAtlasSprite, int[]> pixels = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<TextureAtlasSprite, Map<Integer, Integer>> colors =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void clear() {
        pixels.clear();
        colors.clear();
    }

    /** Sample the emitting block, not the enclosing tile or the block outside a wall. */
    static BlockPos position(int face, List<Point> points) {
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (var p : points) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        var normal =
                points.get(1).sub(points.getFirst()).cross(points.get(2).sub(points.getFirst()));
        // A wall on an exact tile line samples its emitter, not the neighbouring biome.
        double x = (minX + maxX) * .5 - (minX == maxX ? Math.signum(normal.x()) * .01 : 0);
        double z = (minZ + maxZ) * .5 - (minZ == maxZ ? Math.signum(normal.z()) * .01 : 0);
        return new BlockPos(
                face * 4096 + (int) Math.floor(x),
                (int)Math.floor(points.stream().mapToDouble(Point::y).average().orElseThrow()+127.99),
                (int) Math.floor(z));
    }

    public static int color(BlockState state, BlockPos pos) {
        return color(state, pos, Minecraft.getInstance().level);
    }

    public static int color(
            BlockState state, BlockPos pos, net.minecraft.world.level.BlockAndTintGetter view) {
        return color(state,pos,view,Direction.UP);
    }

    static int color(BlockState state, BlockPos pos, net.minecraft.world.level.BlockAndTintGetter view, Direction face) {
        var mc = Minecraft.getInstance();
        var fluid = state.getFluidState();
        var handler =
                fluid.isEmpty()
                        ? null
                        : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                                .of(fluid.getType());
        if (handler
                == net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                        .DEFAULT) handler = null;
        TextureAtlasSprite sprite;
        int tint = 0xffffff;
        if (handler != null) {
            sprite =
                    mc.getTextureAtlas(
                                    net.minecraft.client.renderer.texture.TextureAtlas
                                            .LOCATION_BLOCKS)
                            .apply(handler.getStillTexture(fluid, view, pos));
            tint = handler.getTintColor(fluid, view, pos) & 0xffffff;
        } else {
            var model = mc.getBlockRenderer().getBlockModel(state);
            var tops = model.getQuads(state, face, RandomSource.create(0));
            if (tops.isEmpty()) tops = model.getQuads(state, null, RandomSource.create(0));
            var quad = tops.isEmpty() ? null : tops.getFirst();
            sprite = quad == null ? model.getParticleIcon() : quad.getSprite();
            if (quad != null && quad.isTinted()) {
                int value = mc.getBlockColors().getColor(state, view, pos, quad.getTintIndex());
                if (value != -1) tint = value & 0xffffff;
            }
        }
        int rgb = tint;
        return colors.computeIfAbsent(sprite, s -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(
                        rgb,
                        t ->
                                average(
                                        pixels.computeIfAbsent(
                                                sprite,
                                                s ->
                                                        ((SpriteAccess) (Object) s.contents())
                                                                .starfold$cubicImage()
                                                                .getPixelsRGBA()),
                                        t));
    }

    /** Preserve the cache's mixed-material variation, calibrated to the actual textured biome colour. */
    static int calibrate(int mixed, int map, int albedo) {
        int out=0;
        for(int shift : new int[]{16,8,0}) {
            double base=CubicColor.linear((map>>shift&255)/255.);
            double value=CubicColor.linear((mixed>>shift&255)/255.)
                    *CubicColor.linear((albedo>>shift&255)/255.)/Math.max(.001,base);
            out=out<<8 | (int)Math.round(255*CubicColor.encoded(Math.clamp(value,0,1)));
        }
        return out;
    }

    /** Ignore cutout holes; average the visible texture, then multiply by the vanilla tint. */
    public static int average(int[] abgr, int tint) {
        double[] sum = new double[3];
        double weight = 0;
        for (int pixel : abgr) {
            if ((pixel >>> 24) < 26) continue;
            weight++;
            for (int j = 0; j < 3; j++)
                sum[j] +=
                        CubicColor.linear((pixel >> (8*j) & 255)/255.0);
        }
        if (weight == 0) return 0xff00ff;
        int out = 0;
        for (int j=0;j<3;j++)
            out = out<<8 | (int)Math.round(CubicColor.encoded(sum[j]/weight)
                    * (tint >> (16-8*j) & 255));
        return out;
    }

    private GroundAlbedo() {}
}
