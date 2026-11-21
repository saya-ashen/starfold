package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetCatalog;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

class GroundTicketPrefetchTest {
    private static final AABB HALO = new AABB(-16, -64, -16, 31, 320, 31);

    @Test
    void lowAltitudeWindowHoldsOnlyCanonicalOtherFaceOwners() {
        var access = PlanetAccess.SHARED;
        var catalog = PlanetCatalog.current();
        var seam = new GroundTickets.Window(catalog, 0, 8, 0, 2).owners();
        var west = access.address(new BlockPos(-8, 128, 136)).owner();
        assertTrue(seam.contains(access.key(west.getX() >> 4, west.getZ() >> 4)));
        assertFalse(seam.contains(access.key(0, 8)));
        assertTrue(new GroundTickets.Window(catalog, 8, 8, 0, 2).owners().isEmpty());
        assertTrue(new GroundTickets.Window(catalog, 8, 8, 0, 10).owners().size() > seam.size());
    }

    @Test
    void spaceWindowUsesTheSameBoundaryAsImageStreaming() {
        int x = (int) Math.floor(Space.X - Space.REACH) >> 4;
        int z = (int) Math.floor(Space.Z) >> 4;
        var window = new GroundTickets.Window(PlanetCatalog.current(), x, z, -1, 2);
        var owners = new HashSet<>(window.owners());
        assertFalse(owners.isEmpty());
        assertEquals(new HashSet<>(ImageStream.regionPositions(new ChunkPos(x, z), 2)), owners);
        assertFalse(owners.contains(ChunkPos.asLong(x - 1, z)));
    }

    @Test
    void stationaryAndInvalidVelocitiesRetainTheCurrentHalo() {
        assertSame(HALO, GroundTickets.prefetch(HALO, 0, 0));
        assertSame(HALO, GroundTickets.prefetch(HALO, Double.NaN, 1));
        assertSame(HALO, GroundTickets.prefetch(HALO, 1, Double.POSITIVE_INFINITY));
    }

    @Test
    void travelSweepsOneSecondInItsDirectionWithoutChangingHeight() {
        var ahead = GroundTickets.prefetch(HALO, 1, -2);
        assertEquals(HALO.minX, ahead.minX);
        assertEquals(HALO.maxX + 20, ahead.maxX, 1e-10);
        assertEquals(HALO.minZ - 40, ahead.minZ, 1e-10);
        assertEquals(HALO.maxZ, ahead.maxZ);
        assertEquals(HALO.minY, ahead.minY);
        assertEquals(HALO.maxY, ahead.maxY);
    }

    @Test
    void fastTravelAndTeleportDiscontinuitiesHaveAFourChunkVectorCap() {
        for (double speed : new double[] {4, 40, 4000000})
            for (int sx : new int[] {-1, 1})
                for (int sz : new int[] {-1, 1}) {
                    var ahead = GroundTickets.prefetch(HALO, sx * 3 * speed, sz * 4 * speed);
                    double x = Math.max(HALO.minX - ahead.minX, ahead.maxX - HALO.maxX);
                    double z = Math.max(HALO.minZ - ahead.minZ, ahead.maxZ - HALO.maxZ);
                    assertEquals(64, Math.hypot(x, z), 1e-10);
                    assertEquals(3.0 / 4, x / z, 1e-10);
                    assertTrue(ahead.minX <= HALO.minX && ahead.maxX >= HALO.maxX);
                    assertTrue(ahead.minZ <= HALO.minZ && ahead.maxZ >= HALO.maxZ);
                }
    }
}
