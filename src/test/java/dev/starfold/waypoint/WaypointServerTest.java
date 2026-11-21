package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class WaypointServerTest {
    private static Waypoint marker(int planet, double x) {
        return new Waypoint(
                Waypoint.LAUNCH, planet, new PlanetShape.Pose(Face.TOP, new Point(x, 30, 500)));
    }

    @Test
    void unstableFirstTicksRetryTheSameHullAndSuccessfulLaunchIsRecordedOnlyOnce() {
        var helm = new WaypointServer.HelmLaunch();
        var body = new Object();
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        var marker = marker(0, 100);
        var writes = new AtomicInteger();
        Runnable create =
                () -> {
                    writes.incrementAndGet();
                    save.launch(id, marker);
                };
        assertFalse(helm.record(body, .1, 1, create));
        assertFalse(helm.record(body, Double.NaN, 0, create));
        assertFalse(helm.record(body, 3, 0, create));
        assertTrue(save.launches(id).isEmpty());
        assertEquals(0, writes.get());
        assertTrue(helm.record(body, .1, .1, create));
        assertEquals(marker, save.active(id, 0));
        save.setDirty(false);
        for (int tick = 0; tick < 40; tick++) assertFalse(helm.record(body, .1, .1, create));
        assertEquals(1, writes.get());
        assertFalse(save.isDirty());
    }

    @Test
    void failedSaveDoesNotConsumeTheHullAndRetriesOnTheNextTick() {
        var helm = new WaypointServer.HelmLaunch();
        var body = new Object();
        var attempts = new AtomicInteger();
        Runnable create =
                () -> {
                    if (attempts.incrementAndGet() == 1)
                        throw new IllegalStateException("save unavailable");
                };
        assertThrows(IllegalStateException.class, () -> helm.record(body, .1, .1, create));
        assertTrue(helm.record(body, .1, .1, create));
        assertFalse(helm.record(body, .1, .1, create));
        assertEquals(2, attempts.get());
    }

    @Test
    void switchingToAnUnsettledHullThenBackStartsANewHelmSession() {
        var helm = new WaypointServer.HelmLaunch();
        var a = new Object();
        var b = new Object();
        var writes = new AtomicInteger();
        assertTrue(helm.record(a, .1, .1, writes::incrementAndGet));
        assertFalse(helm.record(b, .1, 1, writes::incrementAndGet));
        assertTrue(helm.record(a, .1, .1, writes::incrementAndGet));
        assertFalse(helm.record(a, .1, .1, writes::incrementAndGet));
        assertEquals(2, writes.get());
    }

    @Test
    void retakingHelmUpdatesThisPlanetsLaunchAndPreservesOtherPlanetsAndManualSelection() {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        var a = marker(0, 100);
        var b = marker(1, 200);
        var replacement = marker(1, 300);
        var manual = new Waypoint("base", 1, b.pose());
        save.launch(id, a);
        save.launch(id, b);
        save.add(id, manual);
        save.select(id, manual.name());
        var body = new Object();
        var helm = new WaypointServer.HelmLaunch();
        assertFalse(helm.record(body, .1, 1, () -> save.launch(id, replacement)));
        assertEquals(b, save.launches(id).get(1));
        assertTrue(helm.record(body, .1, .1, () -> save.launch(id, replacement)));
        assertEquals(replacement, save.launches(id).get(1));
        assertEquals(a, save.launches(id).get(0));
        assertEquals(manual, save.active(id, 1));
        // Leaving the helm removes its session; retaking the same hull records a new site.
        var retaken = new WaypointServer.HelmLaunch();
        assertTrue(retaken.record(body, .1, .1, () -> save.launch(id, b)));
        assertEquals(b, save.launches(id).get(1));
        save.clear(id);
        assertEquals(b, save.active(id, 1));
    }
}
