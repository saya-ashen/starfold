package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.space.SpaceServer;

import org.junit.jupiter.api.Test;

class DepartureTest {
    private ArrivalState depart() {
        return ArrivalState.departed(1, "ship");
    }

    private ArrivalState step(ArrivalState s, double h, boolean backstop) {
        return s.step(1, "ship", h, 300, true, backstop, false, false, true);
    }

    @Test
    void pointingThroughDepartureDoesNotStartNotice() {
        var s = depart();
        for (int i = 0; i < 140; i++) {
            s = step(s, 2048, false);
            assertEquals(ArrivalState.Phase.CRUISE, s.phase());
        }
    }

    @Test
    void backstopStillFires() {
        assertTrue(step(depart(), 1536 + 299, true).latched());
    }

    @Test
    void explicitDepartureLockRestoresNormalRule() {
        assertEquals(
                ArrivalState.Phase.NOTICE, step(depart().lock(1, "ship"), 2048, false).phase());
        assertEquals(
                ArrivalState.Phase.CRUISE, step(depart().lock(0, "ship"), 2048, false).phase());
    }

    @Test
    void otherPlanetMayNoticeWithoutForgettingDeparture() {
        var s = depart().step(2, "ship", 2500, 300, true, false, false, false, true);
        assertEquals(ArrivalState.Phase.NOTICE, s.phase());
        assertEquals(depart().departure(), s.departure());
        assertEquals(ArrivalState.Phase.CRUISE, step(s, 2500, false).phase());
        assertEquals(
                ArrivalState.Phase.NOTICE,
                depart().step(1, "other", 2500, 300, true, false, false, false, true).phase());
    }

    @Test
    void anyChartEntryClearsDeparture() {
        var s = depart().step(2, "ship", 1500, 0, false, false, false, true, true);
        assertNull(s.departure());
        assertEquals(ArrivalState.Phase.NOTICE, step(s, 2500, false).phase());
    }

    @Test
    void neitherSixtySecondsNorClimbingBeyondOldGraceEndsSuppression() {
        var s = depart();
        for (int i = 0; i < 2400; i++) {
            s = step(s, i < 1200 ? 2500 : 2048 + 1500 + 10000, false);
            assertEquals(ArrivalState.Phase.CRUISE, s.phase());
            assertEquals(depart().departure(), s.departure());
        }
        assertEquals(ArrivalState.Phase.CRUISE, step(s, 2500, false).phase());
    }

    @Test
    void lockedTargetAloneMayNoticeButBackstopIgnoresLock() {
        var s = depart();
        assertEquals(
                ArrivalState.Phase.CRUISE,
                s.step(2, "ship", 2500, 300, true, false, false, false, true, 3).phase());
        assertEquals(
                ArrivalState.Phase.NOTICE,
                s.step(3, "ship", 2500, 300, true, false, false, false, true, 3).phase());
        assertEquals(
                ArrivalState.Phase.NOTICE,
                s.step(2, "ship", 2500, 300, true, false, false, false, true, -1).phase());
        for (int planet : new int[] {1, 2})
            assertTrue(
                    s.step(planet, "ship", 1536 + 299, 0, true, true, false, false, true, 3)
                            .latched());
        assertEquals(
                ArrivalState.Phase.NOTICE,
                s.lock(1, "ship")
                        .step(1, "ship", 2500, 300, true, false, false, false, true, 1)
                        .phase());
    }

    @Test
    void lockChangeDoesNotBreakExistingLatch() {
        var s = depart().step(2, "ship", 1536 + 299, 0, false, true, false, false, true, -1);
        for (int i = 0; i < 100; i++)
            s = s.step(2, "ship", 1536 + 299, 0, false, false, false, false, true, 3);
        assertTrue(s.latched());
        assertEquals(depart().departure(), s.departure());
    }

