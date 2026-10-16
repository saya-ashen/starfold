package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class ShipSaveTest {
    static final ResourceLocation DIMENSION = ResourceLocation.parse("starfold:native_planet");

    static ShipRecord.Place place() {
        return new ShipRecord.Place(
                3,
                new Point(20_480_021, 20, 20_480_045),
                new Point(321, 54, -90),
                new Quaterniond().rotationXYZ(.1, .2, .3),
                12345);
    }

    static CompoundTag tag(ShipSave save) {
        return save.save(new CompoundTag(), null);
    }

    static ShipSave fixture() {
        var save = ShipSave.fresh(false);
        save.add(UUID.randomUUID(), DIMENSION, Optional.empty(), new ShipRecord.OnSlot(1));
        save.add(
                UUID.randomUUID(),
                DIMENSION,
                Optional.of("Moon ship"),
                new ShipRecord.InSpace(place()));
        return save;
    }

    @Test
    void typedNbtRoundTripsAllStatesNamesAndOptionalLocator() throws Exception {
        var saved = fixture();
        var root = tag(saved);
        var entry = root.getList("records", Tag.TAG_COMPOUND).getCompound(1);
        var locator = new CompoundTag();
        locator.putInt("chunk_x", -20000);
        locator.putInt("chunk_z", 30000);
        locator.putShort("storage_index", Short.MAX_VALUE);
        locator.putShort("sub_level_index", Short.MIN_VALUE);
        entry.put("locator", locator);
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(root, new DataOutputStream(bytes));
        var disk = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        var restored = ShipSave.load(disk, null);
        assertEquals(saved.list().getFirst(), restored.list().getFirst());
        var record = restored.list().get(1);
        assertEquals(saved.list().get(1).id(), record.id());
        assertEquals(saved.list().get(1).uuid(), record.uuid());
        assertEquals(saved.list().get(1).state(), record.state());
        assertEquals(DIMENSION, record.dimension());
        assertEquals(Optional.of("Moon ship"), record.name());
        assertEquals(
                Optional.of(
                        new ShipRecord.Locator(-20000, 30000, Short.MAX_VALUE, Short.MIN_VALUE)),
                record.locator());
        assertEquals(root, tag(restored));
    }

    @Test
    void idsAreNeverReusedEvenAcrossSaveReloadAndDuplicateUuidIsRefused() {
        var saved = fixture();
        assertEquals(new ShipId(0, 0), saved.list().getFirst().id());
        UUID uuid = saved.list().get(1).uuid();
        assertThrows(
                IllegalArgumentException.class,
                () -> saved.add(uuid, DIMENSION, Optional.empty(), new ShipRecord.OnSlot(0)));
        for (var record : saved.list()) saved.remove(record.uuid());
        var loaded = ShipSave.load(tag(saved), null);
        assertEquals(
                new ShipId(0, 2),
                loaded.add(UUID.randomUUID(), DIMENSION, Optional.empty(), new ShipRecord.OnSlot(0))
                        .id());
    }

    @Test
    void unknownVersionsUnreadableFilesAndMalformedTypesAreRefused() {
        var valid = tag(fixture());
        for (int version : new int[] {-1, 0, 1, 99}) {
            var bad = valid.copy();
            bad.putInt("format", version);
            assertThrows(IllegalArgumentException.class, () -> ShipSave.load(bad, null));
        }
        assertThrows(IllegalStateException.class, () -> ShipSave.fresh(true));
        var bad = valid.copy();
        bad.putLong("next_index", 2);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(bad, null));
        var badList = valid.copy();
        var list = new ListTag();
        list.add(StringTag.valueOf("not a record"));
        badList.put("records", list);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(badList, null));
        var badPlace = valid.copy();
        badPlace.getList("records", 10).getCompound(1).getCompound("place").putInt("tick", 12345);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(badPlace, null));
        var badLocator = valid.copy();
        badLocator.getList("records", 10).getCompound(1).putString("locator", "bad");
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(badLocator, null));
    }

    @Test
    void duplicateIdsUuidsAndCounterRewindAreRefusedOnLoad() {
        var valid = tag(fixture());
        var duplicateUuid = valid.copy();
        var entries = duplicateUuid.getList("records", 10);
        entries.getCompound(1).putUUID("uuid", entries.getCompound(0).getUUID("uuid"));
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(duplicateUuid, null));
        var duplicateId = valid.copy();
        duplicateId.getList("records", 10).getCompound(1).putInt("index", 0);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(duplicateId, null));
        var rewind = valid.copy();
        rewind.putInt("next_index", 1);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(rewind, null));
        assertThrows(IllegalArgumentException.class, () -> new ShipId(1, 0));
    }

    @Test
    void spaceRecordStoresBodyCoordinatesAndNeverLevelLabels() {
        var entry = tag(fixture()).getList("records", 10).getCompound(1);
        assertEquals(
                Set.of("region", "index", "uuid", "dimension", "name", "state", "place"),
                entry.getAllKeys());
        var place = entry.getCompound("place");
        assertEquals(
                Set.of("body", "anchor_plot", "position", "attitude", "tick"), place.getAllKeys());
        assertEquals(321, place.getCompound("position").getDouble("x"));
        assertFalse(entry.toString().contains("level_pose"));
        assertFalse(entry.toString().contains("saved_frame"));
    }
}
