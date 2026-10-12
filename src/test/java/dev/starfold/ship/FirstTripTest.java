package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.FlightPermitTest;
import dev.starfold.flight.FlightSnapshot;
import dev.starfold.flight.HudLayout;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.space.SpaceView;

import net.minecraft.world.level.GameType;

import org.junit.jupiter.api.Test;

class FirstTripTest {
    @Test
    void departureIsLastAndAllTargetsRemain() {
        var model = PlanetSystem.model();
        var at = model.at(0, 0).center();
        var order = SpaceNavigation.cycle(model, at, 0, 0);
        assertEquals(model.count(), order.size());
        assertEquals(0, order.getLast());
        assertNotEquals(0, order.getFirst());
        int locked = -1;
        for (int id : order) {
            locked = SpaceNavigation.next(model, at, 0, locked, 0);
            assertEquals(id, locked);
        }
        assertEquals(-1, SpaceNavigation.next(model, at, 0, locked, 0));
        assertEquals(order.getFirst(), SpaceNavigation.next(model, at, 0, 999, 0));
        assertEquals(
                SpaceView.nearest(model, at, 0, model.count()),
                SpaceNavigation.cycle(model, at, 0, -1));
    }

    @Test
    void unsafePlanetExitUsesSameWindowButSafeStairIsImmediate() {
        for (var mode : new GameType[] {GameType.SURVIVAL, GameType.ADVENTURE}) {
            var rule = new HelmExit();
            assertFalse(rule.leave(mode, false, false, 100));
            assertTrue(rule.leave(mode, false, false, 159));
            assertTrue(rule.leave(mode, false, true, 160));
            assertFalse(rule.leave(mode, true, true, 161));
        }
        assertTrue(new HelmExit().leave(GameType.CREATIVE, false, false, 0));
        var rule = new HelmExit();
        assertFalse(rule.leave(GameType.SURVIVAL, false, false, 0));
        assertFalse(rule.leave(GameType.SURVIVAL, false, false, 60));
    }

    @Test
    void footprintUsesEveryColumnAndFailsClosed() {
        assertEquals(
                HullLanding.Kind.LAND,
                HullLanding.survey(0, 0, 5, 5, (x, z) -> new HullLanding.Surface(100, false))
                        .kind());
        assertEquals(
                HullLanding.Kind.WATER,
                HullLanding.survey(
                                0,
                                0,
                                5,
                                5,
                                (x, z) -> new HullLanding.Surface(100, x == 1 && z == 1))
                        .kind());
        assertEquals(
                HullLanding.Kind.LAVA,
                HullLanding.survey(0, 0, 5, 5, (x, z) -> new HullLanding.Surface(100, true, true))
                        .kind());
        assertEquals(
                HullLanding.Kind.SLOPE,
                HullLanding.survey(
                                0,
                                0,
                                5,
                                5,
                                (x, z) -> new HullLanding.Surface(x == 1 ? 111 : 100, false))
                        .kind());
        assertEquals(
                HullLanding.Kind.LAND,
                HullLanding.survey(
                                0,
                                0,
                                5,
                                5,
                                (x, z) -> new HullLanding.Surface(x == 1 ? 110 : 100, false))
                        .kind());
        assertEquals(
                HullLanding.Kind.UNKNOWN,
                HullLanding.survey(0, 0, 5, 5, (x, z) -> new HullLanding.Surface(Double.NaN, false))
                        .kind());
    }

    @Test
    void preciseChartOnlyBelowTwoHundred() {
        for (int tier = 0; tier < 4; tier++)
            for (var mode : FlightSnapshot.Mode.values()) {
                assertEquals(
                        tier == 3 && mode == FlightSnapshot.Mode.CHART,
                        HullLanding.showSurface(tier, mode, 199.99));
                assertFalse(HullLanding.showSurface(tier, mode, 200));
                assertFalse(HullLanding.showSurface(tier, mode, Double.NaN));
            }
    }