    @Test
    void saveReloadKeepsFlightLongDepartureAndLatchedIdentity() throws Exception {
        var server = new SpaceServer(Space.Frame.IDENTITY);
        var field = SpaceServer.class.getDeclaredField("arrivalState");
        field.setAccessible(true);
        var s = depart();
        for (int i = 0; i < 1300; i++) s = step(s, 2048 + 2000, false);
        for (var state :
                new ArrivalState[] {
                    s, s.step(2, "ship", 1536 + 299, 0, false, true, false, false, true)
                }) {
            field.set(server, state);
            var tag = server.save(new net.minecraft.nbt.CompoundTag(), null);
            var restored = (ArrivalState) field.get(SpaceServer.load(tag, null));
            assertEquals(state.departure(), state.restored().departure());
            assertEquals(state.departure(), restored.departure());
            if (state.latched()) {
                assertTrue(restored.latched());
                assertEquals(2, restored.planet());
            } else assertEquals(ArrivalState.Phase.CRUISE, step(restored, 2500, false).phase());
            tag.putInt(
                    "departureTicks",
                    1); // Old dev.79 saves migrate without a remaining-time expiry.
            assertNotNull(((ArrivalState) field.get(SpaceServer.load(tag, null))).departure());
        }
    }

    @Test
    void occlusionGeometry() {
        var ship = new Point(0, 0, 0);
        var target = new Point(0, 0, 100);
        assertTrue(SpaceNavigation.occludes(ship, target, new Point(0, 0, 50), 10));
        assertTrue(SpaceNavigation.occludes(ship, target, new Point(10, 0, 50), 10));
        assertFalse(SpaceNavigation.occludes(ship, target, new Point(10.01, 0, 50), 10));
        assertFalse(SpaceNavigation.occludes(ship, target, new Point(0, 0, -50), 10));
        assertFalse(SpaceNavigation.occludes(ship, target, new Point(0, 0, 150), 10));
        assertFalse(SpaceNavigation.occludes(ship, target, target, 10));
        assertFalse(SpaceNavigation.occludes(ship, ship, new Point(0, 0, 10), 10));
        assertFalse(SpaceNavigation.occludes(ship, target, new Point(90, 0, 90), 100));
    }

    @Test
    void bilingualHintAndWiring() throws Exception {
        for (String lang : new String[] {"en_us", "zh_cn"}) {
            var json = FlightPermitTest.json("assets/starfold/lang/" + lang + ".json");
            assertEquals(
                    lang.equals("en_us") ? "blocked by %s · go around" : "被 %s 遮挡 · 绕行",
                    json.get("hud.starfold.instrument_blocked").getAsString());
        }
        assertTrue(
                Sources.text("SableFrames")
                        .contains(
                                "ShipHost.get().hullDeparted(level,planet,body.getUniqueId().toString())"));
        assertTrue(
                Sources.text("SableFrames")
                        .contains(
                                "ShipHost.get().hullEnteredChart(level,body.getUniqueId().toString())"));
        var server = Sources.text("SpaceServer");
        assertTrue(
                server.contains(
                        "target!=null&&validTarget(player,target))lockedPlanet=target.planet()"));
        assertTrue(server.contains("abort,false,remaining<.1,lockedPlanet)"));
        assertTrue(
                server.contains(
                        "value.arrivalState=s.step(s.planet(),id,0,0,false,false,false,true,true)"));
        var client = Sources.text("SpaceNavigationClient");
        assertTrue(
                client.contains(
                        "tier>=2&&t.planet()==locked?SpaceNavigation.blocker(model,pivot,locked,tick):-1"));
        var widgets = Sources.text("FlightWidgets");
        assertTrue(widgets.contains("t.blocker()>=0&&i%4>=2"));
    }

    @Test
    void longerCaptionHasSeparateBoundedRow() {
        for (int[] size : new int[][] {{1280, 720}, {1920, 1080}, {854, 480}})
            for (int scale : new int[] {2, 3, 4}) {
                var l = HudLayout.physical(size[0], size[1], scale);
                var row = HudLayout.occlusion(l);
                assertTrue(row.x() >= 0 && row.right() <= l.width());
                assertTrue(row.y() > l.view().bottom());
                assertTrue(row.bottom() < l.height() - 82);
                assertTrue(row.width() >= 180);
            }
    }
}
