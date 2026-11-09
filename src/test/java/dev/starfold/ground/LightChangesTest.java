package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LightChangesTest {
    @Test
    void mergesSectionsAndLayersWithoutInventingUpdates() {
        var a = new LightChanges();
        a.mark(true, 0); // bottom extra light section
        a.mark(false, 11);
        var b = new LightChanges();
        b.mark(false, 11);
        b.mark(false, 12);
        b.mark(true, 77); // top extra light section, not a 64-bit mask
        a.merge(b);
        assertEquals(2, a.sky.cardinality());
        assertEquals(2, a.block.cardinality());
        assertTrue(a.sky.get(0));
        assertTrue(a.sky.get(77));
        assertTrue(a.block.get(11));
        assertTrue(a.block.get(12));
        assertFalse(a.sky.get(11));
        assertFalse(a.block.get(0));
        a.mark(false, 13);
        assertFalse(b.block.get(13), "Player masks must not mutate the shared notification masks");
    }
}
