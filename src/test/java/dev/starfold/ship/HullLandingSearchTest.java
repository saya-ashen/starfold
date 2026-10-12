package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.FlightPermitTest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class HullLandingSearchTest {
    @Test
    void ringsAreBoundedUniqueAndNearestFirst() {
        var offsets = HullLandingSearch.offsets();
        assertEquals(offsets.size(), new java.util.HashSet<>(offsets).size());
        double previous = 0;
        for (var p : offsets) {
            assertTrue(p.distance() >= previous);
            assertTrue(p.distance() <= 160);
            assertEquals(0, p.x() % 8);
            assertEquals(0, p.z() % 8);
            previous = p.distance();
        }
        assertEquals(8, offsets.getFirst().distance());
    }

    @Test
    void workIsIncrementalAndStopsAtNearestHit() {
        var search = new HullLandingSearch();
        var count = new AtomicInteger();
        assertNull(
                search.step(
                        p -> {
                            count.incrementAndGet();
                            return false;
                        }));
        assertEquals(HullLandingSearch.BUDGET, count.get());
        assertFalse(search.complete());
        var expected = HullLandingSearch.offsets().get(HullLandingSearch.BUDGET);
        assertEquals(
                expected,
                search.step(
                        p -> {
                            count.incrementAndGet();
                            return true;
                        }));
        assertTrue(search.complete());
        assertEquals(HullLandingSearch.BUDGET + 1, count.get());
        assertEquals(
                expected,
                search.step(
                        p -> {
                            fail("finished search must not evaluate");
                            return false;
                        }));
    }

    @Test
    void exhaustiveNoHitTerminatesAndNeverExceedsBudget() {
        var search = new HullLandingSearch();
        int total = 0;
        while (!search.complete()) {
            var count = new AtomicInteger();
            assertNull(
                    search.step(
                            p -> {
                                count.incrementAndGet();
                                return false;
                            }));
            assertTrue(count.get() <= HullLandingSearch.BUDGET);
            total += count.get();
        }
        assertEquals(HullLandingSearch.offsets().size(), total);
    }

    @Test
    void wiringKeepsLoadedOnlyForecastAndBilingualCue() throws Exception {
        var landing = Sources.text("HullLanding");
        assertTrue(landing.contains("var site=candidate(level,body,0,0)"));
        assertTrue(landing.contains("getChunkNow(x>>4,z>>4)"));
        assertTrue(landing.contains("Airstair.canDeployAt(level,body"));
        assertTrue(landing.contains("Thrusters.scopeTier(body)<3||!eligible(level,body)"));
        assertTrue(landing.contains("if(!finished&&scan.complete())refreshed=tick"));
        for (String lang : new String[] {"en_us", "zh_cn"}) {
            var json = FlightPermitTest.json("assets/starfold/lang/" + lang + ".json");
            for (String key : new String[] {"landing_site", "landing_none", "landing_search"})
                assertTrue(json.has("hud.starfold.instrument_" + key));
        }
        assertTrue(StarterBooks.pages("zh_cn").get(7).contains("箭头"));
        assertTrue(StarterBooks.pages("en_us").get(7).contains("arrow"));
    }

    @Test
    void aidPacketRoundTrips() {
        var buf =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var site =
                    new HullLanding.Site(
                            HullLanding.Kind.SLOPE,
                            false,
                            new HullLanding.Aid(HullLanding.AidStatus.FOUND, 12, -35));
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
                            site);
            HelmPackets.State.CODEC.encode(buf, packet);
            assertEquals(packet, HelmPackets.State.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }
}
