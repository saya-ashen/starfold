package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpaceSessionsTest {
    @AfterEach
    void clear() { SpaceSessions.clear(); }

    @Test
    void repeatedFrameNotificationsRetainViewButMaskChangeRotatesSession() {
        var first = SpaceSessions.view(null, null, GroundSpace.DEFAULT_MASK);
        assertNotEquals(0, first.packet().session());
        assertSame(first, SpaceSessions.view(null, null, GroundSpace.DEFAULT_MASK));
        var changed = SpaceSessions.view(null, null, new GroundSpace.Config(7, 1234));
        assertNotEquals(first.packet().session(), changed.packet().session());
        assertSame(changed, SpaceSessions.view(null, null, changed.packet().mask()));
        assertEquals(1, SpaceSessions.views.size());
    }

    @Test
    void logoutRemovesIntentsAndObserverAndNextViewGetsANewSession() {
        var old = SpaceSessions.view(null, null, GroundSpace.DEFAULT_MASK);
        SpaceSessions.observers.put(null, null);
        SpaceSessions.wishes.put(null, new SpaceSessions.Wish(null, 1, 1, 2, 3, 100));
        SpaceSessions.targets.put(null, new SpaceSessions.Target(null, null, null, null));
        SpaceSessions.forget(null);
        assertTrue(SpaceSessions.observers.isEmpty());
        assertTrue(SpaceSessions.wishes.isEmpty());
        assertTrue(SpaceSessions.targets.isEmpty());
        assertTrue(SpaceSessions.views.isEmpty());
        assertNotEquals(old.packet().session(), SpaceSessions.view(null, null, GroundSpace.DEFAULT_MASK).packet().session());
    }

    @Test
    void stopClearsEveryTransientMapAndUnloadDropsLevelOwnedEntries() {
        SpaceSessions.view(null, null, GroundSpace.DEFAULT_MASK);
        SpaceSessions.observers.put(null, null);
        SpaceSessions.targets.put(null, new SpaceSessions.Target(null, null, null, null));
        SpaceSessions.weightless.put(null, null);
        SpaceSessions.release(null);
        assertEmpty();
        SpaceSessions.view(null, null, GroundSpace.DEFAULT_MASK);
        SpaceSessions.observers.put(null, null);
        SpaceSessions.targets.put(null, new SpaceSessions.Target(null, null, null, null));
        SpaceSessions.wishes.put(null, new SpaceSessions.Wish(null, 1, 1, 2, 3, 100));
        SpaceSessions.weightless.put(null, null);
        SpaceSessions.clear();
        assertEmpty();
    }

    private static void assertEmpty() {
        assertTrue(SpaceSessions.observers.isEmpty());
        assertTrue(SpaceSessions.views.isEmpty());
        assertTrue(SpaceSessions.wishes.isEmpty());
        assertTrue(SpaceSessions.targets.isEmpty());
        assertTrue(SpaceSessions.weightless.isEmpty());
    }
}
