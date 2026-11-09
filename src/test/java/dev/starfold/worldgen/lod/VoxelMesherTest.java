package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.VoxelTestEvidence;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.*;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class VoxelMesherTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static void finish(VoxelMesher.Job job) {
        int calls = 0;
        while (!job.done()) {
            int before = job.loadedColumns();
            long checked = job.checkedCells();
            job.step(Long.MAX_VALUE, 8, 2048, () -> false);
            assertTrue(job.loadedColumns() - before <= 8);
            assertTrue(job.checkedCells() - checked <= 2048);
            assertTrue(++calls < 10000);
        }
    }

    static VoxelSource fixture(
            java.util.function.BiFunction<
                            Integer, Integer, java.util.function.IntFunction<BlockState>>
                    states) {
        return new VoxelSource() {
            public int floor() {
                return -4;
            }

            public int ceiling() {
                return 8;
            }

            public Column column(Face f, int x, int z) {
                var s = states.apply(x, z);
                return new Column() {
                    public int ground() {
                        return 0;
                    }

                    public BlockState state(int y) {
                        return s.apply(y);
                    }
                };
            }
        };
    }

    @Test
    void solidAirFluidAndAllSixOutwardFacesAtNegativeHeight() {
        var source =
                fixture(
                        (x, z) ->
                                y ->
                                        x == 5 && z == 5 && y == -2
                                                ? Blocks.STONE.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        try (var job = new VoxelMesher.Job(source, Face.values()[0], 0, 0, 1, 4096)) {
            finish(job);
            assertEquals(6, job.quads().size());
            for (var q : job.quads()) {
                var p = q.points();
                var n = p.get(1).sub(p.getFirst()).cross(p.get(2).sub(p.getFirst()));
                double dot =
                        switch (q.axis()) {
                            case 0 -> -n.x();
                            case 1 -> n.x();
                            case 2 -> -n.y();
                            case 3 -> n.y();
                            case 4 -> -n.z();
                            default -> n.z();
                        };
                assertTrue(dot > 0);
                assertTrue(p.stream().allMatch(v -> v.y() >= -2 && v.y() <= -1));
            }
        }
        var air = Blocks.AIR.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        var solid = Blocks.STONE.defaultBlockState();
        assertFalse(VoxelMesher.visible(air, solid));
        assertFalse(VoxelMesher.visible(water, water));
        assertFalse(VoxelMesher.visible(water, solid));
        assertTrue(VoxelMesher.visible(solid, water));
        assertTrue(VoxelMesher.visible(water, air));
        var wet = fixture((x, z) -> y -> x == 5 && z == 5 && y == 0 ? water : air);
        try (var job = new VoxelMesher.Job(wet, Face.values()[0], 0, 0, 1, 4096)) {
            finish(job);
            assertEquals(6, job.quads().size());
            assertTrue(job.quads().stream().allMatch(q -> q.material() == water));
        }
        assertEquals(air, VoxelMesher.shown(Blocks.POPPY.defaultBlockState()));
        assertEquals(air, VoxelMesher.shown(Blocks.DEAD_BUSH.defaultBlockState()));
        assertEquals(water, VoxelMesher.shown(Blocks.SEAGRASS.defaultBlockState()));
        assertEquals(solid, VoxelMesher.shown(solid));
        var leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertEquals(leaves, VoxelMesher.shown(leaves));
        var poppy = Blocks.POPPY.defaultBlockState();
        var flowered = fixture((x, z) -> y -> y < 0 ? solid : x == 5 && z == 5 && y == 0 ? poppy : air);
        try (var job = new VoxelMesher.Job(flowered, Face.values()[0], 0, 0, 1, 4096)) {
            finish(job);
            assertTrue(job.quads().stream().noneMatch(q -> q.material() == poppy));
        }
    }

    @Test
    void separatedVolumesAndCaveRemainAirNotHighestColumnFill() {
        var source =
                fixture(
                        (x, z) ->
                                y ->
                                        (y == -2 || y == 4)
                                                ? Blocks.STONE.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        try (var job = new VoxelMesher.Job(source, Face.values()[0], 0, 0, 2, 4096)) {
            finish(job);
            assertEquals(4 * 49 + 4 * 7, job.quads().size());
            assertTrue(
                    job.quads().stream()
                            .allMatch(
                                    q ->
                                            q.points().stream()
                                                    .allMatch(
                                                            p ->
                                                                    p.y() == -2
                                                                            || p.y() == -1
                                                                            || p.y() == 4
                                                                            || p.y() == 5)));
            assertTrue(
                    job.quads().stream()
                            .anyMatch(q -> q.axis() == 2 && q.points().getFirst().y() == 4));
        }
    }

    @Test
    void deadlineBudgetCancellationOverflowAndDeterminismAreProductionPaths() {
        var source =
                fixture(
                        (x, z) ->
                                y ->
                                        y == 0
                                                ? Blocks.STONE.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        var f = Face.values()[0];
        try (var job = new VoxelMesher.Job(source, f, 0, 0, 1, 4096)) {
            job.step(0, 8, 2048, () -> false);
            assertEquals(0, job.loadedColumns());
            job.step(Long.MAX_VALUE, 1, 1, () -> false);
            assertEquals(1, job.loadedColumns());
            job.step(Long.MAX_VALUE, 8, 2048, () -> true);
            assertFalse(job.done());
            assertThrows(IllegalStateException.class, job::quads);
            assertNull(job.column(0, 0));
        }
        try (var a = new VoxelMesher.Job(source, f, 0, 0, 4, 4096);
                var b = new VoxelMesher.Job(source, f, 0, 0, 4, 4096);
                var over = new VoxelMesher.Job(source, f, 0, 0, 1, 0)) {
            finish(a);
            finish(b);
            finish(over);
            assertEquals(a.quads(), b.quads());
            assertTrue(over.overflow());
            assertEquals(List.of(), over.quads());
            assertEquals(36, a.loadedColumns());
        }
    }

    @Test
    void realSourceMatchesWholeColumnsRockOceanDryAndCanonicalEdges() {
        var ecology = new PlanetTerrain(PlanetTerrain.TEST_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION);
        var source = new TerrainVoxelSource(ecology);
        for (int version : new int[] {PlanetTerrain.VERSION}) {
            var t = new PlanetTerrain(PlanetTerrain.TEST_SEED, version);
            var s = new TerrainVoxelSource(t);
            for (var face : Face.values())
                for (int[] p :
                        new int[][] {
                            {0, 0},
                            {-1, 32},
                            {32, -1},
                            {2048, 32},
                            {32, 2048},
                            {256, 256},
                            {512, 512}
                        }) compare(t, s, face, p[0], p[1]);
            assertEquals(t.floor(), s.floor());
            assertEquals(320, s.ceiling());
            if (version == 5) {
                assertNull(t.sea());
                boolean valley = false;
                search:
                for (var face : Face.values())
                    for (int x = 32; x < 2048; x += 64)
                        for (int z = 32; z < 2048; z += 64)
                            if (t.groundY(face, x, z) < 126) {
                                compare(t, s, face, x, z);
                                assertTrue(s.column(face, x, z).state(127).isAir());
                                try (var mesh =
                                        new VoxelMesher.Job(s, face, x & ~15, z & ~15, 4, 4096)) {
                                    finish(mesh);
                                    assertTrue(
                                            mesh.quads().stream()
                                                    .allMatch(
                                                            q ->
                                                                    q.material()
                                                                            .getFluidState()
                                                                            .isEmpty()));
                                }
                                valley = true;
                                break search;
                            }
                assertTrue(valley, "Dry lowlands must not become inferred sea");
            }
        }
        boolean wet = false, dry = false;
        for (int x = 32; x < 2048; x += 64)
            for (int z = 32; z < 2048; z += 64) {
                int y = ecology.groundY(Face.values()[0], x, z);
                if (!wet && y < 127) {
                    compare(ecology, source, Face.values()[0], x, z);
                    assertTrue(source.column(Face.values()[0], x, z).state(127).is(Blocks.WATER));
                    wet = true;
                }
                if (!dry && y >= 130) {
                    compare(ecology, source, Face.values()[0], x, z);
                    dry = true;
                }
            }
        assertTrue(wet && dry);
    }

    static void compare(PlanetTerrain t, VoxelSource s, Face f, int x, int z) {
        var expected = t.column(f, x, z);
        var actual = s.column(f, x, z);
        assertEquals(expected.ground(), actual.ground());
        for (int y = t.floor() - 1; y <= 320; y++) assertSame(expected.state(y), actual.state(y));
    }

    @Test
    void boundedInterestHasThreeActualLodsAndMeasuredProductionWork() throws Exception {
        var t = new PlanetTerrain(PlanetTerrain.TEST_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION);
        var source = new TerrainVoxelSource(t);
        long start = System.nanoTime(), cells = 0;
        int columns = 0, quads = 0, tiles = 0, overflow = 0;
        var levels = new TreeMap<Integer, Integer>();
        for (int z = 448; z < 576; z += 16)
            for (int x = 448; x < 576; x += 16) {
                int step =
                        VoxelLodPolicy.step(
                                16.0 * Math.max(Math.abs(x + 8 - 512), Math.abs(z + 8 - 512)), -1);
                try (var job = new VoxelMesher.Job(source, Face.values()[0], x, z, step, 4096)) {
                    finish(job);
                    tiles++;
                    columns += job.loadedColumns();
                    cells += job.checkedCells();
                    quads += job.quads().size();
                    if (job.overflow()) overflow++;
                    else levels.merge(step, 1, Integer::sum);
                }
            }
        assertEquals(64, tiles);
        assertEquals(Set.of(1, 2), levels.keySet());
        assertTrue(columns < 16000);
        assertTrue(cells < 6_000_000);
        var path = VoxelTestEvidence.directory("mesher-cost").resolve("cpu-cost.json");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                "{\n  \"tiles\":"
                        + tiles
                        + ",\n  \"columns\":"
                        + columns
                        + ",\n  \"checked_cells\":"
                        + cells
                        + ",\n  \"quads\":"
                        + quads
                        + ",\n  \"overflow_tiles\":"
                        + overflow
                        + ",\n  \"elapsed_ns\":"
                        + (System.nanoTime() - start)
                        + ",\n  \"gpu_validated\":false\n}\n");
    }

    @Test
    void commonBytecodeHasNoClientGlOrAlternativeBackendTypes() throws Exception {
        try (var classes =
                Files.list(Path.of("build/classes/java/main/dev/starfold/worldgen/lod"))) {
            var files = classes.filter(p -> p.toString().endsWith(".class")).toList();
            assertTrue(files.size() >= 8);
            for (var file : files) {
                var text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String forbidden :
                        List.of(
                                "net/minecraft/client/",
                                "com/mojang/blaze3d/",
                                "org/lwjgl/",
                                "sodium",
                                "iris/"))
                    assertFalse(text.contains(forbidden), file + " -> " + forbidden);
            }
        }
    }
}
