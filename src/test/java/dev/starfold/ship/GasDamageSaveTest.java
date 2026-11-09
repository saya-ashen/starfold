package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class GasDamageSaveTest {
    @Test
    void nbtRoundTripKeepsRecentSameHullCadenceWithoutOfflineSimulation() {
        var id = new UUID(12, 34);
        var save = GasDamageSave.fresh(false);
        assertTrue(save.ready(id, 1000, 100));
        assertFalse(save.isDirty());
        save.succeeded(id, 1000, 100);
        assertTrue(save.isDirty());
        var tag = save.save(new CompoundTag(), null);
        assertEquals(1, tag.getList("successes", 10).size());
        var loaded = GasDamageSave.load(tag.copy(), null);
        assertFalse(loaded.ready(new UUID(12, 34), 1001, 100));
        assertFalse(loaded.ready(new UUID(12, 34), 1099, 100));
        var beforeDue = loaded.save(new CompoundTag(), null);
        assertEquals(tag.get("successes"), beforeDue.get("successes"));
        assertEquals(1099, beforeDue.getLong("watermark"));
        assertTrue(loaded.ready(id, 1100, 100));
        assertEquals(0, loaded.save(new CompoundTag(), null).getList("successes", 10).size());
        assertFalse(
                loaded.isDirty(), "mere time passage never applies or records structural damage");
    }

    @Test
    void saveLoadCannotRearmSameTickBudgetOrRollbackCooldown() {
        var save = GasDamageSave.fresh(false);
        save.succeeded(new UUID(0, 1), 1000, 20);
        save.succeeded(new UUID(0, 2), 1000, 20);
        var loaded = GasDamageSave.load(save.save(new CompoundTag(), null), null);
        assertFalse(loaded.ready(new UUID(0, 3), 1000, 20));
        assertFalse(loaded.ready(new UUID(0, 3), 999, 20));
        assertTrue(loaded.ready(new UUID(0, 3), 1001, 20));
        assertFalse(loaded.ready(new UUID(0, 1), 1019, 20));
        assertTrue(loaded.ready(new UUID(0, 1), 1020, 20));
    }

    @Test
    void missingWrongTypeUnknownVersionAndBadEntriesFailClosed() {
        var good = GasDamageSave.fresh(false).save(new CompoundTag(), null);
        for (var key : new String[] {"format", "watermark", "successes"}) {
            var missing = good.copy();
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> GasDamageSave.load(missing, null));
            var wrong = good.copy();
            wrong.putString(key, "wrong");
            assertThrows(IllegalArgumentException.class, () -> GasDamageSave.load(wrong, null));
        }
        var version = good.copy();
        version.putInt("format", 2);
        assertThrows(IllegalArgumentException.class, () -> GasDamageSave.load(version, null));
        var wrongList = new ListTag();
        wrongList.add(StringTag.valueOf("not an entry"));
        var entries = good.copy();
        entries.put("successes", wrongList);
        assertThrows(IllegalArgumentException.class, () -> GasDamageSave.load(entries, null));
        var absentTick = new CompoundTag();
        absentTick.putUUID("hull", new UUID(1, 2));
        var list = new ListTag();
        list.add(absentTick);
        entries.put("successes", list);
        assertThrows(IllegalArgumentException.class, () -> GasDamageSave.load(entries, null));
        assertThrows(IllegalStateException.class, () -> GasDamageSave.fresh(true));
    }
}
