package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.planet.PlanetIdentity;

import org.junit.jupiter.api.Test;

class HudLayoutTest {
    @Test
    void noScopeShowsNothing() {
        for (var w : HudLayout.Widget.values()) {
            assertFalse(HudLayout.shows(0, w));
            assertFalse(HudLayout.shows(-1, w));
            assertTrue(HudLayout.shows(3, w));
        }
    }

    @Test
    void tiersAreCumulativeButNavigationAndPrecisionAreNotBasic() {
        for (var w : HudLayout.Widget.values()) {
            int minimum =
                    switch (w) {
                        case RETICLE, MODE, SPEED, CLEARANCE -> 1;
                        case SPEED_TAPE, CLOSING, TARGET, VELOCITY, PLANETS, BEARING -> 2;
                        case THRUST, LANDING_DETAIL -> 3;
                    };
            for (int tier = 0; tier <= 3; tier++)
                assertEquals(tier >= minimum, HudLayout.shows(tier, w), tier + " " + w);
        }
    }

    @Test
    void rectanglesStayOnScreenAndDoNotOverlap() {
        for (var size :
                new int[][] {
                    {1920, 1080, 2},
                    {1920, 1080, 3},
                    {1920, 1080, 4},
                    {854, 480, 2},
                    {854, 480, 3},
                    {854, 480, 4},
                    {1280, 720, 3}
                }) {
            var l = HudLayout.physical(size[0], size[1], size[2]);
            var rects =
                    java.util.List.of(
                            l.mode(),
                            l.view(),
                            l.speed(),
                            l.closing(),
                            l.thrust(),
                            l.bearing(),
                            l.centre());
            for (int i = 0; i < rects.size(); i++) {
                var r = rects.get(i);
                assertTrue(
                        r.x() >= 0
                                && r.y() >= 0
                                && r.right() <= l.width()
                                && r.bottom() <= l.height(),
                        r.toString());
                for (int j = i + 1; j < rects.size(); j++)
                    assertFalse(r.overlaps(rects.get(j)), r + " / " + rects.get(j));
            }
            assertTrue(l.thrust().y() > l.height() - 30);
            System.out.println(size[0] + "x" + size[1] + " / " + size[2] + ": " + l);
        }
    }

    @Test
    void targetNamesFitAtEveryScaleWithoutLosingCycleRank() {
        for (int[] size : new int[][] {{1920, 1080}, {854, 480}})
            for (int scale : new int[] {2, 3, 4}) {
                var l = HudLayout.physical(size[0], size[1], scale);
                java.util.function.ToIntFunction<String> width =
                        s -> s.codePoints().map(c -> c > 127 ? 9 : 6).sum();
                for (String name :
                        java.util.List.of(
                                "小行星",
                                "Asteroid",
                                "非常长的星球名称".repeat(8),
                                "a very long named destination ".repeat(4),
                                "🪐".repeat(40))) {
                    var label = PlanetIdentity.caption(7, name, 9, 12, l.mode().width(), width);
                    assertTrue(width.applyAsInt(label) <= l.mode().width(), label);
                    assertTrue(label.startsWith("H · ") && label.endsWith(" · 9/12"));
                    if (width.applyAsInt(name) > l.mode().width()) assertTrue(label.contains("…"));
                }
            }
    }

    @Test
    void tapeClampsAndUsesProvidedCaps() {
        assertEquals(.375, HudLayout.fill(300, 800));
        assertEquals(1, HudLayout.fill(800, 800));
        assertEquals(1, HudLayout.fill(900, 800));
        assertEquals(0, HudLayout.fill(-1, 800));
        assertEquals(0, HudLayout.fill(Double.NaN, 800));
        assertEquals(0, HudLayout.fill(1, 0));
        assertEquals(.5, HudLayout.fill(50, 100));
    }

    @Test
    void etaIsBoundedAndNeverPromisesArrivalWhenReceding() {
        assertEquals("01:01", HudLayout.eta(120.1, 2));
        assertEquals("00:00", HudLayout.eta(0, 2));
        assertEquals(">99:59", HudLayout.eta(6000, 1));
        for (double v : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals("—", HudLayout.eta(100, v));
        assertEquals("—", HudLayout.eta(Double.NaN, 2));
    }

    @Test
    void capsComeFromServerAndBothSidesGateLock() throws Exception {
        var client = Sources.text("SpaceNavigationClient");
        assertTrue(client.contains("flight.cruiseCap(),flight.boostCap()"));
        assertTrue(client.contains("HelmClient.tier()<2"));
        assertTrue(client.contains("hintedBody!=HelmClient.body()"));
        assertTrue(client.contains("VanillaGuiLayers.HOTBAR"));
        assertFalse(client.contains("VanillaGuiLayers.PLAYER_HEALTH"));
        var server = Sources.text("SpaceServer");
        assertTrue(server.contains("Thrusters.scopeTier(target.body())<2"));
        assertTrue(server.contains("Thrusters.scopeTier(body)>=2"));
    }
}
