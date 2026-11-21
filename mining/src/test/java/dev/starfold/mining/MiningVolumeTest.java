package dev.starfold.mining;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

class MiningVolumeTest {
    @Test
    void visitsEveryPositionOnceAndFinishesEachLayerBeforeDescending() {
        var seen = new HashSet<MiningVolume.Offset>();
        int lastY = -1;
        for (int cursor = 0; cursor < MiningVolume.TOTAL; cursor++) {
            var pos = MiningVolume.offset(cursor);
            assertTrue(seen.add(pos), "Repeated position " + pos);
            assertTrue(pos.x() >= -2 && pos.x() <= 2);
            assertTrue(pos.z() >= -2 && pos.z() <= 2);
            assertTrue(pos.y() <= -1 && pos.y() >= -MiningVolume.DEPTH);
            if (pos.y() != lastY) {
                assertEquals(lastY - 1, pos.y());
                int previousY = lastY;
                assertEquals(
                        MiningVolume.LAYER, seen.stream().filter(p -> p.y() == previousY).count());
                lastY = pos.y();
            }
        }
        assertEquals(MiningVolume.WIDTH * MiningVolume.WIDTH * MiningVolume.DEPTH, seen.size());
        assertEquals(new MiningVolume.Offset(-2, -1, -2), MiningVolume.offset(0));
        assertEquals(
                new MiningVolume.Offset(2, -MiningVolume.DEPTH, 2),
                MiningVolume.offset(MiningVolume.TOTAL - 1));
    }

    @Test
    void rejectsCursorsOutsideTheVolume() {
        assertThrows(IllegalArgumentException.class, () -> MiningVolume.offset(-1));
        assertThrows(IllegalArgumentException.class, () -> MiningVolume.offset(MiningVolume.TOTAL));
    }
}
