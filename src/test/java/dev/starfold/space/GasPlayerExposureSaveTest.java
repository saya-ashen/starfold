package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GasPlayerExposureState;
import dev.starfold.chart.GasPlayerExposureValues;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;

class GasPlayerExposureSaveTest {
    @Test
    void missingLegacyPlayerDefaultsButPresentMalformedRecordNeverRefillsGrace() {
        var root = new CompoundTag();
        assertEquals(GasPlayerExposureValues.initial(), GasPlayerExposureSave.read(root));
        for (Tag bad : new Tag[] {IntTag.valueOf(100), StringTag.valueOf("bad"), new CompoundTag()}) {
            root.put(GasPlayerExposureSave.KEY, bad);
            assertEquals(new GasPlayerExposureValues(0, 0, 0), GasPlayerExposureSave.read(root));
        }
    }

    @Test
    void strictNbtTypesBoundsPartialFieldsAndCrossFieldInvariantsFailClosed() {
        for (Tag bad : new Tag[] {ByteTag.valueOf((byte) 5), LongTag.valueOf(5),
                DoubleTag.valueOf(5), StringTag.valueOf("5"), IntTag.valueOf(-1), IntTag.valueOf(101)}) {
            var root = new CompoundTag();
            GasPlayerExposureSave.write(root, GasPlayerExposureValues.initial());
            root.getCompound(GasPlayerExposureSave.KEY).put("grace_ticks", bad);
            assertEquals(new GasPlayerExposureValues(0, 0, 0), GasPlayerExposureSave.read(root));
        }
        for (String field : GasPlayerExposureValues.initial().toFields().keySet()) {
            var root = new CompoundTag();
            GasPlayerExposureSave.write(root, GasPlayerExposureValues.initial());
            root.getCompound(GasPlayerExposureSave.KEY).remove(field);
            assertEquals(new GasPlayerExposureValues(0, 0, 0), GasPlayerExposureSave.read(root));
        }
        var root = new CompoundTag();
        GasPlayerExposureSave.write(root, GasPlayerExposureValues.initial());
        root.getCompound(GasPlayerExposureSave.KEY).putInt("attempt_ticks", 1);
        assertEquals(new GasPlayerExposureValues(0, 0, 0), GasPlayerExposureSave.read(root));
    }

    @Test
    void sameTickCompletedDeathAndImmediateRespawnInterruptNinetyNineSafeTicksOnly() {
        var owner = new Object();
        var state = new GasPlayerExposureState(new GasPlayerExposureValues(0, 99, 20), 42);
        var player = new GasPlayerExposureServer.PlayerState<>(owner, state);
        assertTrue(player.completedDeath(owner, false));
        var clone = player.cloneFor(new Object(), true);
        assertEquals(new GasPlayerExposureValues(0, 0, 20), clone.exposure.values());
        assertEquals(42, clone.exposure.lastTick());
        var sameTick = clone.exposure.tick(42, dev.starfold.chart.GasPlayerExposure.confirmedSafe(),
                GasPlayerExposureState.Participation.ACTIVE);
        assertSame(clone.exposure, sameTick.state());
        var next = clone.exposure.tick(43, dev.starfold.chart.GasPlayerExposure.confirmedSafe(),
                GasPlayerExposureState.Participation.ACTIVE);
        assertEquals(new GasPlayerExposureValues(0, 1, 20), next.state().values());
        assertFalse(next.damageAttempt());
    }

    @Test
    void completedDeathPersistsBeforePostOrLogoutAndDeathCloneAlsoProvidesAFallback() {
        var owner = new Object();
        var original = new GasPlayerExposureState(new GasPlayerExposureValues(37, 99, 0), 42);
        var player = new GasPlayerExposureServer.PlayerState<>(owner, original);
        var fallbackClone = player.cloneFor(new Object(), true);
        assertEquals(0, fallbackClone.exposure.values().safeTicks());
        assertEquals(42, fallbackClone.exposure.lastTick());
        assertTrue(player.completedDeath(owner, false));
        var nbt = new CompoundTag();
        GasPlayerExposureSave.write(nbt, player.exposure.values());
        var login = GasPlayerExposureState.restored(GasPlayerExposureSave.read(nbt.copy()));
        assertEquals(new GasPlayerExposureValues(37, 0, 0), login.values());
    }

    @Test
    void nonDeathCloneKeepsCompleteStateAndAliveOrOldOwnerCallbacksCannotInterruptIt() {
        var oldOwner = new Object();
        var newOwner = new Object();
        var original = new GasPlayerExposureState(new GasPlayerExposureValues(0, 99, 7), 42);
        var player = new GasPlayerExposureServer.PlayerState<>(oldOwner, original);
        var clone = player.cloneFor(newOwner, false);
        assertSame(original, clone.exposure);
        assertFalse(clone.completedDeath(oldOwner, false));
        assertFalse(clone.completedDeath(newOwner, true), "canceled death/totem leaves an alive player alone");
        assertSame(original, clone.exposure);
        assertEquals(42, clone.exposure.lastTick());
    }

    @Test
    void repeatedCompletedDeathIsIdempotentAndPreservesDangerCountdownAndLastTick() {
        var owner = new Object();
        var player = new GasPlayerExposureServer.PlayerState<>(owner,
                new GasPlayerExposureState(new GasPlayerExposureValues(0, 99, 7), 42));
        assertTrue(player.completedDeath(owner, false));
        var once = player.exposure;
        assertTrue(player.completedDeath(owner, false));
        assertSame(once, player.exposure);
        assertEquals(new GasPlayerExposureValues(0, 0, 7), player.exposure.values());
        assertEquals(42, player.exposure.lastTick());
    }

    @Test
    void playerValuesRoundTripAcrossDiskCopyAndKeepUnrelatedData() {
        for (var values : new GasPlayerExposureValues[] {GasPlayerExposureValues.initial(),
                new GasPlayerExposureValues(0, 37, 19), new GasPlayerExposureValues(100, 100, 0)}) {
            var persistent = new CompoundTag();
            persistent.putString("other_mod", "retained");
            GasPlayerExposureSave.write(persistent, values);
            var savedEntity = new CompoundTag();
            savedEntity.put("NeoForgeData", persistent.copy());
            var restored = savedEntity.copy().getCompound("NeoForgeData");
            assertEquals(values, GasPlayerExposureSave.read(restored));
            assertEquals("retained", restored.getString("other_mod"));
            assertEquals(-1, GasPlayerExposureState.restored(GasPlayerExposureSave.read(restored)).lastTick());
            assertEquals(1, restored.getCompound(GasPlayerExposureSave.KEY).getAllKeys().stream()
                    .filter("attempt_ticks"::equals).count());
        }
    }
}
