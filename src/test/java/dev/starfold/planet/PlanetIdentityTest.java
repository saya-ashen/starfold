package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.ground.GroundPackets;
import dev.starfold.worldgen.PlanetChunkGenerator;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.*;

import java.util.*;

class PlanetIdentityTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void typedEntriesPersistIdsWithoutRequiringTheirRegistry() {
        var json =
                com.google.gson.JsonParser.parseString(
                        "{\"planet_type\":\"starfold:rocky\",\"terrain_seed\":42}");
        var entry = PlanetType.PLANET.parse(PlanetTypeTest.ops(), json).getOrThrow();
        var saved = PlanetType.PLANET.encodeStart(PlanetTypeTest.ops(), entry).getOrThrow();
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var copy = PlanetType.PLANET.parse(bare, saved).getOrThrow();
        assertEquals(Optional.of(ResourceLocation.parse("starfold:rocky")), copy.get().typeId());
        assertEquals(saved, PlanetType.PLANET.encodeStart(bare, copy).getOrThrow());
    }

    @Test
    void optionalNamesAndIdsSurviveDefinitionBeforeAndAfterMaterialization() throws Exception {
        var definition = AuthoredSystemTest.read(AuthoredSystemTest.definition());
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var saved =
                SystemDefinition.CODEC
                        .encodeStart(AuthoredSystemTest.ops(), definition)
                        .getOrThrow();
        var copy = SystemDefinition.CODEC.parse(bare, saved).getOrThrow();
        var a = definition.materialize(42);
        var b = copy.materialize(42);
        for (int i = 0; i < a.planets().size(); i++)
            assertEquals(
                    PlanetIdentity.of(a.planets().get(i)), PlanetIdentity.of(b.planets().get(i)));
        assertEquals(Optional.of("Aster"), b.planets().getFirst().displayName());
        assertTrue(b.planets().get(3).displayName().isEmpty());
        var generator = b.planets().getFirst();
        assertEquals(
                PlanetIdentity.of(generator),
                PlanetIdentity.of(
                        PlanetChunkGenerator.CODEC
                                .codec()
                                .parse(
                                        bare,
                                        PlanetChunkGenerator.CODEC
                                                .codec()
                                                .encodeStart(bare, generator)
                                                .getOrThrow())
                                .getOrThrow()));
    }

    @Test
    void savedUniverseAndLayoutKeepTypeOrigins() throws Exception {
        var ops = PlanetTypeTest.ops();
        var source =
                com.google.gson.JsonParser.parseString(
                        "{\"stars\":{\"min\":1,\"max\":1},\"planets\":{\"min\":2,\"max\":2},\"star_gap\":100000,\"types\":[{\"type\":\"starfold:rocky\"}]}");
        var layout = UniverseLayout.CODEC.parse(ops, source).getOrThrow();
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var copy =
                UniverseLayout.CODEC
                        .parse(bare, UniverseLayout.CODEC.encodeStart(ops, layout).getOrThrow())
                        .getOrThrow();
        var universe = Universe.generate(42, copy, PlanetSystem.model());
        var loaded =
                Universe.load(
                        universe.save(
                                new net.minecraft.nbt.CompoundTag(), PlanetEcologyTest.lookup),
                        PlanetEcologyTest.lookup);
        for (var planet : loaded.planets())
            assertEquals(Optional.of(ResourceLocation.parse("starfold:rocky")), planet.typeId());
    }

    @Test
    void oldInlineDefaultPairReportsFamilyNotGuessedBiome() throws Exception {
        var system =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(PlanetTypeTest.ops(), PlanetTypeTest.preset("native_universe"))
                        .getOrThrow();
        for (int i = 0; i < 2; i++) {
            var id = PlanetIdentity.of(system.planet(i));
            assertTrue(id.type().isEmpty());
            assertEquals(i == 0 ? "temperate" : "rocky", id.label(k -> k));
        }
    }

    @Test
    void twelveIdentitiesRoundTripIndependentlyOfNearEight() {
        var identities = new ArrayList<PlanetIdentity>();
        for (int i = 0; i < 12; i++)
            identities.add(
                    new PlanetIdentity(
                            Optional.of(ResourceLocation.parse("example:type_" + i)),
                            "rocky",
                            i == 11 ? Optional.of("planet.example.last") : Optional.empty()));
        int[] sizes = new int[12], depths = new int[12];
        Arrays.fill(sizes, 1024);
        Arrays.fill(depths, 192);
        var packet = new GroundPackets.Shapes(sizes, depths, List.of(), identities);
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            GroundPackets.Shapes.CODEC.encode(buffer, packet);
            var copy = GroundPackets.Shapes.CODEC.decode(buffer);
            assertEquals(identities, copy.identities());
            assertEquals(12, PlanetSystem.identities().size());
            assertEquals(identities.getLast(), PlanetSystem.identity(11));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
            PlanetShape.reset();
        }
        assertTrue(PlanetSystem.identities().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundPackets.Shapes(sizes, depths, List.of(), identities.subList(0, 8)));
    }

    @Test
    void formatterUsesNameThenLocalizedTypeThenPathAndKeepsRank() {
        var id =
                new PlanetIdentity(
                        Optional.of(ResourceLocation.parse("starfold_pack:asteroid")),
                        "rocky",
                        Optional.empty());
        assertEquals("asteroid", id.label(k -> k));
        assertEquals(
                "小行星", id.label(k -> k.equals("planet_type.starfold_pack.asteroid") ? "小行星" : k));
        assertEquals(
                "H · 小行星 · 9/12", PlanetIdentity.caption(7, "小行星", 9, 12, 240, String::length));
        assertEquals(
                "My home",
                new PlanetIdentity(id.type(), "rocky", Optional.of("My home")).label(k -> k));
        assertEquals(
                "家",
                new PlanetIdentity(id.type(), "rocky", Optional.of("home.key"))
                        .label(k -> k.equals("home.key") ? "家" : k));
    }

    @Test
    void everyInstalledTypeHasBothTranslations() throws Exception {
        var root = java.nio.file.Path.of("src/main/resources");
        for (String language : List.of("en_us", "zh_cn")) {
            var lang =
                    com.google.gson.JsonParser.parseString(
                                    java.nio.file.Files.readString(
                                            root.resolve(
                                                    "assets/starfold/lang/" + language + ".json")))
                            .getAsJsonObject();
            try (var files = java.nio.file.Files.walk(root.resolve("data"))) {
                for (var path :
                        files.filter(
                                        p ->
                                                p.toString().contains("/planet_type/")
                                                        && p.toString().endsWith(".json"))
                                .toList()) {
                    var rel = root.resolve("data").relativize(path);
                    String key =
                            "planet_type."
                                    + rel.getName(0)
                                    + "."
                                    + path.getFileName().toString().replace(".json", "");
                    assertTrue(lang.has(key), key);
                }
            }
        }
    }
}
