package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;
import dev.starfold.space.SpacePackets;

import org.junit.jupiter.api.Test;

class ShipHostTest {

    @Test
    void standaloneDefaultsHaveNoExternalControllerOrPreparedArrival() {
        var host = new ShipHost() {};
        assertFalse(host.externalRoll(null, null));
        assertFalse(host.landingLoaded(null, null));
        assertFalse(host.landingReady(dev.starfold.chart.GroundSolar.defaults().body(0), 0, true));
        assertEquals(Space.Frame.IDENTITY, host.surroundingsFrame(null));
        assertEquals(dev.starfold.chart.GroundEphemeris.ZERO, host.carriedSpin(null, null));
        assertEquals(Space.Frame.IDENTITY, host.departureFrame(null, null, null, null, 0, 0));
        assertEquals(new SpacePackets.Frame(null, 0), host.carriedFrame(null));
        assertEquals(
                new net.minecraft.world.level.ChunkPos(-1, 2),
                host.landingColumn(new Point(-.1, 0, 32)));
        assertDoesNotThrow(
                () -> {
                    host.pilotReleased(null);
                    host.releaseArrival(null);
                    host.advanceSurroundings(null, null);
                    host.hullDeparted(null, 0, "hull");
                    host.hullEnteredChart(null, "hull");
                    host.hullArrived(null, null);
                });
        var client = new ShipClientHost() {};
        assertFalse(client.movingSurroundings());
        assertFalse(client.mouseSteering());
        assertDoesNotThrow(() -> client.carriedFrame(null));
    }
}
