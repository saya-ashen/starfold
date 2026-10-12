package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.sky.Space;
import dev.starfold.sky.SpaceTest;
import dev.starfold.space.SpaceServer;

import org.junit.jupiter.api.Test;

class ArrivalStateTest {
    private ArrivalState step(
            ArrivalState s,
            double h,
            double closing,
            boolean start,
            boolean backstop,
            boolean abort,
            boolean chart) {
        return s.step(1, "ship", h, closing, start, backstop, abort, chart, true);
    }

    private ArrivalState notice() {
        return step(ArrivalState.empty(), 5000, 300, true, false, false, false);
    }

    private ArrivalState latched() {
        var s = notice();
        for (int i = 0; i < 80; i++) s = step(s, 4000, 300, true, false, false, false);
        return s;
    }

    @Test
    void noticeAndBlendTimers() {
        var s = notice();
        assertEquals(4, s.seconds());
        assertFalse(s.latched());
        for (int i = 0; i < 60; i++) s = step(s, 4500, 300, true, false, false, false);
        assertEquals(1, s.seconds());
        for (int i = 0; i < 20; i++) s = step(s, 4000, 300, true, false, false, false);
        assertEquals(ArrivalState.Phase.TURN_IN, s.phase());
        assertEquals(0, s.blend());
        for (int i = 0; i < 20; i++) s = step(s, 3900, 150, false, false, false, false);
        assertEquals(ArrivalState.Phase.ARRIVAL, s.phase());
        assertEquals(1, s.blend());
    }

    @Test
    void reducedClosingAndMissCannotFlicker() {
        var s = latched();
        for (int i = 0; i < 200; i++) {
            s = step(s, 3900, 0, false, false, false, false);
            assertTrue(s.latched());
        }
    }

    @Test
    void withdrawnNoticeHasCooldown() {
        var s = step(notice(), 5000, 0, false, false, false, false);
        assertEquals(ArrivalState.Phase.CRUISE, s.phase());
        for (int i = 0; i < 60; i++) {
            s = step(s, 5000, 300, true, false, false, false);
            assertEquals(ArrivalState.Phase.CRUISE, s.phase());
        }
        assertEquals(
                ArrivalState.Phase.NOTICE, step(s, 5000, 300, true, false, false, false).phase());
    }

    @Test
    void backstopDuringNoticeEntersImmediately() {
        assertTrue(step(notice(), 1700, 0, false, true, false, false).latched());
    }

    @Test
    void abortAtBothLatchedPhasesSuppressesBackstopToo() {
        var s = latched();
        for (int i = 0; i < 2; i++) {
            var a = step(s, 1700, 300, true, true, true, false);
            assertFalse(a.latched());
            for (int j = 0; j < 60; j++) {
                a = step(a, 1700, 300, true, true, false, false);
                assertFalse(a.latched());
            }
            s = s.restored();
        }
    }

    @Test
    void leavingRequiresContinuousThreeSecondsOrEntryHeightPlus500() {
        var s = latched();
        for (int i = 0; i < 59; i++) s = step(s, 4100, -1, false, false, false, false);
        assertTrue(s.latched());
        s = step(s, 4100, 0, false, false, false, false);
        assertEquals(0, s.leaving());
        for (int i = 0; i < 60; i++) s = step(s, 4100, -1, false, false, false, false);
        assertFalse(s.latched());
        assertFalse(step(latched(), 4501, 10, false, false, false, false).latched());
    }

    @Test
    void chartAndIdentityReset() {
        assertEquals(ArrivalState.empty(), step(latched(), 1500, 100, true, true, false, true));
        assertFalse(latched().step(2, "ship", 5000, 0, false, false, false, false, true).latched());
        assertFalse(
                latched().step(1, "other", 5000, 0, false, false, false, false, true).latched());
    }

    @Test
    void serverSaveRestoresLatchedIdentity() throws Exception {
        var server = new SpaceServer(Space.Frame.IDENTITY);
        var field = SpaceServer.class.getDeclaredField("arrivalState");
        field.setAccessible(true);
        for (var state : new ArrivalState[] {notice(), latched()}) {
            field.set(server, state);
            var saved = server.save(new net.minecraft.nbt.CompoundTag(), null);
            var restored = (ArrivalState) field.get(SpaceServer.load(saved, null));
            assertEquals(state.latched(), restored.latched());
            if (state.latched()) {
                assertEquals(ArrivalState.Phase.ARRIVAL, restored.phase());
                assertEquals(state.planet(), restored.planet());
                assertEquals(state.lead(), restored.lead());
                assertEquals(state.entryHeight(), restored.entryHeight());
            }
        }
    }

    @Test
    void blendProjectionKeepsCruiseAndRadialEndpoints() {
        var push = new dev.starfold.geometry.BentStripGeometry.Point(3, 2, 4);
        var toward = new dev.starfold.geometry.BentStripGeometry.Point(0, -1, 0);
        assertEquals(push, SpaceServer.blendPush(push, toward, 0));
        assertEquals(
                new dev.starfold.geometry.BentStripGeometry.Point(1.5, 2, 2),
                SpaceServer.blendPush(push, toward, .5));
        SpaceTest.point(
                SpaceServer.arrivalPush(push, toward, true),
                SpaceServer.blendPush(push, toward, 1));
    }

    @Test
    void restoreAndTurnEasing() {
        assertEquals(ArrivalState.Phase.CRUISE, notice().restored().phase());
        assertEquals(ArrivalState.Phase.ARRIVAL, latched().restored().phase());
        assertEquals(0, ArrivalState.turnScale(0, 90));
        assertEquals(.5, ArrivalState.turnScale(.5, 90));
        assertEquals(1, ArrivalState.turnScale(1, 90));
        assertEquals(.5, ArrivalState.turnScale(1, 5));
    }
}
