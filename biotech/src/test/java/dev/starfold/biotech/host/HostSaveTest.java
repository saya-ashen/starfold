package dev.starfold.biotech.host;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Set;
import java.util.UUID;

class HostSaveTest {
    private static HostSave roundTrip(HostSave data) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(data.save(new CompoundTag(), null), new DataOutputStream(bytes));
        return HostSave.load(
                NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))),
                null);
    }

    @Test
    void planetsHaveIndependentRespawnableEncounters() throws IOException {
        var host = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var fresh = roundTrip(new HostSave());
        assertTrue(fresh.canSpawn(3, 0));
        fresh.spawn(3, host, Set.of(a, b), 0);
        var spawned = roundTrip(fresh);
        assertTrue(spawned.active(host));
        assertEquals(2, spawned.livingSacs(host));
        assertTrue(spawned.canSpawn(4, 0));
        assertFalse(spawned.canSpawn(3, HostSave.RESPAWN_DELAY_TICKS - 1));
        spawned.sacDied(UUID.randomUUID(), a);
        assertEquals(2, spawned.livingSacs(host));
        spawned.sacDied(host, a);
        assertEquals(1, spawned.livingSacs(host));
        spawned.defeat(host, HostSave.RESPAWN_DELAY_TICKS);
        var defeated = roundTrip(spawned);
        assertFalse(defeated.active(host));
        assertEquals(0, defeated.livingSacs(host));
        assertFalse(defeated.canSpawn(3, HostSave.RESPAWN_DELAY_TICKS * 2 - 1));
        assertTrue(defeated.canSpawn(3, HostSave.RESPAWN_DELAY_TICKS * 2));
    }

    @Test
    void malformedAndInconsistentSavesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(new CompoundTag(), null));
        var tag = new HostSave().save(new CompoundTag(), null);
        var list = tag.getList("Encounters", 10);
        var entry = new CompoundTag();
        entry.putInt("Planet", 3);
        entry.putBoolean("Defeated", false);
        entry.putLong("RespawnAt", 0);
        tag.put("Encounters", list);
        assertDoesNotThrow(() -> HostSave.load(tag, null));
        entry.putBoolean("Defeated", true);
        var invalid = new ListTag();
        invalid.add(entry);
        tag.put("Encounters", invalid);
        assertThrows(IllegalArgumentException.class, () -> HostSave.load(tag, null));
    }

    @Test
    void legacyActiveHostKeepsItsSacsAndClaimsItsOwnPlanet() throws IOException {
        var host = UUID.randomUUID();
        var tag = new CompoundTag();
        tag.putUUID("Host", host);
        tag.putBoolean("Defeated", false);
        var sacs = new ListTag();
        sacs.add(net.minecraft.nbt.NbtUtils.createUUID(UUID.randomUUID()));
        tag.put("Sacs", sacs);
        var save = HostSave.load(tag, null);
        assertEquals(host, save.legacyHost());
        save.track(12, host, 23L);
        var restored = roundTrip(save);
        assertNull(restored.legacyHost());
        assertEquals(host, restored.activeHost(12));
        assertEquals(23L, restored.lastChunk(12));
        assertEquals(1, restored.livingSacs(host));
        assertTrue(restored.canSpawn(13, 0));
    }
}
