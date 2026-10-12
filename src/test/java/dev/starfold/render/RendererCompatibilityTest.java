package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

class RendererCompatibilityTest {
    private static final Set<String> OPTIONAL =
            Set.of(
                    "SentryShipMuzzleMixin",
                    "HelmPerspectiveMixin",
                    "HelmCameraMixin",
                    "HelmMouseMixin",
                    "HelmFitMixin",
                    "HelmCollisionPoseMixin",
                    "PilotPoseProbeMixin",
                    "DevPortCameraMixin",
                    "DevPortClockMixin",
                    "DevPortTintMixin");

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
        assertTrue(
                Sources.text("RendererMixinPlugin")
                        .contains("LoadingModList.get().getModFileById(id)!=null"));
        assertFalse(Sources.text("RendererMixinPlugin").contains("net.minecraft"));
        assertTrue(
                Sources.text("RendererGuard")
                        .contains("RendererCompatibility.supported(loader::isLoaded)"));
    }

    @Test
    void portObserversRequireExplicitOptIn() {
        String key = "starfold.portProbe", old = System.getProperty(key);
        try {
            for (String name :
                    Set.of("DevPortCameraMixin", "DevPortClockMixin", "DevPortTintMixin")) {
                System.clearProperty(key);
                assertFalse(RendererCompatibility.apply("dev.starfold.mixin." + name, id -> true));
                System.setProperty(key, "true");
                assertTrue(RendererCompatibility.apply("dev.starfold.mixin." + name, id -> false));
            }
        } finally {
            if (old == null) System.clearProperty(key);
            else System.setProperty(key, old);
        }
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
        var shim = Sources.text("SentryShipMuzzleMixin");
        assertTrue(shim.contains("@Pseudo"));
        assertTrue(shim.contains("method=\"fireGun\""));
        assertTrue(shim.contains("isInSableSubLevel() ? getActualMuzzlePos() : original"));
        assertTrue(shim.contains("horizontalDistanceSqr() < 0.0025"));
        assertTrue(shim.contains("new Vec2(0.0F, cir.getReturnValue().y)"));
        assertFalse(shim.contains("require=0"));
        assertFalse(shim.contains("hurt("));
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
        var client = Sources.text("RendererGuard");
        assertTrue(client.contains("Component.translatable(\"message.starfold.renderer\")"));
        assertTrue(client.contains("mc.player.sendSystemMessage(message)"));
        assertTrue(client.contains("warn(\"{}\",message.getString())"));
        assertFalse(Sources.text("GroundClient").contains("message.starfold.renderer"));
    }

    @Test
    void projectionGatesCoverFrameSelectionDetailsAndDraws() throws Exception {
        assertTrue(
                Sources.text("GroundClient")
                        .contains("!ready()||!RendererGuard.pipelineSupported()"));
        assertTrue(
                Sources.text("GroundRenderer")
                        .contains(
                                "if(GroundClient.ready()&&RendererGuard.pipelineSupported()&&level==Minecraft.getInstance().level)prepareDetails();"));
        assertTrue(
                Sources.text("GroundRenderer")
                        .contains(
                                "selectionActive=GroundClient.ready()&&RendererGuard.pipelineSupported()"));
        assertTrue(
                Sources.text("GroundCelestials").contains("||!RendererGuard.pipelineSupported()"));
        assertTrue(
                Sources.text("CoreShaderProgramMixin")
                        .contains("RendererGuard.pipelineSupported()"));
        assertTrue(
                Sources.text("CoreShaderVeilProcessorMixin")
                        .contains("RendererGuard.pipelineSupported()"));
    }
}
