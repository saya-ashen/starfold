package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GasPlayerExposureState.Participation;
import dev.starfold.chart.GasPlayerExposureValues;
import dev.starfold.chart.GasPressure;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class GasPlayerExposureHudTest {
    private static GasPlayerExposurePacket packet(double pressure, boolean danger,
            int grace, Participation participation) {
        return GasPlayerExposurePacket.sampled(17, ResourceLocation.parse("starfold:system"), 1,
                new GasPlayerExposure.Sample(danger ? GasPlayerExposure.Condition.DANGER
                        : GasPlayerExposure.Condition.SAFE,
                        Optional.of(new GasPressure.Reading(3, 0, pressure))),
                new GasPlayerExposureValues(grace, 0, 0), participation);
    }

    @Test
    void riskThresholdsUsePositivePressurePointFivePointEightAndServerDanger() {
        assertTrue(GasPlayerExposureHud.lines(packet(0, false, 100, Participation.ACTIVE)).isEmpty());
        double[] pressure = {.001, .499999, .5, .799999, .8, 1};
        String[] suffix = {"pressure", "pressure", "structural", "structural",
                "near_limit", "survival_limit"};
        for (int i = 0; i < pressure.length; i++) {
            var lines = GasPlayerExposureHud.lines(packet(
                    pressure[i], i == pressure.length - 1, 37, Participation.ACTIVE));
            assertEquals("hud.starfold.personal_gas_" + suffix[i], lines.getFirst().key());
            assertEquals(List.of(37), lines.get(1).arguments());
            assertEquals(2, lines.size());
        }
    }

    @Test
    void graceAndExemptionAreServerSamplesNotClientDamageOrRealSeconds() {
        for (var participation : Participation.values()) {
            var lines = GasPlayerExposureHud.lines(packet(1, true, 0, participation));
            assertEquals(List.of(0), lines.get(1).arguments());
            assertEquals(participation == Participation.ACTIVE ? 2 : 3, lines.size());
            if (participation == Participation.EXEMPT)
                assertEquals("hud.starfold.personal_gas_exempt", lines.get(2).key());
            if (participation == Participation.DEAD)
                assertEquals("hud.starfold.personal_gas_dead", lines.get(2).key());
        }
        var initial = GasPlayerExposureHud.lines(packet(1, true, 100, Participation.ACTIVE));
        assertEquals(List.of(100), initial.get(1).arguments());
        assertEquals(initial, GasPlayerExposureHud.lines(packet(1, true, 100, Participation.ACTIVE)));
    }

    @Test
    void unknownIsExplicitAndClearOrExpiredNeverDisplaysOldGrace() {
        var unknown = GasPlayerExposurePacket.sampled(17, ResourceLocation.parse("starfold:system"), 1,
                GasPlayerExposure.unknown(), new GasPlayerExposureValues(42, 0, 0), Participation.ACTIVE);
        var lines = GasPlayerExposureHud.lines(unknown);
        assertEquals("hud.starfold.personal_gas_unknown", lines.getFirst().key());
        assertTrue(lines.getFirst().arguments().isEmpty());
        assertEquals(List.of(42), lines.get(1).arguments());
        assertTrue(GasPlayerExposureHud.lines(null).isEmpty());
        assertTrue(GasPlayerExposureHud.lines(GasPlayerExposurePacket.clear(
                17, ResourceLocation.parse("starfold:system"), 2)).isEmpty());
        var cache = new GasPlayerExposureCache();
        cache.focus(new GasPlayerExposureCache.Identity(unknown.dimension(), unknown.session()));
        assertTrue(cache.receive(unknown, null, 1));
        assertTrue(GasPlayerExposureHud.lines(cache.current(41)).isEmpty());
    }

    @Test
    void bothLanguagesKeepExistingHullLabelsAndSupplyPersonalAndDeathMessages() throws Exception {
        for (String language : List.of("en_us", "zh_cn")) {
            var json = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/starfold/lang/" + language + ".json"))).getAsJsonObject();
            for (String suffix : List.of("pressure", "structural", "near_limit", "survival_limit",
                    "unknown", "grace", "exempt", "dead"))
                assertTrue(json.has("hud.starfold.personal_gas_" + suffix));
            assertTrue(json.has("hud.starfold.instrument_gas_warning"));
            assertTrue(json.has("death.attack.starfold.gas_pressure"));
            assertTrue(json.has("death.attack.starfold.gas_pressure.player"));
            assertTrue(json.has("death.attack.starfold.gas_pressure.item"));
            String grace = json.get("hud.starfold.personal_gas_grace").getAsString();
            assertTrue(grace.contains("%s"));
            assertTrue(grace.contains("tick"));
            for (var line : GasPlayerExposureHud.lines(packet(1, true, 100, Participation.EXEMPT))) {
                String translated = String.format(java.util.Locale.ROOT,
                        json.get(line.key()).getAsString(), line.arguments().toArray());
                assertFalse(translated.isBlank());
            }
        }
    }

    @Test
    void clientAdapterUsesRegionAuthorityLifecycleAndPersonalRenderingWithoutHullOrHurt() throws Exception {
        String client = Files.readString(Path.of(
                "src/main/java/dev/starfold/space/GasPlayerExposureClient.java"));
        assertTrue(client.contains("SpaceClient.session()"));
        assertTrue(client.contains("level != mc.level || player != mc.player"));
        assertTrue(client.contains("cache.invalidate()"));
        assertTrue(client.contains("LoggingOut"));
        assertTrue(client.contains("cache.reset()"));
        assertTrue(client.contains("GasPlayerExposureHud.lines(packet)"));
        assertFalse(client.contains("HelmClient"));
        assertFalse(client.contains("Sable"));
        assertFalse(client.contains("hurt("));
        assertFalse(client.contains("graceTicks() -"));
    }
}
