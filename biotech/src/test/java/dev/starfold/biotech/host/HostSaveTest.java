package dev.starfold.biotech.host;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HostSaveTest {
    private static HostSave roundTrip(HostSave data) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(data.save(new CompoundTag(), null), new DataOutputStream(bytes));
        return HostSave.load(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))), null);
    }

    @Test void freshSpawnSacDeathsAndDefeatSurviveBinaryNbt() throws IOException {
        var host = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var fresh = roundTrip(new HostSave());
        assertFalse(fresh.spawned());
        assertFalse(fresh.defeated());
        fresh.spawn(host, Set.of(a, b));
        var spawned = roundTrip(fresh);
        assertTrue(spawned.spawned());
        assertTrue(spawned.owns(host));
        assertEquals(2, spawned.livingSacs());
        spawned.sacDied(UUID.randomUUID(), a);
        assertEquals(2, spawned.livingSacs());
        spawned.setDirty(false);
        spawned.sacDied(host, a);
        assertTrue(spawned.isDirty());
        assertEquals(1, roundTrip(spawned).livingSacs());
        spawned.sacDied(host, a);
        assertEquals(1, spawned.livingSacs());
        spawned.defeat(UUID.randomUUID());
        assertFalse(spawned.defeated());
        spawned.defeat(host);
        var defeated = roundTrip(spawned);
        assertTrue(defeated.spawned());
        assertTrue(defeated.defeated());
        assertEquals(0, defeated.livingSacs());
        assertFalse(HostRules.shouldSpawn(defeated.spawned(), defeated.defeated(), 0, 0, 60));
    }

    @Test void malformedAndInconsistentSavesAreRejected() {
        assertFalse(HostSave.fresh(false).spawned());
        assertThrows(IllegalStateException.class, () -> HostSave.fresh(true));
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(new CompoundTag(), null));
        var tag = new HostSave().save(new CompoundTag(), null);
        tag.putString("Host", "not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(tag, null));
        tag.remove("Host");
        tag.putBoolean("Defeated", true);
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(tag, null));
        tag.putBoolean("Defeated", false);
        var invalid = new ListTag();
        invalid.add(StringTag.valueOf("not-a-uuid"));
        tag.put("Sacs", invalid);
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(tag, null));
    }

    @Test void duplicateSacsAndDefeatedLiveSacsAreRejected() {
        var data = new HostSave();
        data.spawn(UUID.randomUUID(), Set.of(UUID.randomUUID()));
        var tag = data.save(new CompoundTag(), null);
        var list = tag.getList("Sacs", 11);
        list.add(list.get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(tag, null));
        list.remove(1);
        tag.putBoolean("Defeated", true);
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(tag, null));
    }
}
