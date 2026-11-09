package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CapturedVoxelSourceTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static VoxelColumns slab() {
        var capture = new VoxelColumns.Capture(0, 16);
        while (!capture.done())
            capture.step(
                    (x, y, z) ->
                            y == 12
                                    ? Blocks.STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState(),
                    4096);
        return capture.result().orElseThrow();
    }

    @Test
    void fullTileHasBottomAndBoundaryFacesOnAllSixFacesAndLodSteps() {
        var image = slab();
        for (var face : Face.values())
            for (int step : new int[] {1, 2, 4}) {
                var source = new CapturedVoxelSource(image, face, 32, 48);
                try (var mesh = new VoxelMesher.Job(source, face, 32, 48, step, 4096, true, true)) {
                    while (!mesh.done()) {
                        long before = mesh.checkedCells();
                        mesh.step(Long.MAX_VALUE, 7, 31, () -> false);
                        assertTrue(mesh.checkedCells() - before <= 31);
                    }
                    assertFalse(mesh.overflow());
                    var areas = new double[6];
                    for (var quad : mesh.quads()) {
                        var points = quad.points();
                        areas[quad.axis()] +=
                                points.get(1)
                                        .sub(points.get(0))
                                        .cross(points.get(3).sub(points.get(0)))
                                        .length();
                        for (var p : points) {
                            assertTrue(p.x() >= 32 && p.x() <= 48);
                            assertTrue(p.z() >= 48 && p.z() <= 64);
                            assertTrue(p.y() >= 12 && p.y() <= 13, "Never fill air below a bridge");
                        }
                    }
                    assertArrayEquals(new double[] {16, 16, 256, 256, 16, 16}, areas);
                }
            }
    }

    @Test
    void bridgePierAndEdgePostMatchEveryExpectedUnitFaceWithoutDuplicates() {
        var capture = new VoxelColumns.Capture(0, 16);
        while (!capture.done()) capture.step(CapturedVoxelSourceTest::bridge, 127);
        var image = capture.result().orElseThrow();
        int[][] neighbours = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        var expected = new java.util.HashSet<String>();
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) {
                    var state = bridge(x, y, z);
                    if (state.isAir()) continue;
                    for (int axis = 0; axis < 6; axis++) {
                        var d = neighbours[axis];
                        if (bridge(x + d[0], y + d[1], z + d[2]).isAir())
                            expected.add(
                                    unit(
                                            axis,
                                            32 + x + Math.max(0, d[0]),
                                            y + Math.max(0, d[1]),
                                            48 + z + Math.max(0, d[2]),
                                            state));
                    }
                }
        for (var face : Face.values()) {
            var source = new CapturedVoxelSource(image, face, 32, 48);
            try (var mesh = new VoxelMesher.Job(source, face, 32, 48, 1, 4096, true, true)) {
                while (!mesh.done()) mesh.step(Long.MAX_VALUE, 7, 31, () -> false);
                assertFalse(mesh.overflow());
                var actual = new java.util.HashSet<String>();
                for (var q : mesh.quads()) {
                    int minX =
                            (int) q.points().stream().mapToDouble(p -> p.x()).min().orElseThrow();
                    int minY =
                            (int) q.points().stream().mapToDouble(p -> p.y()).min().orElseThrow();
                    int minZ =
                            (int) q.points().stream().mapToDouble(p -> p.z()).min().orElseThrow();
                    int maxX =
                            (int) q.points().stream().mapToDouble(p -> p.x()).max().orElseThrow();
                    int maxY =
                            (int) q.points().stream().mapToDouble(p -> p.y()).max().orElseThrow();
                    int maxZ =
                            (int) q.points().stream().mapToDouble(p -> p.z()).max().orElseThrow();
                    for (int x = minX; x < Math.max(minX + 1, maxX); x++)
                        for (int y = minY; y < Math.max(minY + 1, maxY); y++)
                            for (int z = minZ; z < Math.max(minZ + 1, maxZ); z++)
                                assertTrue(
                                        actual.add(unit(q.axis(), x, y, z, q.material())),
                                        "Duplicate face");
                }
                assertEquals(
                        expected, actual, "Exact planes, materials, undersides and boundary posts");
            }
        }
    }

    private static net.minecraft.world.level.block.state.BlockState bridge(int x, int y, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= 16)
            return Blocks.AIR.defaultBlockState();
        if (y == 12) return Blocks.STONE.defaultBlockState();
        if (y < 12 && (x == 3 && z == 5 || x == 0 && z == 15))
            return Blocks.OAK_PLANKS.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private static String unit(
            int axis, int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {
        return axis + ":" + x + ":" + y + ":" + z + ":" + state;
    }

    @Test
    void unknownNeighboursCannotCullEdgesAndOverflowHasNoPartialOutput() {
        var source = new CapturedVoxelSource(slab(), Face.values()[0], 0, 0);
        assertTrue(source.column(Face.values()[0], -1, 5).state(12).isAir());
        assertTrue(source.column(Face.values()[0], 16, 5).state(12).isAir());
        assertThrows(IllegalArgumentException.class, () -> source.column(Face.values()[1], 0, 0));
        try (var mesh = new VoxelMesher.Job(source, Face.values()[0], 0, 0, 1, 1, true, true)) {
            while (!mesh.done()) mesh.step(Long.MAX_VALUE, 64, 8192, () -> false);
            assertTrue(mesh.overflow());
            assertTrue(mesh.quads().isEmpty());
        }
    }
}
