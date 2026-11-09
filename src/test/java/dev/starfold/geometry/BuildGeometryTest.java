package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BuildGeometryTest {
    @Test
    void buildingDepthMapsLastRowBothDirections() {
        var a = new StripTopology.Cell(10000, 0, 10000);
        var b = new StripTopology.Cell(-10000, 0, -10000);
        var atlas = new StripTopology(a, b, 32);
        assertEquals(b.add(0, 20, 31), atlas.canonical(a.add(16, 20, 31)));
        assertEquals(a.add(15, 20, 31), atlas.canonical(b.add(-1, 20, 31)));
    }

    @Test
    void buildingHaloIsBoundedAndLegacyDepthStaysUnchanged() {
        var a = new StripTopology.Cell(10000, 0, 10000);
        var b = new StripTopology.Cell(-10000, 0, -10000);
        assertEquals(a.add(16, 9, 40), new StripTopology(a, b, 32).canonical(a.add(16, 9, 40)));
        assertEquals(a.add(16, 9, 31), new StripTopology(a, b).canonical(a.add(16, 9, 31)));
    }

    @Test
    void invalidAtlasDepthRejected() {
        var p = new StripTopology.Cell(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new StripTopology(p, p, 0));
        assertThrows(IllegalArgumentException.class, () -> new StripTopology(p, p, 33));
    }
}
