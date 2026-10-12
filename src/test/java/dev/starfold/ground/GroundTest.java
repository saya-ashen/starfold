package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.render.CoreShaderSource;
import dev.starfold.render.GroundShaderSource;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundEphemerisSave;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

class GroundTest {
    static final List<String> PROGRAMS =
            List.of(
                    "rendertype_solid",
                    "rendertype_cutout",
                    "rendertype_cutout_mipped",
                    "rendertype_translucent",
                    "rendertype_translucent_moving_block",
                    "rendertype_entity_cutout",
                    "rendertype_entity_solid",
                    "rendertype_entity_translucent",
                    "rendertype_item_entity_translucent_cull",
                    "particle",
                    "rendertype_lines",
                    "rendertype_crumbling",
                    "rendertype_text",
                    "rendertype_entity_shadow");

    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    String resource(String p) throws Exception {
        try (var in = TestArchiveResources.open("/" + p)) {
            assertNotNull(in, p);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    String expand(String text) throws Exception {
        var m = Pattern.compile("#moj_import\\s+[<\"]([^>\"]+)[>\"]").matcher(text);
        var out = new StringBuilder();
        while (m.find())
            m.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            expand(
                                    resource("assets/minecraft/shaders/include/" + m.group(1))
                                            .replaceAll("(?m)^\\s*#version[^\\r\\n]*", ""))));
        m.appendTail(out);
        return out.toString();
    }

    @Test
    void nativePlotsAreDisjointAndKeepOrdinaryYGravity() {
        for (var face : Face.values()) {
            var p = new Point(1024.5, 7, 800.5);
            var world = GroundCoordinates.world(face, p);
            assertEquals(face.ordinal(), GroundCoordinates.face(world.x()));
            assertEquals(p, GroundCoordinates.chart(world));
            assertTrue(GroundCoordinates.owned(world));
            assertEquals(GroundCoordinates.FLOOR + 7, world.y());
        }
        assertFalse(GroundCoordinates.owned(new Point(-10, 128, 100)));
        assertThrows(IllegalArgumentException.class, () -> GroundCoordinates.origin(6));
    }

    @Test
    void aNightSleptThroughTurnsTheClockOnAndNeverBackOrPastItsEnd() {
        var s = GroundClock.fresh(false);
        long start = s.clock.tick();
        s.skip(12300);
        assertEquals(start + 12300, s.clock.tick());
        assertEquals(
                start + 12300,
                GroundClock.load(s.save(new CompoundTag(), null), null).clock.tick());
        assertThrows(IllegalStateException.class, () -> s.skip(-1));
        assertThrows(IllegalStateException.class, () -> s.skip(GroundEphemeris.MAX_TICK));
        assertEquals(start + 12300, s.clock.tick());
    }

    @Test
    void nativeSkyClockSavesSeparatelyAndRefusesCorruptExistingData() {
        var s = GroundClock.fresh(false);
        for (int i = 0; i < 123; i++) s.advance();
        var tag = s.save(new CompoundTag(), null);
        var copy = GroundClock.load(tag, null);
        assertEquals(s.clock.model, copy.clock.model);
        assertEquals(123, copy.clock.tick());
        assertNotEquals(GroundEphemerisSave.FILE, GroundClock.FILE);
        assertFalse(tag.contains("pairs"));
        assertThrows(IllegalStateException.class, () -> GroundClock.fresh(true));
        tag.getCompound("motion").remove("sun");
        assertThrows(IllegalArgumentException.class, () -> GroundClock.load(tag, null));
    }

