package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.surface.SurfaceFunction;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.*;

import java.util.*;

class VoxelMaterialShapeTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @Test
    void generatedCapsNeverReplaceTheirUnderlyingTerrain() {
        var terrain = new PlanetTerrain(42);
        var function = new SurfaceFunction(terrain);
        for (int x : new int[] {849, 878, 907}) {
            var cap = function.visible(Face.TOP, x, 356);
            var actual = function.blocks(Face.TOP, x, 356);
            var lod = VoxelColumn.visible(function, Face.TOP, x, 356);
            assertTrue(cap.block().is(Blocks.SNOW));
            for (int y = cap.top() - 4; y <= cap.top(); y++)
                assertSame(actual[y - terrain.floor()], lod.state(y), "x=" + x + " y=" + y);
            assertFalse(lod.state(0).is(Blocks.SNOW));
        }
    }

    @Test
    void recursiveReductionPreservesMaterialBoundariesAndPartialShapes() {
        for (var cap :
                List.of(
                        Blocks.SNOW.defaultBlockState(),
                        Blocks.SAND.defaultBlockState(),
                        Blocks.ICE.defaultBlockState(),
                        Blocks.OAK_SLAB.defaultBlockState(),
                        Blocks.DIRT_PATH.defaultBlockState())) {
            var column =
                    new VoxelColumn(
                            List.of(
                                    new VoxelColumn.Run(-64, 129, Blocks.STONE.defaultBlockState()),
                                    new VoxelColumn.Run(129, 130, cap)));
            var expected = column;
            for (int step = 2; step <= 64; step *= 2) {
                assertEquals(expected, VoxelColumn.reduce(List.of(column), step));
                column = VoxelColumn.reduce(List.of(column, column, column, column), step);
                assertEquals(expected, column, "step=" + step + " cap=" + cap);
                assertSame(Blocks.STONE.defaultBlockState(), column.state(128));
            }
        }
    }

    @Test
    void thinAndPartialBlocksKeepTheirOutlineAcrossLods() {
        for (int step : new int[] {1, 2, 4, 16, 64}) {
            for (int layers : new int[] {1, 2, 7, 8}) {
                var snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
                assertSame(snow, VoxelMesher.shown(snow));
                assertTopAndBottom(snow, step, 10, 10 + layers / 8.0);
            }
            assertTopAndBottom(Blocks.OAK_SLAB.defaultBlockState(), step, 10, 10.5);
            assertTopAndBottom(
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                    step,
                    10.5,
                    11);
            assertTopAndBottom(Blocks.DIRT_PATH.defaultBlockState(), step, 10, 10 + 15 / 16.0);
        }
    }

    @Test
    void capturedTileUsesTheSamePartialBlockShapes() {
        checkCapturedShape(Blocks.OAK_SLAB.defaultBlockState(), false);
        checkCapturedShape(
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true), true);
    }

    private static void checkCapturedShape(BlockState slab, boolean waterlogged) {
        var air = Blocks.AIR.defaultBlockState();
        VoxelSource source =
                new VoxelSource() {
                    public int floor() {
                        return 0;
                    }

                    public int ceiling() {
                        return 3;
                    }

                    public Column column(Face face, int x, int z) {
                        return new Column() {
                            public BlockState state(int y) {
                                return x == 0 && z == 0 && y == 1 ? slab : air;
                            }

                            public int ground() {
                                return 0;
                            }
                        };
                    }
                };
        try (var job = new VoxelMesher.Job(source, Face.TOP, 0, 0, 1, 1000, true, true)) {
            job.step(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, () -> false);
            assertTrue(job.done());
            assertFalse(job.overflow());
            assertTrue(job.quadCount() >= 6);
            boolean foundSolid = false, foundFluid = false;
            for (int i = 0; i < job.quadCount(); i++) {
                var quad = job.quad(i);
                if (quad.material() == slab) {
                    foundSolid = true;
                    assertTrue(quad.points().stream().allMatch(p -> p.y() >= 1 && p.y() <= 1.5));
                } else if (quad.material().is(Blocks.WATER)) foundFluid = true;
            }
            assertTrue(foundSolid);
            assertEquals(waterlogged, foundFluid);
        }
    }

    private static void assertTopAndBottom(BlockState block, int step, double bottom, double top) {
        var column = new VoxelColumn(List.of(new VoxelColumn.Run(10, 11, block)));
        var empty = new VoxelColumn(List.of());
        var mesh =
                VoxelMesher.boxes(
                        0, 0, step, step, step, (x, z) -> x == 0 && z == 0 ? column : empty);
        assertFalse(mesh.isEmpty());
        assertEquals(
                bottom,
                mesh.stream()
                        .flatMap(q -> q.points().stream())
                        .mapToDouble(p -> p.y())
                        .min()
                        .orElseThrow(),
                1e-9);
        assertEquals(
                top,
                mesh.stream()
                        .flatMap(q -> q.points().stream())
                        .mapToDouble(p -> p.y())
                        .max()
                        .orElseThrow(),
                1e-9);
    }

    @Test
    void partialNeighborsHideOnlyTheirOccupiedFaces() {
        var stone = Blocks.STONE.defaultBlockState();
        var slab = Blocks.OAK_SLAB.defaultBlockState();
        var full = new VoxelColumn(List.of(new VoxelColumn.Run(0, 1, stone)));
        var half = new VoxelColumn(List.of(new VoxelColumn.Run(0, 1, slab)));
        var empty = new VoxelColumn(List.of());
        var mesh =
                VoxelMesher.boxes(
                        0,
                        0,
                        2,
                        1,
                        1,
                        (x, z) -> z != 0 ? empty : x == 0 ? full : x == 1 ? half : empty);
        var exposed = mesh.stream().filter(q -> q.material() == stone && q.axis() == 1).toList();
        assertEquals(1, exposed.size());
        assertEquals(
                .5,
                exposed.getFirst().points().stream().mapToDouble(p -> p.y()).min().orElseThrow());
        assertEquals(
                1,
                exposed.getFirst().points().stream().mapToDouble(p -> p.y()).max().orElseThrow());
        var snow = Blocks.SNOW.defaultBlockState();
        var covered =
                new VoxelColumn(
                        List.of(new VoxelColumn.Run(0, 1, stone), new VoxelColumn.Run(1, 2, snow)));
        mesh = VoxelMesher.boxes(0, 0, 1, 1, 1, (x, z) -> x == 0 && z == 0 ? covered : empty);
        assertFalse(mesh.stream().anyMatch(q -> q.material() == stone && q.axis() == 3));
        assertTrue(
                mesh.stream()
                        .anyMatch(
                                q ->
                                        q.material() == snow
                                                && q.axis() == 3
                                                && q.points().getFirst().y() == 1.125));
    }
}
