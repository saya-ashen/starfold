package dev.starfold.worldgen.lod;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VoxelColumnsTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static BlockState building(int x, int y, int z) {
        if (y < 0) return Blocks.STONE.defaultBlockState();
        if (y == 12 || x == 3 && z == 5 && y < 12) return Blocks.OAK_PLANKS.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private static VoxelColumns capture(VoxelColumns.Reader reader, int budget) {
        var capture = new VoxelColumns.Capture(-4, 20);
        int total = 0;
        while (!capture.done()) {
            int reads = capture.step(reader, budget);
            assertTrue(reads <= budget);
            total += reads;
        }
        assertEquals(256 * 24, total);
        return capture.result().orElseThrow();
    }

    @Test
    void exactBridgeColumnsKeepAirUnderDeckAndAcrossEveryTileEdge() {
        var image = capture(VoxelColumnsTest::building, 37);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = -4; y < 20; y++) assertSame(building(x, y, z), image.state(x, y, z));
        assertSame(Blocks.AIR.defaultBlockState(), image.state(0, 5, 0));
        assertSame(Blocks.AIR.defaultBlockState(), image.state(0, -5, 0));
        assertSame(Blocks.AIR.defaultBlockState(), image.state(0, 20, 0));
        assertTrue(image.memoryBytes() < 256 * 24 * 4);
        assertThrows(IllegalArgumentException.class, () -> image.state(16, 0, 0));
    }

    @Test
    void serializedArraysRoundTripAndNeverExposeMutableStorage() {
        var image = capture(VoxelColumnsTest::building, 1024);
        var offsets = image.offsets();
        var ends = image.ends();
        var states = image.states();
        var copy = new VoxelColumns(image.floor(), image.ceiling(), offsets, ends, states);
        offsets[0] = -1;
        ends[0] = -100;
        states[0] = -1;
        assertTrue(image.sameAs(copy));
        image.offsets()[0] = 123;
        image.ends()[0] = 123;
        image.states()[0] = 123;
        assertTrue(image.sameAs(copy));
        var changed =
                capture(
                        (x, y, z) -> y == 12 ? Blocks.AIR.defaultBlockState() : building(x, y, z),
                        1);
        assertFalse(image.sameAs(changed));
        assertFalse(image.sameAs(null));
    }

    @Test
    void malformedOffsetsRunsStatesAndCoverageAreRejected() {
        var image = capture(VoxelColumnsTest::building, 1024);
        var offsets = image.offsets();
        offsets[1] = offsets[0];
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoxelColumns(-4, 20, offsets, image.ends(), image.states()));
        var ends = image.ends();
        ends[0] = -4;
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoxelColumns(-4, 20, image.offsets(), ends, image.states()));
        var incomplete = image.ends();
        incomplete[image.offsets()[1] - 1] = 19;
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoxelColumns(-4, 20, image.offsets(), incomplete, image.states()));
        var states = image.states();
        states[0] = Integer.MAX_VALUE;
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoxelColumns(-4, 20, image.offsets(), image.ends(), states));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new VoxelColumns(
                                -4,
                                20,
                                image.offsets(),
                                new int[VoxelColumns.MAX_RUNS + 1],
                                new int[VoxelColumns.MAX_RUNS + 1]));
        assertThrows(IllegalArgumentException.class, () -> new VoxelColumns.Capture(0, 4097));
    }

    @Test
    void incompleteAndOverflowImagesCannotEscapeAndZeroBudgetDoesNoWork() {
        var capture = new VoxelColumns.Capture(0, 128);
        assertEquals(
                0,
                capture.step(
                        (x, y, z) -> {
                            fail("Zero budget read");
                            return null;
                        },
                        0));
        assertThrows(IllegalStateException.class, capture::result);
        assertThrows(
                IllegalArgumentException.class, () -> capture.step(VoxelColumnsTest::building, -1));
        int total = 0;
        while (!capture.done()) {
            int read =
                    capture.step(
                            (x, y, z) ->
                                    (y & 1) == 0
                                            ? Blocks.STONE.defaultBlockState()
                                            : Blocks.AIR.defaultBlockState(),
                            71);
            assertTrue(read <= 71);
            total += read;
        }
        assertTrue(capture.overflow());
        assertEquals(VoxelColumns.MAX_RUNS + 1, total);
        assertTrue(capture.result().isEmpty());
        assertEquals(0, capture.step(VoxelColumnsTest::building, 100));
    }
}
