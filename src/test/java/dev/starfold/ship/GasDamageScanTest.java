package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

class GasDamageScanTest {
    @Test
    void fullCycleCoversEveryCellOnceAcrossNegativeChunksAndSections() {
        var domain = new GasDamageScan.Domain(-2, -1, 1, 2, -1, 0);
        var scan = new GasDamageScan(domain);
        var start = scan.position();
        var visited = new HashSet<BlockPos>();
        for (int cell = 0; cell < 2 * 2 * 2 * 4096; cell++) {
            var p = scan.position();
            assertTrue(visited.add(p));
            assertTrue(p.getX() >= -32 && p.getX() < 0);
            assertTrue(p.getZ() >= 16 && p.getZ() < 48);
            assertTrue(p.getY() >= -16 && p.getY() < 16);
            scan.nextCell();
        }
        assertEquals(start, scan.position());
        assertEquals(32768, visited.size());
    }

    @Test
    void emptySectionsAndMissingChunksAreSkippedWithoutCellSweeps() {
        var scan = new GasDamageScan(new GasDamageScan.Domain(10, 1000000, -2, 1000000, -4, 19));
        for (int i = 0; i < 17; i++) scan.nextCell();
        scan.nextSection();
        assertEquals(-3, scan.section());
        assertEquals(new BlockPos(160, -48, -32), scan.position());
        scan.nextChunk();
        assertEquals(11, scan.chunkX());
        assertEquals(-4, scan.section());
        assertEquals(new BlockPos(176, -64, -32), scan.position());
        // A single visit skips millions of hypothetical cells; no iteration depends on domain size.
        for (int i = 0; i < GasDamageScan.MAX_VISITS; i++) scan.nextChunk();
        assertEquals(267, scan.chunkX());
    }

    @Test
    void unchangedBoundsKeepProgressAndChangedBoundsResetInConstantTime() {
        var domain = new GasDamageScan.Domain(0, 0, 0, 0, 0, 0);
        var scan = new GasDamageScan(domain);
        for (int i = 0; i < 513; i++) scan.nextCell();
        var current = scan.position();
        scan.reset(new GasDamageScan.Domain(0, 0, 0, 0, 0, 0));
        assertEquals(current, scan.position());
        scan.reset(new GasDamageScan.Domain(1, 1, 0, 0, -1, -1));
        assertEquals(new BlockPos(16, -16, 0), scan.position());
        assertThrows(IllegalArgumentException.class, () -> scan.reset(null));
        assertThrows(
                IllegalArgumentException.class, () -> new GasDamageScan.Domain(1, 0, 0, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasDamageScan.Domain(0, Integer.MAX_VALUE, 0, 0, 0, 0));
    }
}
