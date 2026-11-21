package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

class GroundQuadtreeMesherTest {
    @BeforeAll
    static void registry() {
        PlanetTerrainTest.registry();
    }

    private static VoxelColumn ground() {
        return new VoxelColumn(
                List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState())));
    }

    @Test
    void protrudingBambooOutlinesStayInsideTheirReducedChartCells() {
        var column =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(
                                        167,
                                        168,
                                        Blocks.BAMBOO
                                                .defaultBlockState()
                                                .setValue(
                                                        net.minecraft.world.level.block
                                                                .BambooStalkBlock.LEAVES,
                                                        net.minecraft.world.level.block.state
                                                                .properties.BambooLeaves.LARGE))));
        for (int step : new int[] {1, 2, 16, 64}) {
            var mesh =
                    GroundQuadtreeMesher.build(
                            Face.TOP, 0, 0, step <= 16 ? 16 : 256, step, 160, (x, z) -> column);
            assertTrue(mesh.vertexCount() > 0);
            assertTrue(mesh.bounds().minX() >= 0);
            assertTrue(mesh.bounds().minZ() >= 0);
            assertTrue(mesh.bounds().maxX() <= 256);
            assertTrue(mesh.bounds().maxZ() <= 256);
        }
    }

    @Test
    void partialRootNeverEmitsBeyondASmallerPlanetFace() {
        for (int step : new int[] {64, 32, 16}) {
            var built =
                    GroundQuadtreeMesher.build(
                            Face.TOP, 256, 256, 256, step, 118, 272, (x, z) -> ground());
            assertTrue(built.vertexCount() > 0);
            assertEquals(256, built.bounds().minX());
            assertEquals(272, built.bounds().maxX());
            assertEquals(256, built.bounds().minZ());
            assertEquals(272, built.bounds().maxZ());
            var data = built.vertices();
            for (int i = 0; i < data.limit(); i += 12) {
                assertTrue(Short.toUnsignedInt(data.getShort(i)) / 16. <= 272);
                assertTrue(Short.toUnsignedInt(data.getShort(i + 4)) / 16. <= 272);
            }
        }
    }

    @Test
    void workerBuildIsCompactImmutableAndRenderResolutionOwnsAllRegistration() {
        var built =
                CompletableFuture.supplyAsync(
                                () ->
                                        GroundQuadtreeMesher.build(
                                                Face.TOP, 32, 64, 32, 2, 118, (x, z) -> ground()))
                        .join();
        assertEquals(Face.TOP, built.face());
        assertEquals(built.vertexCount() * 12L, built.bytes());
        assertEquals(0, built.vertexCount() % 4);
        var data = built.vertices();
        assertTrue(data.isReadOnly());
        assertEquals(ByteOrder.nativeOrder(), data.order());
        assertThrows(ReadOnlyBufferException.class, () -> data.put(0, (byte) 1));
        data.position(data.limit());
        assertEquals(built.bytes(), built.vertices().remaining());
        assertThrows(UnsupportedOperationException.class, () -> built.materials().clear());
        var thread = Thread.currentThread();
        var calls = new AtomicInteger();
        var resolved =
                GroundQuadtreeMesher.resolve(
                        built,
                        (material, corner, x, y, z) -> {
                            assertSame(thread, Thread.currentThread());
                            assertSame(Blocks.STONE.defaultBlockState(), material.block());
                            assertTrue(x >= 32 && x <= 64 && z >= 64 && z <= 96);
                            assertTrue(y >= -14 && y <= 2);
                            assertEquals(
                                    1,
                                    Math.sqrt(
                                            material.normalX() * material.normalX()
                                                    + material.normalY() * material.normalY()
                                                    + material.normalZ() * material.normalZ()),
                                    1e-9);
                            calls.incrementAndGet();
                            return new GroundQuadtreeMesher.ResolvedVertex(
                                    0xabcdef, corner, 0xf0e0, material.axis());
                        });
        assertEquals(built.vertexCount(), calls.get());
        assertEquals(built.vertexCount(), resolved.vertexCount());
        for (var buffer : resolved.layers().values()) {
            assertTrue(buffer.isDirect(), "arena passes resolved bytes directly to LWJGL");
            assertTrue(buffer.isReadOnly());
            assertEquals(ByteOrder.nativeOrder(), buffer.order());
            for (int i = 0; i < buffer.limit(); i += 12) {
                assertEquals(0xcdef, Short.toUnsignedInt(buffer.getShort(i + 6)));
                assertEquals(0xab, Short.toUnsignedInt(buffer.getShort(i + 8)) >>> 8);
                assertEquals(0xf0e0, Short.toUnsignedInt(buffer.getShort(i + 10)));
            }
            buffer.position(buffer.limit());
        }
        assertEquals(
                resolved.vertexCount(),
                resolved.layers().values().stream()
                        .mapToInt(buffer -> buffer.remaining() / 12)
                        .sum());
        // Staging survives a palette reload, without keeping the previous palette ids/light.
        var again =
                GroundQuadtreeMesher.resolve(
                        built,
                        (m, c, x, y, z) -> new GroundQuadtreeMesher.ResolvedVertex(3, c, 0, 0));
        assertEquals(3, again.layers().get(0).getShort(6));
    }

    @Test
    void renderResolutionHonorsVertexAndCpuBudgetsAndCancellation() {
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 32, 2, 118, (x, z) -> ground());
        var calls = new AtomicInteger();
        GroundQuadtreeMesher.Resolver resolver =
                (m, c, x, y, z) -> {
                    calls.incrementAndGet();
                    return new GroundQuadtreeMesher.ResolvedVertex(17, c, 0xf0, m.axis());
                };
        GroundQuadtreeMesher.Resolved sliced;
        try (var resolving = GroundQuadtreeMesher.beginResolve(built, resolver)) {
            assertEquals(0, calls.get());
            assertFalse(resolving.done());
            assertThrows(IllegalStateException.class, resolving::finish);
            assertThrows(IllegalArgumentException.class, () -> resolving.step(-1, () -> true));
            assertEquals(0, resolving.step(3, () -> true));
            assertEquals(0, resolving.step(4000, () -> false));
            assertEquals(4, resolving.step(5, () -> true));
            assertEquals(4, calls.get());
            var deadlineChecks = new AtomicInteger();
            assertEquals(4, resolving.step(4000, () -> deadlineChecks.getAndIncrement() == 0));
            assertEquals(8, calls.get());
            while (!resolving.done()) assertTrue(resolving.step(12, () -> true) <= 12);
            sliced = resolving.finish();
        }
        assertEquals(built.vertexCount(), calls.get());
        var all = GroundQuadtreeMesher.resolve(built, resolver);
        assertEquals(
                all.layers(), sliced.layers(), "slicing preserves every compact byte and layer");
        var cancelled = GroundQuadtreeMesher.beginResolve(built, resolver);
        cancelled.step(4, () -> true);
        cancelled.close();
        assertThrows(IllegalStateException.class, () -> cancelled.step(4, () -> true));
        assertThrows(IllegalStateException.class, cancelled::finish);
        var failed =
                GroundQuadtreeMesher.beginResolve(
                        built,
                        (m, c, x, y, z) -> {
                            throw new IllegalArgumentException("injected callback failure");
                        });
        assertThrows(IllegalArgumentException.class, () -> failed.step(4, () -> true));
        assertThrows(IllegalStateException.class, failed::finish);
    }

    @Test
    void multipleRunsKeepCaveAndFloatingPlatformWhileOnlyLowestSolidExtends() {
        var column =
                new VoxelColumn(
                        List.of(
                                new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState()),
                                new VoxelColumn.Run(
                                        140, 142, Blocks.OAK_PLANKS.defaultBlockState())));
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 16, 2, 110, (x, z) -> column);
        assertEquals(-22, built.bounds().minY());
        assertEquals(14, built.bounds().maxY());
        var stoneSide =
                built.materials().stream()
                        .filter(m -> m.axis() != 1 && m.block().is(Blocks.STONE))
                        .toList();
        assertFalse(stoneSide.isEmpty());
        assertTrue(stoneSide.stream().allMatch(m -> m.bottomY() == -22 && m.topY() == 2));
        assertTrue(
                built.materials().stream()
                        .filter(m -> m.axis() != 1 && m.block().is(Blocks.OAK_PLANKS))
                        .allMatch(m -> m.bottomY() == 12 && m.topY() == 14));
        assertTrue(built.materials().stream().anyMatch(m -> m.normalY() < 0 && m.bottomY() == 12));
    }

    @Test
    void oceanHaloHasNoInternalWallsAndFluidHeightSurvivesQuantization() {
        var water =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(128, 130, Blocks.WATER.defaultBlockState())));
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 32, 2, 100, (x, z) -> water);
        assertEquals(16, built.vertexCount(), "four grid cells, each a fluid top quad");
        assertTrue(built.materials().stream().allMatch(m -> m.normalY() > 0));
        double expected = 1 + 8. / 9;
        assertEquals(expected, built.bounds().maxY(), 1e-6);
        GroundQuadtreeMesher.resolve(
                built,
                (m, c, x, y, z) -> {
                    assertEquals(expected, y, 1e-6);
                    assertEquals(y - Math.round(y * 16) / 16., m.upperHeightOffset(), 1e-10);
                    return new GroundQuadtreeMesher.ResolvedVertex(0, c, 0, 1);
                });
    }

    @Test
    void fluidBoundaryKeepsPartialSolidOcclusionAndNeverUsesSolidSkirtFloor() {
        var water =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(128, 132, Blocks.WATER.defaultBlockState())));
        var wall =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(128, 130, Blocks.STONE.defaultBlockState())));
        var built =
                GroundQuadtreeMesher.build(
                        Face.TOP, 0, 0, 16, 2, 90, (x, z) -> x < 0 ? wall : water);
        var sides = built.materials().stream().filter(m -> m.normalX() < 0).toList();
        assertFalse(sides.isEmpty());
        assertTrue(sides.stream().allMatch(m -> m.bottomY() == 2 && m.topY() > 3));
        assertEquals(2, built.bounds().minY());
    }

    @Test
    void coarseRootsCanMergeAcrossGridButFineNodesCannot() {
        var fine = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 64, 16, 120, (x, z) -> ground());
        var coarse = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 64, 32, 120, (x, z) -> ground());
        assertEquals(16, topQuads(fine));
        assertEquals(1, topQuads(coarse));
    }

    private static int topQuads(GroundQuadtreeMesher.Built mesh) {
        int count = 0;
        var data = mesh.vertices();
        for (int p = 0; p < data.limit(); p += 48) {
            int id =
                    Short.toUnsignedInt(data.getShort(p + 6))
                            | (Short.toUnsignedInt(data.getShort(p + 8)) >>> 8) << 16;
            if (mesh.materials().get(id).normalY() > 0) count++;
        }
        return count;
    }

    @Test
    void emptyNodesAndInvalidLayerOrCompactInputsAreExplicit() {
        var empty =
                GroundQuadtreeMesher.build(
                        Face.TOP, 0, 0, 16, 1, 100, (x, z) -> new VoxelColumn(List.of()));
        assertEquals(0, empty.vertexCount());
        assertEquals(new GroundQuadtreeMesher.Bounds(0, 0, 0, 0, 0, 0), empty.bounds());
        assertTrue(
                GroundQuadtreeMesher.resolve(empty, (m, c, x, y, z) -> fail("empty"))
                        .layers()
                        .isEmpty());
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 16, 2, 100, (x, z) -> ground());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GroundQuadtreeMesher.resolve(
                                built,
                                (m, c, x, y, z) ->
                                        new GroundQuadtreeMesher.ResolvedVertex(0, 0, 0, c)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundQuadtreeMesher.ResolvedVertex(0x1000000, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundQuadtreeMesher.build(Face.TOP, 1, 0, 16, 2, 100, (x, z) -> ground()));
    }
}
