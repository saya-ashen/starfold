package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.Sources;
import dev.starfold.chart.GasAppearance;
import dev.starfold.chart.GasGiant;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class GasRenderTest {
    static final Path SHADERS = Path.of("src/main/resources/assets/starfold/shaders/core");

    @Test
    void dedicatedUniformsMatchThePhysicalBandedNonEmissiveProgram() throws Exception {
        var json =
                JsonParser.parseString(Files.readString(SHADERS.resolve("native_gas_giant.json")))
                        .getAsJsonObject();
        var uniforms = new HashMap<String, com.google.gson.JsonObject>();
        for (var entry : json.getAsJsonArray("uniforms")) {
            var value = entry.getAsJsonObject();
            assertNull(uniforms.put(value.get("name").getAsString(), value));
        }
        assertEquals(
                GasAppearance.BANDS,
                uniforms.get("BandFrequency").getAsJsonArray("values").get(0).getAsDouble());
        assertEquals(
                GasAppearance.WARP,
                uniforms.get("BandWarp").getAsJsonArray("values").get(0).getAsDouble());
        assertEquals(
                GasAppearance.AMBIENT,
                uniforms.get("Ambient").getAsJsonArray("values").get(0).getAsDouble());
        assertEquals(256, uniforms.get("GasSpheres").get("count").getAsInt());
        assertEquals("float", uniforms.get("GasSpheres").get("type").getAsString());
        assertEquals(3, uniforms.get("GasFogColor").get("count").getAsInt());
        assertEquals("float", uniforms.get("GasFogColor").get("type").getAsString());
        var source =
                Files.readString(
                        Path.of("src/main/java/dev/starfold/render/GasGiantRenderer.java"));
        var names =
                new HashSet<String>(
                        List.of("ModelViewMat", "ProjMat", "SpaceLens", "GasCount", "GasSpheres"));
        var matcher =
                java.util.regex.Pattern.compile("(?:vector|getUniform)\\(\\s*\"([^\"]+)\"")
                        .matcher(source);
        while (matcher.find()) names.add(matcher.group(1));
        assertEquals(names, uniforms.keySet());
        var vertex = Files.readString(SHADERS.resolve("native_gas_giant.vsh"));
        var fragment = Files.readString(SHADERS.resolve("native_gas_giant.fsh"));
        assertTrue(vertex.contains("DrawCenter+chart*GasRadius"));
        assertFalse(vertex.contains("Background"));
        assertTrue(fragment.contains("q.y*BandFrequency+warp+GasPhase"));
        assertTrue(fragment.contains("Ambient+(1.0-Ambient)*daylight*gasLight"));
        assertTrue(fragment.contains("SurfaceHigh, GasFogColor, SkyColor"));
        assertTrue(fragment.contains("if(Inside>0.5){fragColor=vec4(GasFogColor,1.0);return;}"));
        var exterior = Files.readString(SHADERS.resolve("native_ground_exterior.fsh"));
        var function =
                java.util.regex.Pattern.compile(
                        "float gasVisibility\\(.*?\\n}", java.util.regex.Pattern.DOTALL);
        var a = function.matcher(fragment);
        var b = function.matcher(exterior);
        assertTrue(a.find());
        assertTrue(b.find());
        assertEquals(a.group(), b.group());
    }

    @Test
    void actualVeilParserRoundTripsBothGasStagesAndExportsUniqueEvidence() throws Exception {
        var parent = Path.of(System.getProperty("starfold.gasShaderEvidence", "build/d1-gas/shader-runs"));
        Files.createDirectories(parent);
        var out = Files.createTempDirectory(parent, "gas-");
        for (String ext : List.of("vsh", "fsh")) {
            String source = Files.readString(SHADERS.resolve("native_gas_giant." + ext));
            String suffix = ext.equals("vsh") ? "vert" : "frag";
            var tree =
                    io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(
                            source, new HashMap<>());
            var parsed = tree.toSourceString();
            assertNotNull(
                    io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(
                            parsed, new HashMap<>()));
            Files.writeString(out.resolve("gas." + suffix), source, StandardOpenOption.CREATE_NEW);
            Files.writeString(
                    out.resolve("gas-veil." + suffix), parsed, StandardOpenOption.CREATE_NEW);
        }
    }

    @Test
    void adapterUsesCatalogFramesAndRestoresItsGpuStateWithoutDefaultGasWork() throws Exception {
        var renderer = Sources.text("GasGiantRenderer");
        var draw = renderer.substring(renderer.indexOf("public static void draw"));
        assertTrue(
                draw.indexOf("GasOcclusion.present(model)") < draw.indexOf("GL_CURRENT_PROGRAM"));
        assertTrue(draw.indexOf("GL_CURRENT_PROGRAM") < draw.indexOf("if(mesh==null)build()"));
        for (String contract :
                List.of(
                        "model.catalogIndices()",
                        "model.catalogBody(index)",
                        "model.frame(index,tick)",
                        "SpaceView.place",
                        "GroundClient.place",
                        "GasLighting.clear(shader)",
                        "RenderSystem.depthFunc(GL11.GL_LEQUAL)",
                        "_glBindVertexArray(vertexArray)",
                        "_glBindBuffer(GL15.GL_ARRAY_BUFFER,arrayBuffer)",
                        "ProgramManager.glUseProgram(program)"))
            assertTrue(renderer.contains(contract), contract);
        assertFalse(renderer.contains("model.at(index"));
        assertFalse(renderer.contains("PlanetPackets.Profile"));
        assertFalse(renderer.contains("System.currentTimeMillis"));
        var hooks = Sources.text("GroundCelestials");
        assertTrue(hooks.contains("GasGiantRenderer.initialize(modBus)"));
        assertTrue(hooks.contains("GasGiantRenderer.clear()"));
        assertEquals(
                2,
                java.util.regex.Pattern.compile("GasGiantRenderer\\.draw\\(c\\)")
                        .matcher(hooks)
                        .results()
                        .count());
        assertTrue(
                Sources.text("GroundSunRenderer")
                        .contains(
                                "GasOcclusion.present(GroundClient.model())?1:GroundOrbit.weight"));
    }

    @Test
    void cameraFogIsLockIndependentBoundedAndRespectsVanillaOverrides() throws Exception {
        var policy = new GasGiant(1, 42, "Amber", 0, 0xffffff, 256, 512);
        float previous = Float.POSITIVE_INFINITY;
        for (int i = 0; i <= 100; i++) {
            var layer = new GasAtmosphereView.Layer(3, policy, i / 100.0, new Point(.2, .2, .2));
            assertTrue(layer.fogEnd() <= previous);
            assertTrue(layer.fogEnd() >= 8 && layer.fogEnd() <= 256);
            previous = layer.fogEnd();
        }
        for (double pressure : new double[] {-1, 2, Double.NaN})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GasAtmosphereView.Layer(3, policy, pressure, new Point(0, 0, 0)));
        var source = Sources.text("GasAtmosphereView");
        assertFalse(source.contains("SpaceNavigationClient"));
        assertTrue(source.contains("PlanetLevels.is(world)"));
        assertTrue(source.contains("MobEffects.BLINDNESS"));
        assertTrue(source.contains("MobEffects.DARKNESS"));
        assertTrue(source.contains("SpaceClient.universeEye(camera.getPosition())"));
        var fog = Sources.text("GroundFogMixin");
        assertTrue(fog.contains("GasAtmosphereView.layer(camera)"));
        assertTrue(fog.contains("RenderSystem.setShaderFogEnd(gas.fogEnd())"));
    }
}
