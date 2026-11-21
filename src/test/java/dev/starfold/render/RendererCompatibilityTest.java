package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

class RendererCompatibilityTest {
    private static final Set<String> OPTIONAL =
            Set.of(
                    "SentryShipMuzzleMixin",
                    "HullBottomCacheMixin",
                    "HelmPerspectiveMixin",
                    "HelmCameraMixin",
                    "HelmMouseMixin",
                    "HelmFitMixin",
                    "HelmCollisionPoseMixin",
                    "SableShipWriteMixin",
                    "SableShipStorageMixin",
                    "SableShipSerializationMixin",
                    "SableShipSplitMixin",
                    "SableFuelMassMixin",
                    "SableShipReadinessMixin");

    @Test
    void unsupportedRenderersDisableProjectionWithoutDisablingNativeMixins() throws Exception {
        var config =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of("src/main/resources/starfold.mixins.json")))
                        .getAsJsonObject();
        assertEquals("dev.starfold.render.RendererMixinPlugin", config.get("plugin").getAsString());
        assertTrue(config.get("required").getAsBoolean());
        assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        for (String renderer : Set.of("sodium", "iris", "embeddium", "canvas")) {
            assertFalse(RendererCompatibility.supported(renderer::equals));
            for (String group : Set.of("mixins", "client"))
                for (var entry : config.getAsJsonArray(group)) {
                    String name = "dev.starfold.mixin." + entry.getAsString();
                    assertEquals(
                            !OPTIONAL.contains(entry.getAsString()),
                            RendererCompatibility.apply(name, renderer::equals),
                            name);
                }
        }
        assertTrue(RendererCompatibility.supported(Set.of("sable", "veil", "create")::contains));
        for (String group : Set.of("mixins", "client"))
            for (var entry : config.getAsJsonArray(group))
                assertEquals(
                        !OPTIONAL.contains(entry.getAsString()),
                        RendererCompatibility.apply(
                                "dev.starfold.mixin." + entry.getAsString(), id -> false));
    }

    @Test
    void sentryShipShimRequiresAllThreeMods() throws Exception {
        String name = "dev.starfold.mixin.SentryShipMuzzleMixin";
        var mods = java.util.List.of("sentrymechanicalarm", "tacz", "sable");
        for (int mask = 0; mask < 8; mask++) {
            var loaded = new java.util.HashSet<String>();
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0) loaded.add(mods.get(i));
            assertEquals(mask == 7, RendererCompatibility.apply(name, loaded::contains));
        }
    }

    @Test
    void helmMouseRequiresOnlySable() {
        String name = "dev.starfold.mixin.HelmMouseMixin";
        assertFalse(RendererCompatibility.apply(name, id -> false));
        assertTrue(RendererCompatibility.apply(name, "sable"::equals));
        assertFalse(RendererCompatibility.apply(name, "simulated"::equals));
    }

    @Test
    void helmCameraRequiresOnlySable() {
        String name = "dev.starfold.mixin.HelmCameraMixin";
        assertFalse(RendererCompatibility.apply(name, id -> false));
        assertTrue(RendererCompatibility.apply(name, "sable"::equals));
        assertFalse(RendererCompatibility.apply(name, "simulated"::equals));
    }

    @Test
    void noticeWaitsForPlayerAndIsOncePerPlanetEntryNotPerTick() {
        var notice = new RendererCompatibility.Notice();
        Object planet = new Object(), otherPlanet = new Object(), vanilla = new Object();
        assertFalse(notice.enter(null, false, false, false));
        assertFalse(notice.enter(vanilla, false, true, false));
        assertFalse(notice.enter(planet, true, false, false));
        assertTrue(notice.enter(planet, true, true, false));
        for (int tick = 0; tick < 1000; tick++)
            assertFalse(notice.enter(planet, true, true, false));
        assertTrue(notice.enter(otherPlanet, true, true, false));
        assertFalse(notice.enter(otherPlanet, true, true, false));
        assertFalse(notice.enter(vanilla, false, true, false));
        assertTrue(notice.enter(planet, true, true, false));
        assertFalse(notice.enter(null, false, false, false));
        assertTrue(notice.enter(planet, true, true, false));
    }

    @Test
    void supportedRendererNeverWarns() {
        var notice = new RendererCompatibility.Notice();
        Object planet = new Object();
        for (int tick = 0; tick < 1000; tick++) assertFalse(notice.enter(planet, true, true, true));
    }

    @Test
    void bothLanguagesExplainTheUnsupportedBuildWithoutPromisingCorrectPlanets() throws Exception {
        for (String language : Set.of("en_us", "zh_cn")) {
            var lang =
                    JsonParser.parseString(
                                    Files.readString(
                                            Path.of(
                                                    "src/main/resources/assets/starfold/lang/"
                                                            + language
                                                            + ".json")))
                            .getAsJsonObject();
            String text = lang.get("message.starfold.renderer").getAsString();
            for (String renderer : Set.of("Sodium", "Iris", "Embeddium", "Canvas"))
                assertTrue(text.contains(renderer));
            assertTrue(text.contains(language.equals("en_us") ? "not drawn correctly" : "无法"));
            assertTrue(text.contains(language.equals("en_us") ? "disabled" : "停用"));
        }
    }
}
