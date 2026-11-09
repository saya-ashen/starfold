package dev.starfold.probes;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.dev.DevShipPersistenceProbe;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ShipProbeTest {
    @Test
    void overrideIsOffByDefaultShipSpecificAndClearedOnServerStop() throws Exception {
        String key = "starfold.shipProbe", previous = System.getProperty(key);
        var ship = UUID.randomUUID();
        var other = UUID.randomUUID();
        var field = DevShipPersistenceProbe.class.getDeclaredField("excluded");
        field.setAccessible(true);
        try {
            DevShipPersistenceProbe.clear();
            field.set(null, ship);
            System.clearProperty(key);
            assertFalse(DevShipPersistenceProbe.enabled());
            assertTrue(DevShipPersistenceProbe.alwaysTicket(ship));
            assertTrue(DevShipPersistenceProbe.alwaysTicket(other));
            System.setProperty(key, "true");
            assertFalse(DevShipPersistenceProbe.alwaysTicket(ship));
            assertTrue(DevShipPersistenceProbe.alwaysTicket(other));
            DevShipPersistenceProbe.clear();
            assertTrue(DevShipPersistenceProbe.alwaysTicket(ship));
            assertFalse(DevShipPersistenceProbe.held(ship));
        } finally {
            DevShipPersistenceProbe.clear();
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
