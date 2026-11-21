package dev.starfold.mining;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class OilCycleTest {
    private static final class Source implements OilCycle.Reservoir {
        int amount, writes;
        boolean fail;

        Source(int amount) {
            this.amount = amount;
        }

        @Override
        public int read() {
            return amount;
        }

        @Override
        public void write(int next) {
            if (fail) throw new IllegalStateException("SavedData unavailable");
            amount = next;
            writes++;
        }
    }

    private static final class Tank implements OilCycle.Receiver {
        int stored, before, capacity = OilCycle.TANK_CAPACITY, acceptance = Integer.MAX_VALUE;
        boolean failAfterInsertion;

        @Override
        public int simulate(int requested) {
            return Math.min(requested, capacity - stored);
        }

        @Override
        public int receive(int offered) {
            before = stored;
            int received = Math.min(offered, acceptance);
            stored += received;
            if (failAfterInsertion) throw new IllegalStateException("Insertion failed");
            return received;
        }

        @Override
        public void rollback() {
            stored = before;
        }
    }

    @Test
    void fullTankDoesNotTouchReservoirOrInvokeReceiver() {
        Source source = new Source(500);
        Tank tank = new Tank();
        tank.stored = tank.capacity;
        tank.failAfterInsertion = true;
        assertEquals(0, OilCycle.transfer(source, tank, 10000));
        assertEquals(500, source.amount);
        assertEquals(0, source.writes);
        assertEquals(tank.capacity, tank.stored);
    }

    @Test
    void capacityAndEnergyLimitActualProductionAndDebit() {
        Source source = new Source(500);
        Tank tank = new Tank();
        tank.stored = tank.capacity - 13;
        int accepted = OilCycle.transfer(source, tank, 200);
        assertEquals(13, accepted);
        assertEquals(487, source.amount);
        assertEquals(tank.capacity, tank.stored);
        Tank other = new Tank();
        assertEquals(4, OilCycle.transfer(source, other, 9));
        assertEquals(483, source.amount);
        assertEquals(4, other.stored);
        assertEquals(0, OilCycle.transfer(source, other, -1));
    }

    @Test
    void debitMatchesActualReceiptEvenWhenSimulationOverestimates() {
        Source source = new Source(1000);
        Tank tank = new Tank();
        tank.acceptance = 7;
        assertEquals(7, OilCycle.transfer(source, tank, 20000));
        assertEquals(993, source.amount);
        assertEquals(7, tank.stored);
    }

    @Test
    void finiteDepletionWritesZeroAndAnotherMachineReadsTheLiveRemainder() {
        Source shared = new Source(145);
        Tank first = new Tank(), second = new Tank();
        assertEquals(100, OilCycle.transfer(shared, first, 20000));
        assertEquals(45, OilCycle.transfer(shared, second, 20000));
        assertEquals(0, shared.amount);
        assertEquals(2, shared.writes);
        assertEquals(0, OilCycle.transfer(shared, first, 20000));
        assertEquals(145, first.stored + second.stored);
    }

    @Test
    void infiniteCdgReservoirIsNeverDecremented() {
        Source source = new Source(Integer.MAX_VALUE);
        Tank tank = new Tank();
        assertEquals(100, OilCycle.transfer(source, tank, 200));
        assertEquals(Integer.MAX_VALUE, source.amount);
        assertEquals(0, source.writes);
    }

    @Test
    void failedCdgDebitAndFailedInsertionRollbackTank() {
        Source source = new Source(300);
        source.fail = true;
        Tank tank = new Tank();
        tank.stored = 20;
        assertThrows(IllegalStateException.class, () -> OilCycle.transfer(source, tank, 200));
        assertEquals(20, tank.stored);
        assertEquals(300, source.amount);
        source.fail = false;
        tank.failAfterInsertion = true;
        assertThrows(IllegalStateException.class, () -> OilCycle.transfer(source, tank, 200));
        assertEquals(20, tank.stored);
        assertEquals(300, source.amount);
        assertEquals(0, source.writes);
    }

    @Test
    void savedProgressRoundTripsAndDoesNotRecreditSpentEnergyOrSurveyWork() {
        var building =
                new OilCycle.Progress(
                        1210,
                        1543,
                        100,
                        187,
                        8,
                        2,
                        OilStatus.BUILDING.ordinal(),
                        1459,
                        true,
                        false);
        Map<String, Integer> ints = new HashMap<>();
        Map<String, Boolean> flags = new HashMap<>();
        building.save(ints::put, flags::put);
        var restored =
                OilCycle.Progress.load(
                        key -> ints.getOrDefault(key, 0), key -> flags.getOrDefault(key, false));
        assertEquals(building, restored);
        assertEquals(1210, restored.energy());
        assertEquals(187, restored.depth());
        assertEquals(100, restored.survey());
        var pumping =
                new OilCycle.Progress(
                        1010, 1543, 100, 188, 19, 3, OilStatus.PUMPING.ordinal(), 1359, true, true);
        pumping.save(ints::put, flags::put);
        assertEquals(pumping, OilCycle.Progress.load(ints::get, flags::get));
    }

    @Test
    void deepHomeCoreWellSurvivesRestartWithoutTruncatingItsPaidPath() {
        // Authored home size 2048, depth ratio .5: floor = 127 - round(radius * .5).
        int floor = 127 - (int) Math.round((2048 * 2 / Math.PI) * .5);
        int depth = 142 - floor;
        assertEquals(667, depth);
        assertTrue(depth <= OilCycle.MAX_DEPTH);
        var pumping =
                new OilCycle.Progress(
                        1010,
                        1543,
                        100,
                        depth,
                        19,
                        3,
                        OilStatus.PUMPING.ordinal(),
                        1359,
                        true,
                        true);
        Map<String, Integer> ints = new HashMap<>();
        Map<String, Boolean> flags = new HashMap<>();
        pumping.save(ints::put, flags::put);
        var restored = OilCycle.Progress.load(ints::get, flags::get);
        assertEquals(pumping, restored);
        assertEquals(depth, restored.depth());
        assertTrue(restored.well());
    }

    @Test
    void corruptOrLegacyProgressCannotGrantAWellOrFreeStoredPower() {
        var value =
                new OilCycle.Progress(Integer.MAX_VALUE, -5, 99, -1, 999, 3, 999, -8, true, true);
        assertEquals(OilCycle.ENERGY_CAPACITY, value.energy());
        assertEquals(0, value.burnTicks());
        assertEquals(0, value.depth());
        assertEquals(19, value.clock());
        assertFalse(value.surveyed());
        assertFalse(value.well());
        assertEquals(0, value.mode());
        var legacy = OilCycle.Progress.load(key -> 0, key -> false);
        assertEquals(0, legacy.energy());
        assertFalse(legacy.well());
        assertEquals(0, legacy.mode());
    }
}
