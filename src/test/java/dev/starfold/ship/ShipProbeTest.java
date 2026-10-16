package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.dev.DevShipPersistenceProbe;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ShipProbeTest {
    @Test
    void inspectionExplainsCargoAndFixtureRemovalNeverDeletesPlotResidents() throws Exception {
        var source = dev.starfold.Sources.text("DevShipPersistenceProbe");
        assertTrue(source.contains("SableTickets.aboardTests(live,entity)"));
        assertTrue(source.contains("BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())"));
        assertTrue(source.contains("entity.position()"));
        assertTrue(source.contains("aboard_tests="));
        assertTrue(source.contains("keeps_awake="));
        assertTrue(source.contains("if(!(entity instanceof ItemEntity item))continue"));
        assertTrue(source.contains("if(tests.isEmpty()||tests.contains(\"plot\"))continue"));
        assertTrue(source.contains("elapsed_ticks={}"));
        assertTrue(source.contains("if(!enabled()||watched==null)return"));
        assertTrue(source.contains("holds.clear();watched=null"));
        assertTrue(
                dev.starfold.Sources.text("StarfoldServer")
                        .contains("DevShipPersistenceProbe.tick(level)"));
    }

    @Test
    void entityAndCrewVerdictsRequireTheRealRetainedHull() throws Exception {
        var source = dev.starfold.Sources.text("DevShipPersistenceProbe");
        assertTrue(source.contains("case\"assert entity-awake\""));
        assertTrue(
                source.contains(
                        "!SableTickets.input(level,live).reasons().contains(ShipLeases.Reason.NON_PLAYER_ABOARD)"));
        assertTrue(source.contains("case\"assert aboard\""));
        assertTrue(source.contains("SableFrames.aboard(player)!=live"));
        assertTrue(source.contains("live.logicalPose().transformPositionInverse("));
    }

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
