package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

class ShipObserverTest {
    static final class FakeHull {
        final UUID uuid = UUID.randomUUID();
        ShipObserver.Sample sample =
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION, Optional.of("Starter"), new ShipRecord.OnSlot(0));
        int reads;

        ShipObserver.Sample sample() {
            reads++;
            return sample;
        }
    }

    @Test
    void addedRefreshUnloadedAndRemovedPreserveTheCorrectIdentity() {
        var saved = ShipSave.fresh(false);
        var observer = new ShipObserver(saved, slot -> 0);
        var hull = new FakeHull();
        observer.added(hull.uuid, hull::sample);
        var first = saved.find(hull.uuid).id();
        assertEquals(new ShipId(0, 0), first);
        assertEquals(1, hull.reads);
        assertTrue(saved.find(hull.uuid).locator().isEmpty());
        hull.sample =
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION,
                        Optional.of("Renamed"),
                        new ShipRecord.InSpace(ShipSaveTest.place()));
        assertInstanceOf(ShipRecord.OnSlot.class, saved.find(hull.uuid).state());
        observer.refresh();
        assertEquals(hull.sample.state(), saved.find(hull.uuid).state());
        assertEquals(hull.sample.name(), saved.find(hull.uuid).name());
        assertEquals(first, saved.find(hull.uuid).id());
        observer.removed(hull.uuid, false);
        int reads = hull.reads;
        observer.refresh();
        assertEquals(reads, hull.reads);
        assertEquals(first, saved.find(hull.uuid).id());
        var reloaded = ShipSave.load(ShipSaveTest.tag(saved), null);
        var nextObserver = new ShipObserver(reloaded, slot -> 0);
        hull.sample =
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION, Optional.empty(), new ShipRecord.OnSlot(1));
        nextObserver.added(hull.uuid, hull::sample);
        assertEquals(first, reloaded.find(hull.uuid).id());
        assertEquals(new ShipRecord.OnSlot(1), reloaded.find(hull.uuid).state());
        nextObserver.removed(hull.uuid, true);
        assertNull(reloaded.find(hull.uuid));
        nextObserver.added(hull.uuid, hull::sample);
        assertEquals(new ShipId(0, 1), reloaded.find(hull.uuid).id());
    }

    @Test
    void onlyLiveSourcesAreRefreshedAndSnapshotsDoNotAlias() {
        var saved = ShipSave.fresh(false);
        var observer = new ShipObserver(saved, slot -> 0);
        var a = new FakeHull();
        var b = new FakeHull();
        observer.added(a.uuid, a::sample);
        var list = saved.list();
        observer.added(b.uuid, b::sample);
        assertEquals(1, list.size());
        observer.removed(a.uuid, false);
        observer.refresh();
        assertEquals(1, a.reads);
        assertEquals(2, b.reads);
        assertEquals(2, saved.list().size());
    }

    @Test
    void surfaceRegistrationUsesTheSurfaceBodyRegionNotTheSlot() {
        var model =
                new dev.starfold.chart.GroundEphemeris.Model(
                        ShipParkingTest.model().bodies().stream()
                                .map(
                                        body ->
                                                body.slot() == 0
                                                        ? body.identified(
                                                                new dev.starfold.chart.BodyId(9, 4))
                                                        : body)
                                .toList());
        var saved = ShipSave.fresh(false);
        var observer = new ShipObserver(saved, slot -> model.id(slot).region());
        var hull = new FakeHull();
        observer.added(hull.uuid, hull::sample);
        assertEquals(new ShipId(9, 0), saved.find(hull.uuid).id());
    }

    @Test
    void spaceRegistrationUsesPlaceBodyRegionWithoutLookingUpASurface() {
        var saved = ShipSave.fresh(false);
        var observer =
                new ShipObserver(
                        saved,
                        slot -> {
                            throw new AssertionError("Surface lookup");
                        });
        var uuid = UUID.randomUUID();
        observer.serialized(
                uuid,
                new ShipObserver.Sample(
                        ShipSaveTest.DIMENSION,
                        Optional.empty(),
                        new ShipRecord.InSpace(ShipSaveTest.place())));
        assertEquals(new ShipId(7, 0), saved.find(uuid).id());
    }
}
