package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.surface.SurfaceFunction;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.*;

import java.util.*;

class VoxelColumnBlocksTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @Test
    void orderedColumnsMatchTheOriginalOverlapCompositorExactly() {
        var palette =
                new BlockState[] {
                    Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                    Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                    Blocks.OAK_LOG.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(),
                    Blocks.COAL_ORE.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState()
                };
        var random = new Random(20261112);
        for (int trial = 0; trial < 120; trial++) {
            var states = new BlockState[384];
            for (int y = 0; y < states.length; ) {
                var state = palette[random.nextInt(palette.length)];
                int end = Math.min(states.length, y + 1 + random.nextInt(24));
                Arrays.fill(states, y, end, state);
                y = end;
            }
            assertArrayEquals(
                    reference(states, -64).packed(),
                    VoxelColumn.visibleBlocks(states, -64).packed());
        }
    }

    @Test
    void airGapsAndRunCapMatchForAlternatingStructureBlocks() {
        var states = new BlockState[384];
        for (int y = 0; y < states.length; y++)
            states[y] =
                    switch (y % 3) {
                        case 0 -> Blocks.AIR.defaultBlockState();
                        case 1 -> Blocks.STONE.defaultBlockState();
                        default -> Blocks.OAK_LOG.defaultBlockState();
                    };
        var actual = VoxelColumn.visibleBlocks(states, -64);
        assertEquals(VoxelColumn.MAX_RUNS, actual.runs().size());
        assertEquals(reference(states, -64), actual);
        assertTrue(VoxelColumn.visibleBlocks(new BlockState[0], -64).runs().isEmpty());
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        assertTrue(VoxelColumn.visibleBlocks(states, -64).runs().isEmpty());
        Arrays.fill(states, Blocks.STONE.defaultBlockState());
        assertEquals(
                List.of(new VoxelColumn.Run(-64, 320, Blocks.STONE.defaultBlockState())),
                VoxelColumn.visibleBlocks(states, -64).runs());
    }

    @Test
    void generatedColumnsMatchTheFullVisibilityPathAcrossFacesAndSeams() {
        var terrain = new PlanetTerrain(42);
        var function = new SurfaceFunction(terrain);
        int size = terrain.shape().size();
        var random = new Random(20261118);
        for (var face : Face.VALUES) {
            for (int i = 0; i < 48; i++) {
                int x = random.nextInt(size), z = random.nextInt(size);
                if (i < 4) x = i % 2 == 0 ? -1 : size;
                if (i >= 4 && i < 8) z = i % 2 == 0 ? -1 : size;
                assertArrayEquals(
                        referenceVisible(function, face, x, z).packed(),
                        VoxelColumn.visible(function, face, x, z).packed(),
                        face + " " + x + ", " + z);
            }
        }
    }

    private static VoxelColumn referenceVisible(SurfaceFunction function, Face face, int x, int z) {
        var terrain = function.terrain();
        int floor = terrain.floor();
        var owner = terrain.shape().topology().canonical(new Cell(face, x, 0, z)).cell();
        if (terrain.terrainPlan().localContent(owner.face(), owner.u(), owner.v()))
            return VoxelColumn.visibleBlocks(function.blocks(face, x, z), floor);
        var visible = function.visible(face, x, z);
        int low = visible.sides().stream().mapToInt(SurfaceFunction.Side::bottom).min().orElseThrow();
        int bottom = Math.max(
                floor, Math.min(Math.min(visible.top(), low), terrain.groundY(face, x, z) - 3));
        var states = function.blocks(face, x, z, bottom);
        var runs = new ArrayList<VoxelColumn.Run>();
        var raw = states[bottom - floor];
        var shown = VoxelMesher.plant(raw) ? raw : VoxelMesher.shown(raw);
        if (bottom > floor && !shown.isAir()) runs.add(new VoxelColumn.Run(floor, bottom, shown));
        var band = Arrays.copyOfRange(states, bottom - floor, states.length);
        runs.addAll(VoxelColumn.visibleBlocks(band, bottom).runs());
        return VoxelColumn.compose(runs);
    }

    private static VoxelColumn reference(BlockState[] states, int floor) {
        var source = new ArrayList<VoxelColumn.Run>();
        for (int y = 0; y < states.length; y++) {
            var raw = states[y];
            if (raw.isAir()) continue;
            var shown = VoxelMesher.plant(raw) ? raw : VoxelMesher.shown(raw);
            if (!shown.isAir()) source.add(new VoxelColumn.Run(floor + y, floor + y + 1, shown));
        }
        return VoxelColumn.compose(source);
    }
}