    @Test
    void customWireContainsOnlyCelestialMetadata() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            raw, net.minecraft.core.RegistryAccess.EMPTY);
            var p = new GroundPackets.Sky(42, GroundSolar.defaults());
            GroundPackets.Sky.CODEC.encode(b, p);
            assertEquals(p, GroundPackets.Sky.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
            assertEquals("native_ground_v2_sky", p.type().id().getPath());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GroundPackets.Sky(0, GroundEphemeris.defaults()));
        } finally {
            raw.release();
        }
    }

    @Test
    void nativeBackendHasNoMaterialPlacementOrProxyMovementLoop() throws Exception {
        var sources = List.of(Sources.text("GroundServer"), Sources.text("PlanetPlayers"));
        for (String forbidden :
                List.of(
                        "GroundWorld",
                        "GroundMaterials",
                        "GroundPackets.Input",
                        "GroundTransit.step",
                        "GroundMotion.step",
                        "setBlock(",
                        "action("))
            for (var source : sources) assertFalse(source.contains(forbidden), forbidden);
        assertTrue(Sources.text("PlanetLevels").contains("native_ground"));
        assertTrue(Sources.text("Starfold").contains("StarfoldServer.initialize(modBus)"));
        assertTrue(
                Sources.text("GroundClock")
                        .contains(
                                "Buildings, inventory and block entities stay in native chunk saves"));
    }

    @Test
    void dimensionUsesNativeFlatGeneratorAndNormalLighting() throws Exception {
        var root = Path.of("src/main/resources/data/starfold");
        var dim =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("dimension/native_ground.json")))
                        .getAsJsonObject();
        var generator = dim.getAsJsonObject("generator");
        assertEquals("minecraft:flat", generator.get("type").getAsString());
        int height =
                generator.getAsJsonObject("settings").getAsJsonArray("layers").asList().stream()
                        .mapToInt(e -> e.getAsJsonObject().get("height").getAsInt())
                        .sum();
        assertEquals(128, height - 64);
        var type =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("dimension_type/native_ground.json")))
                        .getAsJsonObject();
        assertFalse(type.has("fixed_time"));
        assertEquals(0, type.get("ambient_light").getAsDouble());
    }

    @Test
    void vanillaSpecialShapesAreRetainedInsteadOfFullCubeProxies() {
        var getter = net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
        var pos = net.minecraft.core.BlockPos.ZERO;
        var stair =
                net.minecraft.world.level.block.Blocks.OAK_STAIRS
                        .defaultBlockState()
                        .getCollisionShape(getter, pos);
        double volume =
                stair.toAabbs().stream()
                        .mapToDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())
                        .sum();
        assertEquals(.75, volume, 1e-9);
        assertTrue(
                net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState().hasBlockEntity());
        assertFalse(
                net.minecraft.world.level.block.Blocks.GLASS
                        .defaultBlockState()
                        .isSolidRender(getter, pos));
        assertFalse(
                net.minecraft.world.level.block.Blocks.WATER
                        .defaultBlockState()
                        .getFluidState()
                        .isEmpty());
    }

    @Test
    void nativeTerrainWrapPreservesModelsAlphaLightmapsAndIsIdempotent() throws Exception {
        for (String n : PROGRAMS)
            for (boolean vertex : new boolean[] {true, false}) {
                var config =
                        com.google.gson.JsonParser.parseString(
                                        resource("assets/minecraft/shaders/core/" + n + ".json"))
                                .getAsJsonObject();
                String stage =
                        config.get(vertex ? "vertex" : "fragment")
                                .getAsString()
                                .replace("minecraft:", "");
                String raw =
                        resource(
                                "assets/minecraft/shaders/core/"
                                        + stage
                                        + (vertex ? ".vsh" : ".fsh"));
                String patched = CoreShaderSource.patch(stage, vertex, raw);
                assertTrue(patched.contains("void cpNativeOriginalMain()"), n);
                assertEquals(patched, CoreShaderSource.patch(stage, vertex, patched));
                assertTrue(patched.contains("cpCapOriginalMain();"));
                if (vertex) {
                    assertTrue(patched.contains("if(CPNativeActive<0.5)return"));
                    assertTrue(patched.contains("vec4 view=cpNativeModelView"));
                    assertFalse(patched.contains("inverse(ProjMat)*gl_Position"));
                } else assertFalse(patched.contains("fragColor.a ="));
            }
    }

    @Test
    void actualVanillaAndVeilShaderPairsAreExportedForLinking() throws Exception {
        var out = Path.of("build/native-ground");
        Files.createDirectories(out);
        for (String n : PROGRAMS) {
            var config =
                    com.google.gson.JsonParser.parseString(
                                    resource("assets/minecraft/shaders/core/" + n + ".json"))
                            .getAsJsonObject();
            for (boolean vertex : new boolean[] {true, false}) {
                String stage =
                        config.get(vertex ? "vertex" : "fragment")
                                .getAsString()
                                .replace("minecraft:", "");
                String text =
                        expand(
                                CoreShaderSource.patch(
                                        stage,
                                        vertex,
                                        resource(
                                                "assets/minecraft/shaders/core/"
                                                        + stage
                                                        + (vertex ? ".vsh" : ".fsh"))));
                String ext = vertex ? "vert" : "frag";
                Files.writeString(out.resolve(n + "." + ext), text);
                var parsed =
                        io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(
                                text, new HashMap<>());
                Files.writeString(out.resolve(n + "-veil." + ext), parsed.toSourceString());
            }
        }
        for (String n : List.of("native_ground_sun", "native_ground_stars"))
            for (String ext : List.of("vsh", "fsh")) {
                String text =
                        Files.readString(
                                Path.of(
                                        "src/main/resources/assets/starfold/shaders/core/"
                                                + n
                                                + "."
                                                + ext));
                var parsed =
                        io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(
                                text, new HashMap<>());
                Files.writeString(
                        out.resolve(n + "-veil." + (ext.equals("vsh") ? "vert" : "frag")),
                        parsed.toSourceString());
            }
        Files.writeString(
                out.resolve("scope.json"),
                "{\"native_chunks\":true,\"shader_programs\":14,\"seam_routing\":true,\"space_roundtrip\":true,\"minecraft_started\":false,\"runtime_verified\":false}\n");
    }

    @Test
    void shaderFrameClosesBeforeHandAndOtherWorlds() throws Exception {
        var wrapper = Sources.text("CoreShaderRenderMixin");
        assertTrue(wrapper.contains("GroundClient.begin"));
        assertTrue(wrapper.contains("GroundClient.end"));
        assertTrue(Sources.text("CoreShaderShaderMixin").contains("GroundClient.apply(shader)"));
        for (String n :
                List.of(
                        "position_tex",
                        "position_color",
                        "rendertype_gui",
                        "rendertype_clouds",
                        "custom_machine_shader"))
            assertEquals("unchanged", GroundShaderSource.patch(n, true, "unchanged"));
    }

    @Test
    void installedSeamsAndSpaceKeepTheirRoutingBorderAndRealModels() throws Exception {
        assertTrue(
                Sources.text("GroundCoordinates").contains("GroundTopology routes halo queries"));
        assertTrue(Sources.text("PlanetPlayers").contains("getWorldBorder().setSize(60_000_000)"));
        assertTrue(Sources.text("GroundFlightServer").contains("GroundDynamics.step"));
        assertTrue(Sources.text("GroundRenderer").contains("getBlockRenderer()"));
    }

    @Test
    void nativeLightingIsDimensionScopedAndUsesTheSavedSun() throws Exception {
        assertTrue(Sources.text("GroundLightMixin").contains("PlanetLevels.is"));
        assertTrue(Sources.text("GroundLightMixin").contains("GroundSolar.daylight"));
        assertTrue(Sources.text("GroundFogMixin").contains("GroundSolar.sky"));
        assertTrue(Sources.text("GroundClient").contains("SkyType.NONE"));
    }
}
