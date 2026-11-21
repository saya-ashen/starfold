package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.util.SableNBTUtils;

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
        var observer = new ShipObserver(saved, slot -> 0);
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
        saved.add(
                0, uuid, ShipSaveTest.DIMENSION, Optional.of("Starter"), new ShipRecord.OnSlot(0));
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
}
