package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StripTopologyTest {
    private final StripTopology.Cell left = new StripTopology.Cell(20_000_000, 128, 20_000_000);
    private final StripTopology.Cell right = new StripTopology.Cell(20_010_000, 96, 20_010_000);
    private final StripTopology topology = new StripTopology(left, right);

    @Test
    void translatedDestinationChecksKeepRelativeDirectionInBothDirections() {
        for (var pair :
                new StripTopology.Cell[][] {
                    {left.add(15, 9, 4), left.add(16, 9, 4)},
                    {right.add(0, 9, 4), right.add(-1, 9, 4)}
                }) {
            var from = pair[0];
            var to = pair[1];
            var real = topology.canonical(to);
            int dx = real.x() - to.x(), dy = real.y() - to.y(), dz = real.z() - to.z();
            var translatedFrom = from.add(dx, dy, dz);
            assertEquals(from, topology.canonical(translatedFrom));
            assertEquals(to.x() - from.x(), real.x() - translatedFrom.x());
            assertEquals(to.y() - from.y(), real.y() - translatedFrom.y());
            assertEquals(to.z() - from.z(), real.z() - translatedFrom.z());
        }
    }

    @Test
    void realTargetIsInsideDestinationFootprintWhileGhostIsNot() {
        var ghost = left.add(16, 9, 4);
        var real = topology.canonical(ghost);
        assertEquals(right.add(0, 9, 4), real);
        assertFalse(ghost.x() >= left.x() && ghost.x() < left.x() + StripTopology.WIDTH);
        assertTrue(real.x() >= right.x() && real.x() < right.x() + StripTopology.WIDTH);
        var support = topology.canonical(left.add(16, 8, 4));
        assertEquals(right.add(0, 8, 4), support);
        assertEquals(support, topology.canonical(left.add(16, 9, 4)).add(0, -1, 0));
    }

    @Test
    void bidirectionalHorizontalNeighbours() {
        assertEquals(right.add(0, 9, 4), topology.canonical(left.add(16, 9, 4)));
        assertEquals(left.add(15, 9, 4), topology.canonical(right.add(-1, 9, 4)));
    }

    @Test
    void entireHaloIsCanonicalAndIdempotent() {
        for (int dx = 0; dx < 8; dx++)
            for (int y = -8; y < 24; y++)
                for (int z = -4; z < 12; z++) {
                    var a = topology.canonical(left.add(16 + dx, y, z));
                    var b = topology.canonical(right.add(-1 - dx, y, z));
                    assertEquals(right.add(dx, y, z), a);
                    assertEquals(left.add(15 - dx, y, z), b);
                    assertEquals(a, topology.canonical(a));
                    assertEquals(b, topology.canonical(b));
                }
    }

    @Test
    void aboveBelowAndAlongSeamCommuteWithMapping() {
        for (int y = 0; y < 20; y++)
            for (int z = 0; z < 8; z++) {
                var query = left.add(16, y, z);
                var mapped = topology.canonical(query);
                for (var d : new int[][] {{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}})
                    assertEquals(
                            mapped.add(d[0], d[1], d[2]),
                            topology.canonical(query.add(d[0], d[1], d[2])));
            }
    }

    @Test
    void ownCellsAreNotRemapped() {
        for (int x = 0; x < 32; x++) {
            var real = topology.real(x, 9, 4);
            assertEquals(real, topology.canonical(real));
        }
    }

    @Test
    void unrelatedCoordinatesAndBeyondBoundsAreUnchanged() {
        for (var p :
                new StripTopology.Cell[] {
                    new StripTopology.Cell(0, 80, 0),
                    left.add(24, 9, 4),
                    left.add(16, 33, 4),
                    left.add(16, 9, 17),
                    right.add(-9, 9, 4)
                }) assertEquals(p, topology.canonical(p));
    }

    @Test
    void logicalCellsHaveUniqueOwners() {
        var owners = new java.util.HashSet<StripTopology.Cell>();
        for (int x = 0; x < 32; x++)
            for (int y = 0; y < 20; y++)
                for (int z = 0; z < 8; z++) assertTrue(owners.add(topology.real(x, y, z)));
        assertThrows(IllegalArgumentException.class, () -> topology.real(-1, 9, 4));
        assertThrows(IllegalArgumentException.class, () -> topology.real(32, 9, 4));
    }
}
