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
                new dev.starfold.chart.BodyId(7, 3),
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
        save.add(0, UUID.randomUUID(), DIMENSION, Optional.empty(), new ShipRecord.OnSlot(1));
        save.add(
                0,
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
                () -> saved.add(0, uuid, DIMENSION, Optional.empty(), new ShipRecord.OnSlot(0)));
        for (var record : saved.list()) saved.remove(record.uuid());
        var loaded = ShipSave.load(tag(saved), null);
        assertEquals(
                new ShipId(0, 2),
                loaded.add(
                                0,
                                UUID.randomUUID(),
                                DIMENSION,
                                Optional.empty(),
                                new ShipRecord.OnSlot(0))
                        .id());
    }

    @Test
    void unknownVersionsUnreadableFilesAndMalformedTypesAreRefused() {
        var valid = tag(fixture());
        for (int version : new int[] {-1, 0, 1, 2, 3, 99}) {
            var bad = valid.copy();
            bad.putInt("format", version);
            assertThrows(IllegalArgumentException.class, () -> ShipSave.load(bad, null));
        }
        assertThrows(IllegalStateException.class, () -> ShipSave.fresh(true));
        var bad = valid.copy();
        bad.getList("next_indices", 10).getCompound(0).putLong("next_index", 2);
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
        rewind.getList("next_indices", 10).getCompound(0).putInt("next_index", 1);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(rewind, null));
        assertThrows(IllegalArgumentException.class, () -> new ShipId(-1, 0));
    }

    @Test
    void regionalCountersAreIndependentAndSurviveRemovalAndReload() {
        var saved = ShipSave.fresh(false);
        for (int region : new int[] {0, 7, 19}) {
            for (int index = 0; index < 2; index++) {
                var record =
                        saved.add(
                                region,
                                UUID.randomUUID(),
                                DIMENSION,
                                Optional.empty(),
                                new ShipRecord.OnSlot(0));
                assertEquals(new ShipId(region, index), record.id());
                saved.remove(record.uuid());
            }
        }
        var loaded = ShipSave.load(tag(saved), null);
        for (int region : new int[] {19, 0, 7, 42}) {
            assertEquals(
                    new ShipId(region, region == 42 ? 0 : 2),
                    loaded.add(
                                    region,
                                    UUID.randomUUID(),
                                    DIMENSION,
                                    Optional.empty(),
                                    new ShipRecord.OnSlot(0))
                            .id());
        }
        assertEquals(loaded.list(), ShipSave.load(tag(loaded), null).list());
    }

    @Test
    void nonzeroRegionIdRoundTripsThroughCodec() {
        var record =
                ShipSave.fresh(false)
                        .add(
                                7,
                                UUID.randomUUID(),
                                DIMENSION,
                                Optional.empty(),
                                new ShipRecord.InSpace(place()));
        assertEquals(new ShipId(7, 0), record.id());
        assertEquals(record, ShipCodec.read(ShipCodec.write(record)));
        assertThrows(IllegalArgumentException.class, () -> new ShipId(7, -1));
    }

    @Test
    void movingToAnotherRegionKeepsLifetimeIdAcrossReload() {
        var saved = ShipSave.fresh(false);
        var record =
                saved.add(
                        19,
                        UUID.randomUUID(),
                        DIMENSION,
                        Optional.empty(),
                        new ShipRecord.OnSlot(0));
        saved.refresh(record.uuid(), Optional.empty(), new ShipRecord.InSpace(place()));
        var loaded = ShipSave.load(tag(saved), null);
        assertEquals(new ShipId(19, 0), loaded.find(record.uuid()).id());
        assertEquals(
                7,
                ((ShipRecord.InSpace) loaded.find(record.uuid()).state()).place().body().region());
        assertEquals(
                new ShipId(7, 0),
                loaded.add(
                                7,
                                UUID.randomUUID(),
                                DIMENSION,
                                Optional.empty(),
                                new ShipRecord.InSpace(place()))
                        .id());
    }

    @Test
    void missingDuplicateNegativeAndExhaustedRegionalCountersAreRefused() {
        var valid = tag(fixture());
        var missing = valid.copy();
        missing.put("next_indices", new ListTag());
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(missing, null));
        var duplicate = valid.copy();
        var counters = duplicate.getList("next_indices", 10);
        counters.add(counters.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(duplicate, null));
        for (String field : new String[] {"region", "next_index"}) {
            var negative = valid.copy();
            negative.getList("next_indices", 10).getCompound(0).putInt(field, -1);
            assertThrows(IllegalArgumentException.class, () -> ShipSave.load(negative, null));
        }
        var exhausted = valid.copy();
        exhausted
                .getList("next_indices", 10)
                .getCompound(0)
                .putInt("next_index", Integer.MAX_VALUE);
        var saved = ShipSave.load(exhausted, null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        saved.add(
                                0,
                                UUID.randomUUID(),
                                DIMENSION,
                                Optional.empty(),
                                new ShipRecord.OnSlot(0)));
        assertEquals(
                new ShipId(7, 0),
                saved.add(
                                7,
                                UUID.randomUUID(),
                                DIMENSION,
                                Optional.empty(),
                                new ShipRecord.InSpace(place()))
                        .id());
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
        assertEquals(
                new dev.starfold.chart.BodyId(7, 3),
                dev.starfold.chart.BodyId.read(place.getCompound("body")));
        assertEquals(place(), ShipCodec.readPlace(place));
        assertFalse(entry.toString().contains("level_pose"));
        assertFalse(entry.toString().contains("saved_frame"));
    }
}
