package dev.starfold.render;

import com.mojang.blaze3d.vertex.*;

import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** CPU vertex encoding uses only the captured mesh context and its owned buffers. */
final class GroundMeshVertices {
    static float channel(int color, int shift) {
        return (color >> shift & 255) / 255f * 2 - 1;
    }

    static class Buffers implements AutoCloseable {
        final Map<RenderType, ByteBufferBuilder> memory = new LinkedHashMap<>();
        final Map<RenderType, BufferBuilder> buffers = new LinkedHashMap<>();
        final boolean surface, compact;
        final GroundMeshContext context = GroundMeshContext.capture();
        final GroundFarDiagnostics.Collector diagnostics;
        int surfaceFace;

        Buffers() {
            this(false);
        }

        Buffers(boolean surface) {
            this(surface, false);
        }

        Buffers(boolean surface, boolean compact) {
            this.surface = surface;
            this.compact = compact;
            diagnostics =
                    surface || compact ? GroundFarDiagnostics.capture(context.planet()) : null;
        }

        BufferBuilder get(RenderType layer) {
            return buffers.computeIfAbsent(
                    layer,
                    l -> {
                        var bytes = new ByteBufferBuilder(l.bufferSize());
                        memory.put(l, bytes);
                        return new BufferBuilder(bytes, l.mode(), l.format());
                    });
        }

        @Override
        public void close() {
            if (diagnostics != null) diagnostics.close("ENCODED_CPU_BUFFER_CLOSED", false);
            for (var bytes : memory.values()) bytes.close();
        }
    }

    static void surfacePolygon(
            Buffers builders,
            BlockState state,
            List<BentStripGeometry.Point> points,
            int x,
            int z) {
        surfacePolygon(builders, state, points, x, z, false);
    }

    static void surfacePolygon(
            Buffers builders,
            BlockState state,
            List<BentStripGeometry.Point> points,
            int x,
            int z,
            boolean volume) {
        surfacePolygon(builders, state, points, x, z, volume, 0);
    }

    static void surfacePolygon(
            Buffers builders,
            BlockState state,
            List<BentStripGeometry.Point> points,
            int x,
            int z,
            boolean volume,
            int surfaceTag) {
        surfacePolygon(builders, state, points, x, z, volume, surfaceTag, -1);
    }

    static void surfacePolygon(
            Buffers builders,
            BlockState state,
            List<BentStripGeometry.Point> points,
            int x,
            int z,
            boolean volume,
            int surfaceTag,
            int mixedRgb) {
        var context = builders.context;
        var level = context.tint();
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
        var layer =
                handler == null
                        ? ItemBlockRenderTypes.getChunkRenderType(state)
                        : ItemBlockRenderTypes.getRenderLayer(fluid);
        var model = mc.getBlockRenderer().getBlockModel(state);
        var normal =
                points.get(1).sub(points.getFirst()).cross(points.get(2).sub(points.getFirst()));
        var face = Direction.getNearest((float) normal.x(), (float) normal.y(), (float) normal.z());
        var tops = model.getQuads(state, face, RandomSource.create(0));
        if (tops.isEmpty() && VoxelMesher.plant(state))
            tops = model.getQuads(state, null, RandomSource.create(0));
        var pos = context.storage(GroundAlbedo.position(builders.surfaceFace, points));
        var sprite =
                handler != null
                        ? mc.getTextureAtlas(
                                        net.minecraft.client.renderer.texture.TextureAtlas
                                                .LOCATION_BLOCKS)
                                .apply(handler.getStillTexture(fluid, level, pos))
                        : tops.isEmpty() ? model.getParticleIcon() : tops.getFirst().getSprite();
        int color =
                handler != null
                        ? handler.getTintColor(fluid, level, pos)
                        : !tops.isEmpty() && tops.getFirst().isTinted()
                                ? mc.getBlockColors()
                                        .getColor(state, level, pos, tops.getFirst().getTintIndex())
                                : 0xffffff;
        int albedo = GroundAlbedo.color(state, pos, level, face);
        if (mixedRgb == -3) color = plantTint(color, albedo, builders.surfaceFace, points, context);
        if (mixedRgb >= 0 || mixedRgb == -2) {
            if (mixedRgb >= 0)
                albedo =
                        GroundAlbedo.calibrate(
                                mixedRgb,
                                dev.starfold.worldgen.surface.SurfaceColor.color(state),
                                GroundAlbedo.color(state, pos, level));
            mixedRgb = albedo;
            color = mixedRgb;
        }
        if (GroundFarTexture.patch(surfaceTag)) {
            float shade =
                    VoxelMesher.plant(state)
                            ? 1
                            : switch (face) {
                                case UP -> 1;
                                case DOWN -> .5f;
                                case NORTH, SOUTH -> .8f;
                                case EAST, WEST -> .6f;
                            };
            color = shaded(color, shade);
            albedo = shaded(albedo, shade);
        }
        var out = builders.get(layer);
        double ox = x, oz = z, span = 16;
        boolean averaged = mixedRgb >= 0 || mixedRgb == -2;
        int surfaceClass = dev.starfold.api.SurfaceClass.of(state, level, pos);
        if (points.size() == 4) {
            if (builders.diagnostics != null)
                builders.diagnostics.encoded(
                        builders.surfaceFace,
                        layer,
                        state,
                        points,
                        0,
                        1,
                        2,
                        3,
                        volume,
                        surfaceTag != 0 ? surfaceTag : volume ? GroundShaderSource.VOXEL_TAG : 16,
                        color,
                        albedo,
                        mixedRgb == -4,
                        normal.y() > 0 ? "TOP" : normal.y() < 0 ? "BOTTOM" : "SIDE");
            for (var p : points)
                surfaceVertex(
                        out,
                        sprite,
                        color,
                        albedo,
                        p,
                        ox,
                        oz,
                        span,
                        volume,
                        surfaceTag,
                        averaged,
                        surfaceClass);
        } else
            for (int i = 1; i < points.size() - 1; i++) {
                if (builders.diagnostics != null)
                    builders.diagnostics.encoded(
                            builders.surfaceFace,
                            layer,
                            state,
                            points,
                            0,
                            i,
                            i + 1,
                            i + 1,
                            volume,
                            surfaceTag != 0
                                    ? surfaceTag
                                    : volume ? GroundShaderSource.VOXEL_TAG : 16,
                            color,
                            albedo,
                            mixedRgb == -4,
                            normal.y() > 0 ? "TOP" : normal.y() < 0 ? "BOTTOM" : "SIDE");
                for (int vertex : new int[] {0, i, i + 1, i + 1})
                    surfaceVertex(
                            out,
                            sprite,
                            color,
                            albedo,
                            points.get(vertex),
                            ox,
                            oz,
                            span,
                            volume,
                            surfaceTag,
                            averaged,
                            surfaceClass);
            }
    }

