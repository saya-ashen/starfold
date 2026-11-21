package dev.starfold.mining;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class ReceiveOnlyEnergyTest {
    @Test
    void receptionClampsToRoomAndOnlyRealAcceptedEnergyNotifies() {
        AtomicInteger stored = new AtomicInteger(19990), changes = new AtomicInteger();
        var power =
                new ReceiveOnlyEnergy(20000, stored::get, stored::set, changes::incrementAndGet);
        assertEquals(10, power.receiveEnergy(Integer.MAX_VALUE, true));
        assertEquals(19990, stored.get());
        assertEquals(0, changes.get());
        assertEquals(0, power.receiveEnergy(-1, false));
        assertEquals(0, power.receiveEnergy(0, false));
        assertEquals(0, changes.get());
        assertEquals(10, power.receiveEnergy(Integer.MAX_VALUE, false));
        assertEquals(20000, stored.get());
        assertEquals(1, changes.get());
        assertEquals(0, power.receiveEnergy(1, false));
        assertEquals(1, changes.get());
        assertEquals(20000, power.getMaxEnergyStored());
        assertTrue(power.canReceive());
        assertFalse(power.canExtract());
        assertEquals(0, power.extractEnergy(1000, true));
        assertEquals(0, power.extractEnergy(1000, false));
        assertEquals(20000, stored.get());
        assertEquals(1, changes.get());
    }

    @Test
    void readsLiveMachineStateAfterFuelSpendingOrRestoreAndWritesBeforeNotifying() {
        AtomicInteger stored = new AtomicInteger(), changes = new AtomicInteger();
        var power =
                new ReceiveOnlyEnergy(
                        20000,
                        stored::get,
                        stored::set,
                        () -> {
                            assertEquals(1500, stored.get());
                            changes.incrementAndGet();
                        });
        stored.set(1000);
        assertEquals(1000, power.getEnergyStored());
        assertEquals(500, power.receiveEnergy(500, false));
        assertEquals(1, changes.get());
        stored.set(1200);
        assertEquals(1200, power.getEnergyStored());
        assertEquals(18800, power.receiveEnergy(20000, true));
        assertEquals(1200, stored.get());
        assertEquals(1, changes.get());
    }
}