    @Test
    void hoveringWithStairsIsStillNotLanded() {
        assertFalse(HullLanding.safeExit(5, true));
        assertFalse(HullLanding.safeExit(0, false));
        assertFalse(HullLanding.safeExit(Double.NaN, true));
        assertTrue(HullLanding.safeExit(0, true));
        assertTrue(HullLanding.safeExit(-.1, true));
        for (var kind :
                new HullLanding.Kind[] {
                    HullLanding.Kind.WATER, HullLanding.Kind.LAVA, HullLanding.Kind.UNKNOWN
                }) assertFalse(HullLanding.safeExit(0, true, kind));
    }

    @Test
    void landingDataRoundTripsInsideExistingHelmPacket() {
        for (var kind : HullLanding.Kind.values())
            for (boolean can : new boolean[] {false, true}) {
                var buf =
                        new net.minecraft.network.RegistryFriendlyByteBuf(
                                io.netty.buffer.Unpooled.buffer(),
                                net.minecraft.core.RegistryAccess.EMPTY);
                try {
                    var packet =
                            new HelmPackets.State(
                                    java.util.UUID.randomUUID(),
                                    net.minecraft.core.BlockPos.ZERO,
                                    net.minecraft.core.Direction.NORTH,
                                    true,
                                    true,
                                    0,
                                    false,
                                    0,
                                    0,
                                    0,
                                    new HullLanding.Site(kind, can));
                    HelmPackets.State.CODEC.encode(buf, packet);
                    assertEquals(packet, HelmPackets.State.CODEC.decode(buf));
                    assertEquals(0, buf.readableBytes());
                } finally {
                    buf.release();
                }
            }
    }

    @Test
    void surfaceRowsClearModeViewAndSurvivalBars() {
        for (int[] size : new int[][] {{1280, 720}, {1920, 1080}, {854, 480}})
            for (int scale : new int[] {2, 3, 4}) {
                var l = HudLayout.physical(size[0], size[1], scale);
                assertTrue(54 > l.view().bottom());
                assertTrue(65 + 9 < l.height() - 49);
                assertTrue(Math.min(180, l.width() - 32) >= 150);
            }
    }

    @Test
    void wireAndPlannerAreShared() throws Exception {
        var server = Sources.text("HelmServer");
        assertTrue(server.contains("HullLanding.site(s.player.serverLevel(),s.body)"));
        var stairs = Sources.text("Airstair");
        assertTrue(stairs.contains("plan(level,stair,body,at,true)"));
        assertTrue(stairs.contains("chart?plan(server,stair,body,at,gravity):null"));
        assertTrue(stairs.contains("if(!state.getFluidState().isEmpty())return null"));
        var packet = Sources.text("HelmPackets");
        assertTrue(packet.contains("b.writeEnum(p.landing.kind())"));
        assertTrue(packet.contains("b.readEnum(HullLanding.Kind.class)"));
        for (String lang : new String[] {"en_us", "zh_cn"}) {
            var json = FlightPermitTest.json("assets/starfold/lang/" + lang + ".json");
            for (String key :
                    new String[] {
                        "below_land",
                        "below_water",
                        "below_lava",
                        "below_slope",
                        "below_unknown",
                        "stair_yes",
                        "stair_no"
                    }) assertTrue(json.has("hud.starfold.instrument_" + key));
            assertTrue(json.has("message.starfold.helm_exit_stair"));
        }
    }

    @Test
    void bothBooksGiveTheNextActionAndLandingCue() {
        assertTrue(StarterBooks.pages("zh_cn").getFirst().endsWith("走上舷梯，右键船头的控制器"));
        assertTrue(
                StarterBooks.pages("en_us")
                        .getFirst()
                        .endsWith("Walk up the airstair; right-click the bow controller."));
        assertTrue(StarterBooks.pages("zh_cn").get(4).contains("最近的其他星球"));
        assertTrue(StarterBooks.pages("en_us").get(4).contains("nearest other planet"));
        assertTrue(StarterBooks.pages("zh_cn").get(7).contains("下方"));
        assertTrue(StarterBooks.pages("en_us").get(7).contains("Below"));
    }
}
