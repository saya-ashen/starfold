package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.BodyId;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGuidance;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundNavigationSave;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.space.SpaceServer;
import dev.starfold.universe.SystemSkyCodec;
import dev.starfold.waypoint.Waypoint;
import dev.starfold.waypoint.WaypointCodec;
import dev.starfold.waypoint.WaypointSave;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class BodyIdSaveTest {
    private static GroundEphemeris.Model model() {
        var source = PlanetSystem.model();
        return new GroundEphemeris.Model(
                java.util.stream.IntStream.range(0, 3)
                        .mapToObj(n -> source.bodies().get(n).identified(new BodyId(17, 90 + n)))
                        .toList());
    }

    private static GroundEphemeris.Model reordered() {
        var source = model();
        var a = source.body(0);
        var b = source.body(1);
        return new GroundEphemeris.Model(
                List.of(
                        source.bodies().getFirst(),
                        new GroundEphemeris.Body(
                                b.motion(),
                                b.radius(),
                                b.light(),
                                0,
                                b.reach(),
                                b.appearance(),
                                b.id()),
                        new GroundEphemeris.Body(
                                a.motion(),
                                a.radius(),
                                a.light(),
                                1,
                                a.reach(),
                                a.appearance(),
                                a.id())));
    }

    private static GroundDynamics.State state() {
        return GroundDynamics.local(
                40,
                0,
                new PlanetShape.Pose(Face.TOP, new Point(200, 40, 200)),
                GroundEphemeris.ZERO,
                new Quaterniond(),
                false,
                new GroundGuidance.Options(1, true, false));
    }

    @Test
    void identityValidationAndLookupIncludeSunsAndRejectUnknownOrNonSurfaceIds() {
        var model = model();
        for (int n = 0; n < 3; n++) {
            var id = model.bodies().get(n).id();
            assertEquals(id, BodyId.read(id.tag()));
            assertEquals(n, model.index(id));
        }
        assertEquals(0, model.slot(new BodyId(17, 91)));
        assertThrows(IllegalArgumentException.class, () -> model.slot(new BodyId(17, 90)));
        assertThrows(IllegalArgumentException.class, () -> model.index(new BodyId(18, 90)));
        assertThrows(IllegalArgumentException.class, () -> new BodyId(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BodyId(0, -1));
        assertThrows(IllegalArgumentException.class, () -> BodyId.read(new CompoundTag()));
    }

    @Test
    void savedCatalogAndParentIdsRoundTripInNbtAndJsonWhileWireStillUsesInts() {
        var model = model();
        var tag =
                (CompoundTag)
                        SystemSkyCodec.SAVED_MODEL.encodeStart(NbtOps.INSTANCE, model).getOrThrow();
        assertEquals(
                model.bodies().getFirst().id(),
                BodyId.read(
                        tag.getList("bodies", 10)
                                .getCompound(1)
                                .getCompound("motion")
                                .getCompound("parent")));
        assertEquals(model, SystemSkyCodec.SAVED_MODEL.parse(NbtOps.INSTANCE, tag).getOrThrow());
        var json =
                SystemSkyCodec.SAVED_MODEL
                        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, model)
                        .getOrThrow();
        assertEquals(
                model,
                SystemSkyCodec.SAVED_MODEL
                        .parse(com.mojang.serialization.JsonOps.INSTANCE, json)
                        .getOrThrow());
        var wire = GroundEphemerisSave.modelTag(model);
        assertFalse(wire.getList("bodies", 10).getCompound(0).contains("id"));
        assertTrue(
                wire.getList("bodies", 10)
                        .getCompound(1)
                        .getCompound("motion")
                        .contains("parent", 3));
        var clock = new GroundEphemerisSave(model, 40);
        assertEquals(
                model, GroundEphemerisSave.load(clock.save(new CompoundTag(), null), null).model);
    }

    @Test
    void malformedAndOldSavedCatalogsAreRejected() {
        var tag =
                (CompoundTag)
                        SystemSkyCodec.SAVED_MODEL
                                .encodeStart(NbtOps.INSTANCE, model())
                                .getOrThrow();
        for (int format : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 10}) {
            var old = tag.copy();
            old.putInt("format", format);
            assertTrue(SystemSkyCodec.SAVED_MODEL.parse(NbtOps.INSTANCE, old).isError());
        }
        var missing = tag.copy();
        missing.getList("bodies", 10).getCompound(0).remove("id");
        assertTrue(SystemSkyCodec.SAVED_MODEL.parse(NbtOps.INSTANCE, missing).isError());
        var duplicate = tag.copy();
        duplicate.getList("bodies", 10).getCompound(1).put("id", new BodyId(17, 90).tag());
        assertTrue(SystemSkyCodec.SAVED_MODEL.parse(NbtOps.INSTANCE, duplicate).isError());
        var parent = tag.copy();
        parent.getList("bodies", 10)
                .getCompound(1)
                .getCompound("motion")
                .put("parent", new BodyId(99, 1).tag());
        assertTrue(SystemSkyCodec.SAVED_MODEL.parse(NbtOps.INSTANCE, parent).isError());
    }

    @Test
    void navigationDiskIdsResolveAfterCatalogAndSlotReorderingWithoutChangingWire() {
        var state = state();
        var tag = GroundNavigationSave.encode(state, model());
        assertEquals(model().id(0), BodyId.read(tag.getCompound("planet")));
        assertEquals(model().id(1), BodyId.read(tag.getCompound("guidance").getCompound("target")));
        assertEquals(state, GroundNavigationSave.decode(tag, model()));
        var restored = GroundNavigationSave.decode(tag, reordered());
        assertEquals(1, restored.planet());
        assertEquals(0, restored.guidance().target());
        assertEquals(state.position(), restored.position());
        assertEquals(state, GroundNavigationSave.decode(GroundNavigationSave.encode(state)));
        var id = UUID.randomUUID();
        var empty = new GroundNavigationSave().save(new CompoundTag(), null);
        var save = GroundNavigationSave.load(empty, null, BodyIdSaveTest::model);
        save.put(id, state);
        var disk = save.save(new CompoundTag(), null);
        assertEquals(
                restored,
                GroundNavigationSave.load(disk, null, BodyIdSaveTest::reordered).find(id));
        disk.putInt("format", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundNavigationSave.load(disk, null, BodyIdSaveTest::model));
    }

    @Test
    void waypointAndRecentLaunchUseIdsAndStillSendIntegerWireMarkers() {
        var marker = new Waypoint("base", 0, state().local());
        var tag = WaypointCodec.encode(marker, model());
        assertEquals(marker, WaypointCodec.decode(tag, model()));
        assertEquals(1, WaypointCodec.decode(tag, reordered()).planet());
        assertTrue(WaypointCodec.encode(marker).contains("planet", 3));
        var id = UUID.randomUUID();
        var save =
                WaypointSave.load(
                        new WaypointSave().save(new CompoundTag(), null),
                        null,
                        BodyIdSaveTest::model);
        save.add(id, marker);
        save.launch(id, new Waypoint(Waypoint.LAUNCH, 0, marker.pose()));
        var disk = save.save(new CompoundTag(), null);
        assertEquals(
                model().id(0),
                BodyId.read(
                        disk.getCompound("players")
                                .getCompound(id.toString())
                                .getCompound("recentLaunch")));
        var loaded = WaypointSave.load(disk, null, BodyIdSaveTest::reordered);
        assertEquals(1, loaded.active(id).planet());
        assertEquals(1, loaded.list(id).get("base").planet());
        disk.putInt("format", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> WaypointSave.load(disk, null, BodyIdSaveTest::model));
    }

    @Test
    void spaceFrameUsesIdsAndRejectsUnversionedSaves() {
        var save = new SpaceServer(Space.Frame.IDENTITY, BodyIdSaveTest::model);
        var disk = save.save(new CompoundTag(), null);
        assertEquals(model().id(0), BodyId.read(disk.getCompound("planet")));
        assertEquals(
                disk,
                SpaceServer.load(disk, null, BodyIdSaveTest::reordered)
                        .save(new CompoundTag(), null));
        disk.remove("format");
        assertThrows(
                IllegalArgumentException.class,
                () -> SpaceServer.load(disk, null, BodyIdSaveTest::model));
    }
}
