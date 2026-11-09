package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.IntFunction;

class VoxelVerticalRunsTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static VoxelSource source(
            int floor,
            int ceiling,
            int ground,
            java.util.function.BiFunction<Integer, Integer, IntFunction<BlockState>> blocks) {
        return new VoxelSource() {
            public int floor() {
                return floor;
            }

            public int ceiling() {
                return ceiling;
            }

            public Column column(Face face, int x, int z) {
                return new Column() {
                    public int ground() {
                        return ground;
                    }

                    public BlockState state(int y) {
                        return blocks.apply(x, z).apply(y);
                    }
                };
            }
        };
    }

    static void complete(VoxelMesher.Job job) {
        int calls = 0;
        while (!job.done()) {
            int columns = job.loadedColumns();
            long cells = job.checkedCells();
            job.step(Long.MAX_VALUE, 7, 73, () -> false);
            assertTrue(job.loadedColumns() - columns <= 7);
            assertTrue(job.checkedCells() - cells <= 73);
            assertTrue(job.activeRuns() <= job.quadCount());
            assertTrue(++calls < 10000);
        }
    }

    record UnitFace(BlockState state, int axis, int plane, int u, int v) {}

    static Map<UnitFace, Integer> expand(List<VoxelMesher.Quad> quads) {
        var result = new HashMap<UnitFace, Integer>();
        for (var q : quads) {
            if (!q.volume()) continue;
            assertEquals(4, q.points().size());
            int[] low = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
                    high = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (var p : q.points()) {
                double[] xyz = {p.x(), p.y(), p.z()};
                for (int i = 0; i < 3; i++) {
                    assertEquals(Math.rint(xyz[i]), xyz[i]);
                    low[i] = Math.min(low[i], (int) xyz[i]);
                    high[i] = Math.max(high[i], (int) xyz[i]);
                }
            }
            int normal = q.axis() / 2, a = (normal + 1) % 3, b = (normal + 2) % 3;
            assertEquals(low[normal], high[normal]);
            var n =
                    q.points()
                            .get(1)
                            .sub(q.points().getFirst())
                            .cross(q.points().get(2).sub(q.points().getFirst()));
            double outward =
                    switch (q.axis()) {
                        case 0 -> -n.x();
                        case 1 -> n.x();
                        case 2 -> -n.y();
                        case 3 -> n.y();
                        case 4 -> -n.z();
                        default -> n.z();
                    };
            assertTrue(outward > 0);
            for (int u = low[a]; u < high[a]; u++)
                for (int v = low[b]; v < high[b]; v++)
                    result.merge(
                            new UnitFace(q.material(), q.axis(), low[normal], u, v),
                            1,
                            Integer::sum);
        }
        return result;
    }

    @Test
    void threeLodsKeepExactMaterialOrientedUnitFacesAcrossGapsWaterAndGround() {
        var air = Blocks.AIR.defaultBlockState();
        var s =
                source(
                        -4,
                        12,
                        0,
                        (x, z) ->
                                y -> {
                                    if ((x + z) % 3 == 0 || y == -1 || y == 5) return air;
                                    if (y < 1) return Blocks.STONE.defaultBlockState();
                                    if (y < 5) return Blocks.OAK_LOG.defaultBlockState();
                                    return x % 3 == 0
                                            ? Blocks.WATER.defaultBlockState()
                                            : Blocks.OAK_LEAVES.defaultBlockState();
                                });
        for (int step : new int[] {1, 2, 4}) {
            try (var old = new VoxelMesher.Job(s, Face.values()[0], 0, 0, step, 100000);
                    var merged = new VoxelMesher.Job(s, Face.values()[0], 0, 0, step, 4096, true)) {
                complete(old);
                complete(merged);
                assertFalse(merged.overflow());
                assertEquals(expand(old.quads()), expand(merged.quads()));
                assertTrue(merged.mergedVerticalFaces() > 0);
                assertTrue(merged.quadCount() < old.quadCount());
                assertTrue(
                        merged.quads().stream()
                                .allMatch(
                                        q ->
                                                q.points().stream()
                                                                        .mapToDouble(p -> p.y())
                                                                        .max()
                                                                        .orElseThrow()
                                                                - q.points().stream()
                                                                        .mapToDouble(p -> p.y())
                                                                        .min()
                                                                        .orElseThrow()
                                                        <= 16));
            }
        }
    }

    @Test
    void deepVerticalFacesFitOriginal4096CapWithoutLosingAnyUndergroundSurface() {
        var s =
                source(
                        -64,
                        128,
                        64,
                        (x, z) ->
                                y ->
                                        (x + z) % 2 == 0 && y < 64
                                                ? Blocks.STONE.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        try (var old = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 4096);
                var reference = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 100000);
                var merged = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 4096, true)) {
            complete(old);
            complete(reference);
            complete(merged);
            assertTrue(old.overflow());
            assertFalse(reference.overflow());
            assertFalse(merged.overflow());
            assertEquals(50372, reference.quadCount());
            assertEquals(3332, merged.quadCount());
            assertTrue(merged.quadCount() <= 4096);
            assertEquals(expand(reference.quads()), expand(merged.quads()));
            assertTrue(merged.mergedVerticalFaces() > 10000);
        }
    }

    @Test
    void horizontalRowsKeepExactFacesAndDoNotJoinAcrossPlanes() {
        var s =
                source(
                        0,
                        2,
                        0,
                        (x, z) ->
                                y ->
                                        y == 0
                                                ? Blocks.STONE.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        try (var reference = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 4096);
                var compact = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 64, true)) {
            complete(reference);
            complete(compact);
            assertFalse(compact.overflow());
            assertEquals(392, reference.quadCount());
            assertEquals(28, compact.quadCount());
            assertEquals(364, compact.mergedHorizontalFaces());
            assertEquals(0, compact.mergedVerticalFaces());
            assertEquals(expand(reference.quads()), expand(compact.quads()));
        }
    }

    @Test
    void fullCapacityCanStillExtendExistingRunAndCancelClearsBoundedMap() {
        var s =
                source(
                        0,
                        12,
                        100,
                        (x, z) ->
                                y ->
                                        x == 5 && z == 5 && y < 8
                                                ? Blocks.STONE.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        try (var exact = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 6, true);
                var overflow = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 5, true);
                var cancel = new VoxelMesher.Job(s, Face.values()[0], 0, 0, 1, 4096, true)) {
            complete(exact);
            complete(overflow);
            assertFalse(exact.overflow());
            assertEquals(6, exact.quadCount());
            assertTrue(overflow.overflow());
            assertEquals(0, overflow.activeRuns());
            cancel.step(Long.MAX_VALUE, 1000, 500, () -> false);
            assertTrue(cancel.activeRuns() > 0);
            cancel.step(Long.MAX_VALUE, 7, 73, () -> true);
            assertEquals(0, cancel.activeRuns());
            assertEquals(0, cancel.quadCount());
            assertFalse(cancel.done());
        }
    }

}
