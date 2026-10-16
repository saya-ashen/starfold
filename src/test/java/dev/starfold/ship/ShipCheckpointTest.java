package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.util.SableNBTUtils;
import dev.starfold.Sources;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.*;

class ShipCheckpointTest {
    @Test
    void fullTagComPoseWinsOverOlderPeriodicAndMetadataPoses() {
        var logical =
                new Pose3d(
                        new Vector3d(5000, 200, 1000),
                        new Quaterniond().rotateY(.7),
                        new Vector3d(20000000, -100, 20000000),
                        new Vector3d(1, 1, 1));
        var normalized = new Pose3d(logical);
        var com = new Vector3d(20000005, -98, 20000007);
        normalized.position().set(logical.transformPosition(new Vector3d(com)));
        normalized.rotationPoint().set(com);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.put("pose", SableNBTUtils.writePose3d(normalized));
        var uuid = UUID.randomUUID();
        var data = new SubLevelData(uuid, new BoundingBox3d(), logical, List.of(), tag);
        var decoded = ShipRecords.serializedPose(data);
        assertEquals(normalized.position(), decoded.position());
        assertEquals(com, decoded.rotationPoint());
        assertNotEquals(data.pose().position(), decoded.position());
        var point = new Vector3d(20000003, -99, 20000002);
        assertTrue(
                logical.transformPosition(new Vector3d(point))
                                .distance(decoded.transformPosition(new Vector3d(point)))
                        < 1e-8);
        var saved = ShipSave.fresh(false);
        var observer = new ShipObserver(saved);
        observer.added(
                uuid,
                () ->
                        new ShipObserver.Sample(
                                ShipSaveTest.DIMENSION,
                                Optional.empty(),
                                new ShipRecord.OnSlot(0)));
        var place = ShipRecords.chartPlace(decoded, 119);
        observer.serialized(
                uuid,
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION,
                        Optional.empty(),
                        new ShipRecord.OnSlot(0, Optional.of(place))));
        observer.removed(uuid, false);
        observer.refresh();
        assertEquals(place, ((ShipRecord.OnSlot) saved.find(uuid).state()).place().orElseThrow());
        var loaded = ShipSave.load(ShipSaveTest.tag(saved), null);
        assertEquals(saved.list(), loaded.list());
        // Adoption/periodic slot reads must not erase the final material anchor on return.
        observer.added(
                uuid,
                () ->
                        new ShipObserver.Sample(
                                ShipSaveTest.DIMENSION,
                                Optional.empty(),
                                new ShipRecord.OnSlot(0)));
        assertEquals(
                119, ((ShipRecord.OnSlot) saved.find(uuid).state()).place().orElseThrow().tick());
        var q = place.attitude();
        q.identity();
        assertNotEquals(q, place.attitude());
    }

    @Test
    void failedPointerPublicationKeepsOldAddressAndSuccessfulMovesRoundtrip() {
        var saved = ShipSave.fresh(false);
        var uuid = UUID.randomUUID();
        saved.add(uuid, ShipSaveTest.DIMENSION, Optional.of("Starter"), new ShipRecord.OnSlot(0));
        saved.pointerSaved(uuid, null);
        assertTrue(saved.find(uuid).locator().isEmpty());
        var old = new ShipRecord.Locator(1, 2, (short) 3, (short) 4);
        var next = new ShipRecord.Locator(-6, 7, (short) 8, (short) 9);
        var trace = new ArrayList<String>();
        trace.add("serialized");
        trace.add("payload-write");
        saved.pointerSaved(uuid, old);
        trace.add("old-pointer");
        saved.pointerSaved(uuid, null);
        assertEquals(old, saved.find(uuid).locator().orElseThrow());
        saved.pointerSaved(uuid, next);
        trace.add("new-pointer");
        trace.add("flush");
        var restored = ShipSave.load(ShipSaveTest.tag(saved), null);
        trace.add("registry-save");
        assertEquals(next, restored.find(uuid).locator().orElseThrow());
        assertEquals(
                List.of(
                        "serialized",
                        "payload-write",
                        "old-pointer",
                        "new-pointer",
                        "flush",
                        "registry-save"),
                trace);
        var oldFormat = ShipSaveTest.tag(saved);
        oldFormat.putInt("format", 1);
        assertThrows(IllegalArgumentException.class, () -> ShipSave.load(oldFormat, null));
    }

    @Test
    void hooksGuardFailuresAndNeverRequestUnloadOrInventPointers() throws Exception {
        var write = Sources.text("SableShipWriteMixin");
        assertTrue(write.contains("shift = At.Shift.AFTER"));
        assertTrue(write.contains("starfold$written = false"));
        assertTrue(write.contains("starfold$written = true"));
        var storage = Sources.text("SableShipStorageMixin");
        assertTrue(
                storage.contains(
                        "pointer != null && (!pointer.equals(old) || ((SableShipStorageAccess) storage).starfold$writeSucceeded())"));
        assertTrue(storage.contains("original.call(storage); starfold$flushed = true;"));
        assertTrue(storage.contains("starfold$flushed = false"));
        assertTrue(
                storage.contains(
                        "if (starfold$flushed && PlanetLevels.is(level)) level.getDataStorage().save()"));
        var records = Sources.text("ShipRecords");
        assertTrue(records.contains("data.fullTag().getCompound(\"pose\")"));
        assertTrue(records.contains("observer.serialized(data.uuid(), sample(level, hull, pose))"));
        assertFalse(records.contains("data.pose()"));
        for (var text : List.of(write, storage, records))
            assertFalse(text.contains("moveToUnloaded("));
        for (var text : List.of(write, records)) assertFalse(text.contains("snatchAndLoad("));
        // The storage mixin intercepts snatch; its hooks still must not request one.
        assertFalse(storage.contains(".snatchAndLoad("));
        assertFalse(storage.contains("@Shadow public abstract void snatchAndLoad"));
        var selection = Sources.text("RendererCompatibility");
        for (var name :
                List.of(
                        "SableShipWriteMixin",
                        "SableShipStorageMixin",
                        "SableShipSerializationMixin"))
            assertTrue(selection.contains("dev.starfold.mixin." + name));
    }
}
