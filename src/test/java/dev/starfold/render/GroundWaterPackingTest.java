package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.*;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

class GroundWaterPackingTest {
    @BeforeAll
    static void registry() {
        PlanetTerrainTest.registry();
    }

    @Test
    void sourceFlowFallingAndAquaticWaterKeepExactTopAndSideEndpointsAtEveryStep() {
        var states = new java.util.ArrayList<BlockState>();
        for (int amount = 1; amount <= 8; amount++) {
            states.add(Fluids.FLOWING_WATER.getFlowing(amount, false).createLegacyBlock());
            states.add(Fluids.FLOWING_WATER.getFlowing(amount, true).createLegacyBlock());
        }
        states.add(Blocks.WATER.defaultBlockState());
        states.add(Blocks.SEAGRASS.defaultBlockState());
        states.add(Blocks.KELP.defaultBlockState());
        for (int step : new int[] {1, 2, 16}) {
            for (var state : states) {
                var column = new VoxelColumn(List.of(new VoxelColumn.Run(124, 128, state)));
                var empty = new VoxelColumn(List.of());
                var quads =
                        GroundFarMesh.build(
                                PlanetShape.STANDARD,
                                Face.TOP,
                                1216,
                                1088,
                                16,
                                step,
                                (f, x, z, s) ->
                                        GroundFarMesh.Surface.voxel(
                                                x >= 1216 && x < 1232 && z >= 1088 && z < 1104
                                                        ? column
                                                        : empty));
                double expected = 127 + state.getFluidState().getAmount() / 9.0 - .001 - 128;
                boolean top = false, side = false;
                for (var quad : quads) {
                    if (!(quad.material().getBlock()
                            instanceof net.minecraft.world.level.block.LiquidBlock)) continue;
                    try (var source = new ByteBufferBuilder(128);
                            var target = new ByteBufferBuilder(48)) {
                        long start = source.reserve(128);
                        for (int i = 0; i < 4; i++) {
                            var point = quad.points().get(i);
                            vertex(
                                    start + i * 32L,
                                    (float) point.x(),
                                    (float) point.y(),
                                    (float) point.z(),
                                    8.25f);
                        }
                        var raw = mesh(source, 4);
                        try (var packed =
                                pack(
                                        raw,
                                        target,
                                        RenderType.translucent(),
                                        1216 / 16 + 1088 / 16 * 128L)) {
                            assertEquals(48, packed.vertexBuffer().remaining());
                            for (int i = 0; i < 4; i++) {
                                double rawY = quad.points().get(i).y();
                                double actual = decode(packed, i);
                                if (rawY > -1) {
                                    assertEquals(expected, actual, 1e-7, state + " step " + step);
                                    if (quad.axis() == 1) top = true;
                                    else side = true;
                                } else assertEquals(rawY, actual, 1e-7, "integer side bottom");
                            }
                        }
                    }
                }
                assertTrue(top && side, "exercise both top and side: " + state + " step " + step);
            }
        }
    }

    @Test
    void solidOtherTransparentAndIntegerWaterVerticesRetainTheirExistingHeights() {
        for (var layer : new RenderType[] {RenderType.solid(), RenderType.translucent()}) {
            for (float v : new float[] {.25f, 2.25f, 8.25f}) {
                for (float y : new float[] {-4, -.11111111f, -.5f}) {
                    try (var source = new ByteBufferBuilder(128);
                            var target = new ByteBufferBuilder(48)) {
                        long start = source.reserve(128);
                        for (int i = 0; i < 4; i++) vertex(start + i * 32L, i % 2, y, i / 2, v);
                        try (var packed = pack(mesh(source, 4), target, layer, -1)) {
                            double quantized = Math.round((y + 2048.0) * 16) / 16.0 - 2048;
                            double expected =
                                    layer == RenderType.translucent() && v > 8
                                            ? y == -.11111111f ? -1 + 8 / 9.0 - .001 : y
                                            : quantized;
                            assertEquals(expected, decode(packed, 0), 1e-7);
                            assertEquals(12, packed.drawState().format().getVertexSize());
                        }
                    }
                }
            }
        }
    }

    private static MeshData pack(
            MeshData raw, ByteBufferBuilder target, RenderType layer, long key) {
        var packing = new GroundCompact.Packing(raw, target, layer, 0, key, pos -> 0xf0f0);
        assertTrue(packing.step(new GroundUploadBudget(Long.MAX_VALUE)));
        return packing.finish();
    }

    private static double decode(MeshData packed, int vertex) {
        var data = packed.vertexBuffer();
        int p = vertex * 12;
        int material =
                Short.toUnsignedInt(data.getShort(p + 6))
                        | (Short.toUnsignedInt(data.getShort(p + 8)) >>> 8) << 16;
        // Same position and palette term consumed by GroundCompact.unpack's GLSL.
        return Short.toUnsignedInt(data.getShort(p + 2)) / 16.0
                - 2048
                + GroundCompact.heightOffset(material);
    }

    private static void vertex(long p, float x, float y, float z, float v) {
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutInt(p + 12, 0xfa563412);
        MemoryUtil.memPutFloat(p + 16, .25f);
        MemoryUtil.memPutFloat(p + 20, v);
        MemoryUtil.memPutInt(p + 24, 0xffff0000);
        MemoryUtil.memPutInt(p + 28, 0x007f00);
    }

    private static MeshData mesh(ByteBufferBuilder source, int count) {
        return new MeshData(
                source.build(),
                new MeshData.DrawState(
                        DefaultVertexFormat.BLOCK,
                        count,
                        count / 4 * 6,
                        VertexFormat.Mode.QUADS,
                        VertexFormat.IndexType.SHORT));
    }
}