    static int plantTint(
            int tint,
            int base,
            int face,
            List<BentStripGeometry.Point> points,
            GroundMeshContext context) {
        var trace = GroundLodBuildTrace.packing;
        long started = trace == null ? 0 : System.nanoTime();
        try {
        var terrain = context.terrain();
        if (terrain == null || terrain.scatter() == null) return tint;
        if (points.isEmpty()) throw new java.util.NoSuchElementException();
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (var point : points) {
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        int count = 0, hits = 0, red = 0, green = 0, blue = 0;
        for (double z = minZ + .5; z < maxZ; z += 4)
            for (double x = minX + .5; x < maxX; x += 4) {
                count++;
                int c = context.plantColor(face, (int) x, (int) z);
                if (c >= 0) {
                    red += c >> 16 & 255;
                    green += c >> 8 & 255;
                    blue += c & 255;
                    hits++;
                }
            }
        if (hits == 0) return tint;
        // A crossed plant covers about a third of the ground cell in the coarse projection.
        double share = .35 * hits / count;
        int result = 0;
        int[] sum = {red, green, blue};
        for (int j = 0; j < 3; j++) {
            int shift = 16 - 8 * j, b = base >> shift & 255;
            double target = b * (1 - share) + .35 * sum[j] / count;
            result =
                    result << 8
                            | (int)
                                    Math.clamp(
                                            Math.round(
                                                    (tint >> shift & 255)
                                                            * target
                                                            / Math.max(1, b)),
                                            0,
                                            255);
        }
        return result;
        } finally {
            if (trace != null) {
                trace.plantNanos += System.nanoTime() - started;
                trace.plantCalls++;
            }
        }
    }

    private static int shaded(int rgb, float shade) {
        return (int) ((rgb >> 16 & 255) * shade) << 16
                | (int) ((rgb >> 8 & 255) * shade) << 8
                | (int) ((rgb & 255) * shade);
    }

    private static void surfaceVertex(
            BufferBuilder out,
            TextureAtlasSprite sprite,
            int color,
            int albedo,
            BentStripGeometry.Point p,
            double x,
            double z,
            double span,
            boolean volume,
            int surfaceTag,
            boolean averaged,
            int surfaceClass) {
        boolean repeat = GroundFarTexture.patch(surfaceTag);
        out.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                .setColor(
                        color >> 16 & 255,
                        color >> 8 & 255,
                        color & 255,
                        surfaceTag != 0 ? surfaceTag : volume ? GroundShaderSource.VOXEL_TAG : 16)
                .setUv(
                        (averaged ? -1 : sprite.getU0())
                                + (repeat
                                        ? 0
                                        : (sprite.getU1() - sprite.getU0())
                                                * (float) ((p.x() - x) / span)),
                        sprite.getV0()
                                + 2 * surfaceClass
                                + (repeat
                                        ? 0
                                        : (sprite.getV1() - sprite.getV0())
                                                * (float) ((p.z() - z) / span)))
                .setUv2(
                        repeat ? GroundFarTexture.upper(sprite.getU1()) : 240,
                        repeat ? GroundFarTexture.upper(sprite.getV1()) : 240)
                .setNormal(channel(albedo, 16), channel(albedo, 8), channel(albedo, 0));
    }

    static void top(
            Buffers buffers,
            BlockState state,
            float x,
            float y,
            float z,
            float size,
            boolean unknown) {
        top(buffers, state, x, y, z, size, size, unknown);
    }

    static void top(
            Buffers buffers,
            BlockState state,
            float x,
            float y,
            float z,
            float width,
            float depth,
            boolean unknown) {
        topHeights(buffers, state, x, z, width, depth, new float[] {y, y, y, y}, unknown);
    }

    private static void topHeights(
            Buffers buffers,
            BlockState state,
            float x,
            float z,
            float width,
            float depth,
            float[] heights,
            boolean unknown) {
        float y = heights[0];
        var level = buffers.context.tint();
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
        var layer =
                unknown
                        ? RenderType.solid()
                        : handler == null
                                ? ItemBlockRenderTypes.getChunkRenderType(state)
                                : ItemBlockRenderTypes.getRenderLayer(fluid);
        var model = mc.getBlockRenderer().getBlockModel(state);
        var tops = model.getQuads(state, Direction.UP, RandomSource.create(0));
        var pos =
                buffers.context.storage(
                        new BlockPos(buffers.surfaceFace * 4096 + (int) x, (int) y + 128, (int) z));
        TextureAtlasSprite sprite =
                handler != null
                        ? mc.getTextureAtlas(
                                        net.minecraft.client.renderer.texture.TextureAtlas
                                                .LOCATION_BLOCKS)
                                .apply(handler.getStillTexture(fluid, level, pos))
                        : tops.isEmpty() ? model.getParticleIcon() : tops.getFirst().getSprite();
        var out = buffers.get(buffers.surface ? RenderType.solid() : layer);
        int albedo =
                buffers.surface ? (unknown ? 0xff3333 : GroundAlbedo.color(state, pos, level)) : 0;
        int color =
                unknown
                        ? 0xff3333
                        : handler != null
                                ? handler.getTintColor(fluid, level, pos)
                                : !tops.isEmpty() && tops.getFirst().isTinted()
                                        ? mc.getBlockColors()
                                                .getColor(
                                                        state,
                                                        level,
                                                        pos,
                                                        tops.getFirst().getTintIndex())
                                        : 0xffffff;
        float[][] p = {
            {x, heights[0], z},
            {x, heights[1], z + depth},
            {x + width, heights[2], z + depth},
            {x + width, heights[3], z}
        };
        float[] us = {sprite.getU0(), sprite.getU0(), sprite.getU1(), sprite.getU1()},
                vs = {sprite.getV0(), sprite.getV1(), sprite.getV1(), sprite.getV0()};
        if (buffers.diagnostics != null)
            buffers.diagnostics.encoded(
                    buffers.surfaceFace,
                    buffers.surface ? RenderType.solid() : layer,
                    state,
                    List.of(
                            new BentStripGeometry.Point(p[0][0], p[0][1], p[0][2]),
                            new BentStripGeometry.Point(p[1][0], p[1][1], p[1][2]),
                            new BentStripGeometry.Point(p[2][0], p[2][1], p[2][2]),
                            new BentStripGeometry.Point(p[3][0], p[3][1], p[3][2])),
                    0,
                    1,
                    2,
                    3,
                    false,
                    buffers.surface ? Math.max(1, (int) width) : 255,
                    color,
                    albedo,
                    false,
                    "TOP");
        for (int i = 0; i < 4; i++)
            out.addVertex(p[i][0], p[i][1], p[i][2])
                    .setColor(
                            color >> 16 & 255,
                            color >> 8 & 255,
                            color & 255,
                            buffers.surface ? Math.max(1, (int) width) : 255)
                    .setUv(us[i], vs[i])
                    .setUv2(240, 240)
                    .setNormal(
                            buffers.surface ? channel(albedo, 16) : 0,
                            buffers.surface ? channel(albedo, 8) : 1,
                            buffers.surface ? channel(albedo, 0) : 0);
    }

    private GroundMeshVertices() {}
}
