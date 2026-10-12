package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGuidance;
import dev.starfold.chart.GroundTransit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundNavigationSave;
import dev.starfold.ground.GroundPackets;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.render.GroundSurfaceIndex;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetTypeTest;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.waypoint.Waypoint;
import dev.starfold.waypoint.WaypointCodec;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.PlanetGeneration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/** A system is a list of planets of any length; the two it began with are the first two of it. */
class PlanetCountTest {
    final SingleLevelStorageTest fixture = new SingleLevelStorageTest();

    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    PlanetChunkGenerator third() {
        var settings =
                new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
                        Optional.of(net.minecraft.core.HolderSet.direct()),
                        PlanetEcologyTest.palette.getHolderOrThrow(Biomes.DESERT),
                        List.of());
        settings.getLayersInfo()
                .add(new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        return new PlanetChunkGenerator(
                settings,
                20261003L,
                5,
                Optional.empty(),
                Optional.of(new PlanetGeneration("rocky", "rocky_craters", 1, 1.2, .04, .09, 512)));
    }

    SystemChunkGenerator three() {
        var two = fixture.generator();
        return new SystemChunkGenerator(
                PlanetStorage.LAYOUT, List.of(two.planet(0), two.planet(1), third()));
    }

    @Test
    void aSystemOfThreeGeneratesEachPlanetInItsOwnStorageAndNothingBeyondASmallerFace() {
        var g = three();
        assertEquals(3, g.count());
        assertThrows(IllegalArgumentException.class, () -> g.planet(3));
        PlanetShape.system(new int[] {2048, 2048, 512}, new int[] {191, 191, 191});
        try {
            // The third planet is the first of the second row: under the first, a row stride along
            // z.
            int row = PlanetStorage.ROW_STRIDE / 16, rowBlocks = PlanetStorage.ROW_STRIDE;
            assertEquals(0, PlanetStorage.offset(2));
            assertEquals(rowBlocks, PlanetStorage.offsetZ(2));
            for (int face = 0; face < 6; face++) {
                var chunk = fixture.chunk(g, face * 256 + 8, row + 8);
                int solid = 0;
                for (int y = -64; y < 200; y++)
                    if (!chunk.getBlockState(
                                    new BlockPos(
                                            chunk.getPos().getMinBlockX() + 3, y, rowBlocks + 131))
                            .isAir()) solid++;
                assertTrue(solid > 100, "face " + face + " of the third planet has ground");
                assertSame(
                        Blocks.BEDROCK,
                        chunk.getBlockState(
                                        new BlockPos(
                                                chunk.getPos().getMinBlockX(),
                                                -64,
                                                rowBlocks + 128))
                                .getBlock());
                assertTrue(
                        chunk.getNoiseBiome(1, 40, 33).is(Biomes.DESERT),
                        "the third planet's own biome");
                assertTrue(
                        fixture.chunk(g, face * 256 + 40, row + 8)
                                .getBlockState(
                                        new BlockPos((face * 256 + 40) * 16, -64, rowBlocks + 128))
                                .isAir(),
                        "nothing beyond a face of 512");
                assertTrue(
                        fixture.chunk(g, face * 256 + 8, row + 40)
                                .getBlockState(
                                        new BlockPos(
                                                (face * 256 + 8) * 16, -64, rowBlocks + 640 + 8))
                                .isAir(),
                        "nor beyond it along z");
            }
            assertTrue(
                    fixture.chunk(g, PlanetStorage.PLANET_STRIDE / 16 + 8, row + 8)
                            .getBlockState(
                                    new BlockPos(
                                            PlanetStorage.PLANET_STRIDE + 128,
                                            -64,
                                            rowBlocks + 128))
                            .isAir(),
                    "no fourth planet");
            assertTrue(
                    fixture.chunk(g, 2 * PlanetStorage.PLANET_STRIDE / 16 + 8, 8)
                            .getBlockState(
                                    new BlockPos(2 * PlanetStorage.PLANET_STRIDE + 128, -64, 128))
                            .isAir(),
                    "nothing where a row of three would have put it");
            // The same ground as the planet alone makes of the same place.
            var alone = g.planet(2);
            for (int x = 3; x < 500; x += 97)
                for (int z = 5; z < 500; z += 89)
                    assertEquals(
                            alone.getBaseHeight(
                                    x,
                                    z,
                                    net.minecraft.world.level.levelgen.Heightmap.Types
                                            .WORLD_SURFACE_WG,
                                    PlanetEcologyTest.HEIGHT,
                                    null),
                            g.getBaseHeight(
                                    x,
                                    rowBlocks + z,
                                    net.minecraft.world.level.levelgen.Heightmap.Types
                                            .WORLD_SURFACE_WG,
                                    PlanetEcologyTest.HEIGHT,
                                    null));
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void planetsStandInASquareThatGrowsAShellAtATimeAndEachKeepsItsPlace() {
        assertEquals(0, PlanetStorage.column(0));
        assertEquals(0, PlanetStorage.row(0));
        assertEquals(1, PlanetStorage.column(1));
        assertEquals(0, PlanetStorage.row(1));
        assertEquals(0, PlanetStorage.column(2));
        assertEquals(1, PlanetStorage.row(2));
        assertEquals(1, PlanetStorage.column(3));
        assertEquals(1, PlanetStorage.row(3));
        assertEquals(2, PlanetStorage.column(4));
        assertEquals(0, PlanetStorage.row(4));
        var seen = new java.util.HashSet<Long>();
        for (int planet = 0; planet < 5000; planet++) {
            int column = PlanetStorage.column(planet), row = PlanetStorage.row(planet);
            assertTrue(seen.add(column * 100000L + row));
            assertEquals(planet, PlanetStorage.index(column, row));
            int shell = (int) Math.floor(Math.sqrt(planet));
            assertEquals(shell, Math.max(column, row), "a square of n holds the first n*n");
        }
        int last = PlanetStorage.MAX_PLANETS - 1;
        assertEquals(PlanetStorage.SIDE - 1, PlanetStorage.column(last));
        assertEquals(PlanetStorage.SIDE - 1, PlanetStorage.row(last));
        assertEquals(-1, PlanetStorage.index(PlanetStorage.SIDE, 0));
        assertEquals(-1, PlanetStorage.index(-1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetStorage.column(PlanetStorage.MAX_PLANETS));
        // The furthest storage lies inside the world border, and no planet's charts reach its
        // neighbours'.
        assertTrue((long) PlanetStorage.SIDE * PlanetStorage.PLANET_STRIDE < 29_999_984L);
        assertTrue(PlanetStorage.PLANET_STRIDE >= 2048 + 6 * 4096);
        assertTrue(PlanetStorage.ROW_STRIDE >= 2048 + 4096);
    }

    @Test
    void storageAddressesEveryPlanetOfTheRunningSystemAndNoMore() {
        assertEquals(2, PlanetShape.count());
        assertThrows(IllegalArgumentException.class, () -> PlanetStorage.offset(2));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetStorage.planet(10, PlanetStorage.ROW_STRIDE + 10));
        assertEquals(-1, PlanetStorage.find(10, PlanetStorage.ROW_STRIDE + 10));
        int[] sizes = new int[] {2048, 1024, 512, 1024, 768},
                depths = new int[] {191, 326, 191, 191, 191};
        PlanetShape.system(sizes, depths);
        try {
            assertEquals(5, PlanetShape.count());
            assertEquals(512, PlanetShape.of(2).size());
            assertEquals(326, PlanetShape.depth(1));
            assertThrows(IllegalArgumentException.class, () -> PlanetShape.of(5));
            for (int planet = 0; planet < 5; planet++) {
                int ox = PlanetStorage.offset(planet), oz = PlanetStorage.offsetZ(planet);
                assertEquals(PlanetStorage.column(planet) * PlanetStorage.PLANET_STRIDE, ox);
                assertEquals(PlanetStorage.row(planet) * PlanetStorage.ROW_STRIDE, oz);
                assertEquals(planet, PlanetStorage.planet(ox + 10.5, oz + 10.5));
                assertEquals(
                        planet,
                        PlanetStorage.planet(ox - 100.0, oz + 10.5),
                        "the halo before its first face");
                assertEquals(
                        planet,
                        PlanetStorage.planet(ox + 10.5, oz - 100.0),
                        "and the halo above it");
                long key = PlanetStorage.key(ox / 16 + 256 + 3, oz / 16 + 5);
                assertEquals(
                        planet * (long) PlanetStorage.CHUNKS_PER_PLANET
                                + dev.starfold.storage.ChunkKeys.local(256 + 3, 5),
                        key);
                assertEquals(
                        new net.minecraft.world.level.ChunkPos(ox / 16 + 256 + 3, oz / 16 + 5),
                        PlanetStorage.chunk(key));
                var query = new BlockPos(ox + 100, 64, oz + 100);
                var address = PlanetStorage.address(query);
                assertEquals(planet, address.owner().planet());
                assertEquals(query.getX(), address.owner().block().getX());
                assertEquals(query.getZ(), address.owner().block().getZ());
                // A step off any edge of a face is another face of the same planet, never a
                // neighbour's.
                assertEquals(
                        planet,
                        PlanetStorage.address(new BlockPos(ox - 1, 64, oz + 100)).owner().planet());
                assertEquals(
                        planet,
                        PlanetStorage.address(new BlockPos(ox + 100, 64, oz - 1)).owner().planet());
                assertEquals(
                        planet,
                        PlanetStorage.address(new BlockPos(ox + 100, 64, oz + sizes[planet]))
                                .owner()
                                .planet());
                assertEquals(new BlockPos(100, 64, 100), PlanetStorage.local(planet, query));
                var pose =
                        PlanetStorage.body(
                                planet,
                                new dev.starfold.geometry.BentStripGeometry.Point(
                                        ox + 100.5, 140, oz + 100.5));
                assertEquals(
                        new dev.starfold.geometry.BentStripGeometry.Point(
                                ox + 100.5, 140, oz + 100.5),
                        PlanetStorage.feet(planet, pose));
                var access = PlanetAccess.SHARED;
                assertTrue(access.inChart(query));
                assertEquals(planet, access.storagePlanet(query));
                assertEquals(sizes[planet], access.shape(query).size());
                assertEquals(0, access.face(query.getX()));
                assertEquals(3, access.face(ox + 3 * 4096 + 50));
                assertEquals(
                        new dev.starfold.geometry.BentStripGeometry.Point(ox + 4096, 128, oz),
                        access.origin(planet, 1));
                assertEquals(
                        new dev.starfold.geometry.BentStripGeometry.Point(50.5, 12, 60.5),
                        access.chart(
                                new dev.starfold.geometry.BentStripGeometry.Point(
                                        ox + 4096 + 50.5, 140, oz + 60.5)));
            }
            assertEquals(
                    -1,
                    PlanetStorage.key(256 + 32, PlanetStorage.ROW_STRIDE / 16 + 5),
                    "beyond a face of 512");
            assertEquals(
                    -1,
                    PlanetStorage.key(256 + 3, PlanetStorage.ROW_STRIDE / 16 + 32 + 5),
                    "along z too");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PlanetStorage.chunk(5L * PlanetStorage.CHUNKS_PER_PLANET));
            // Two planets of one column, or of one row, are not the same planet.
            var access = PlanetAccess.SHARED;
            assertFalse(access.samePlanet(10, 10, 10, PlanetStorage.ROW_STRIDE + 10));
            assertFalse(access.samePlanet(10, 10, PlanetStorage.PLANET_STRIDE + 10, 10));
            assertTrue(access.samePlanet(10, 10, 4096 + 300, -50));
            assertNull(
                    access.image(
                            10.5,
                            10.5,
                            new dev.starfold.geometry.BentStripGeometry.Point(
                                    4096 + 10.5, 140, PlanetStorage.ROW_STRIDE + 10.5)),
                    "a place of the planet below is not drawn in this one's chart");
            assertEquals(-1, PlanetStorage.find(10, 5000), "between two rows");
            assertEquals(-1, PlanetStorage.find(27000, 10), "between two columns");
            assertFalse(access.inChart(new BlockPos(10, 128, 5000)));
        } finally {
            PlanetSystem.reset();
        }
        assertEquals(2, PlanetShape.count());
        assertThrows(
                IllegalArgumentException.class, () -> PlanetShape.system(new int[0], new int[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetShape.system(new int[] {2048}, new int[] {191, 191}));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetShape.system(new int[] {2048, 500}, new int[] {191, 191}));
    }

    @Test
    void aRowOfTwoIsStillReadAndAThirdPlanetNeedsTheSquare() throws Exception {
        var two = fixture.generator();
        assertEquals(
                2,
                new SystemChunkGenerator(PlanetStorage.ROW_LAYOUT, two.planet(0), two.planet(1))
                        .count());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SystemChunkGenerator(
                                PlanetStorage.ROW_LAYOUT,
                                List.of(two.planet(0), two.planet(1), third())));
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var row =
                SystemChunkGenerator.CODEC
                        .codec()
                        .encodeStart(
                                ops,
                                new SystemChunkGenerator(
                                        PlanetStorage.ROW_LAYOUT, two.planet(0), two.planet(1)))
                        .getOrThrow()
                        .getAsJsonObject();
        assertEquals("six_face_x32768_v1", row.get("storage_layout").getAsString());
        assertEquals(
                "six_face_square_v2",
                SystemChunkGenerator.CODEC
                        .codec()
                        .encodeStart(ops, three())
                        .getOrThrow()
                        .getAsJsonObject()
                        .get("storage_layout")
                        .getAsString());
        for (var preset : List.of("native_trio", "native_stars", "native_universe"))
            assertTrue(
                    java.nio.file.Files.readString(
                                    java.nio.file.Path.of(
                                            "src/main/resources/data/starfold/worldgen/world_preset/"
                                                    + preset
                                                    + ".json"))
                            .contains("\"storage_layout\":\"six_face_square_v2\""),
                    preset);
        for (var preset : List.of("native_system", "native_exploration"))
            assertTrue(
                    java.nio.file.Files.readString(
                                    java.nio.file.Path.of(
                                            "src/main/resources/data/starfold/worldgen/world_preset/"
                                                    + preset
                                                    + ".json"))
                            .contains("\"storage_layout\":\"six_face_x32768_v1\""),
                    preset);
    }

    @Test
    void aSystemIsSavedAsItsNamedPairOrAsAListAndNeverBoth() {
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var codec = SystemChunkGenerator.CODEC.codec();
        var pair = codec.encodeStart(ops, fixture.generator()).getOrThrow().getAsJsonObject();
        assertTrue(pair.has("a") && pair.has("b"));
        assertFalse(pair.has("planets"));
        var list = codec.encodeStart(ops, three()).getOrThrow().getAsJsonObject();
        assertFalse(list.has("a") || list.has("b"));
        assertEquals(3, list.getAsJsonArray("planets").size());
        var back = codec.parse(ops, list).getOrThrow();
        assertEquals(3, back.count());
        assertEquals(512, back.planet(2).terrain().shape().size());
        assertEquals(20261003L, back.planet(2).terrain().seed());
        assertEquals(list, codec.encodeStart(ops, back).getOrThrow());
        var both = list.deepCopy();
        both.add("a", pair.get("a"));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(ops, both));
        var neither = list.deepCopy();
        neither.remove("planets");
        assertThrows(IllegalArgumentException.class, () -> codec.parse(ops, neither));
        var one = list.deepCopy();
        one.getAsJsonArray("planets").remove(2);
        one.getAsJsonArray("planets").remove(1);
        assertThrows(IllegalArgumentException.class, () -> codec.parse(ops, one));
        // A list may hold any of the planet policies in any order; the named pair stays the
        // temperate and the rocky one.
        assertEquals(
                2,
                new SystemChunkGenerator(PlanetStorage.LAYOUT, List.of(third(), third())).count());
    }

    @Test
    void theClientIsToldEveryPlanetsShapeAndDepth() {
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            GroundPackets.Shapes.CODEC.encode(
                    buffer,
                    new GroundPackets.Shapes(
                            new int[] {2048, 1024, 512}, new int[] {652, 326, 191}));
            var read = GroundPackets.Shapes.CODEC.decode(buffer);
            assertArrayEquals(new int[] {2048, 1024, 512}, read.sizes());
            assertArrayEquals(new int[] {652, 326, 191}, read.depths());
            assertEquals(3, PlanetShape.count(), "takes effect as it is decoded");
            assertEquals(326, PlanetShape.depth(1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GroundPackets.Shapes(new int[] {2048, 100}, new int[] {191, 191}));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GroundPackets.Shapes(new int[] {2048}, new int[0]));
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void theModelCarriesEveryPlanetOfASystemAndTheFirstTwoAreWhereTheyWere() {
        var two = PlanetSystem.model();
        var three = PlanetSystem.model(3);
        assertEquals(2, two.count());
        assertEquals(3, three.count());
        assertEquals(two, PlanetSystem.model(2));
        for (long tick : new long[] {0, 777, 48000, 1234567})
            for (int id = 0; id < 2; id++) {
                assertEquals(two.at(id, tick).center(), three.at(id, tick).center());
                assertEquals(two.at(id, tick).velocity(), three.at(id, tick).velocity());
            }
        assertThrows(IllegalArgumentException.class, () -> two.at(2, 0));
        assertThrows(IllegalArgumentException.class, () -> three.at(3, 0));
        assertThrows(IllegalArgumentException.class, () -> PlanetSystem.model(1));
        // The third planet circles the same sun a ring further out, and never comes near the first
        // two.
        double ring = two.radius() + PlanetSystem.RING;
        for (long tick = 0; tick < 400000; tick += 3371) {
            var c = three.at(2, tick);
            assertEquals(ring, c.center().sub(three.center()).length(), 1e-6);
            assertEquals(
                    2 * Math.PI * ring / three.more().get(0).period(), c.velocity().length(), 1e-9);
            for (int id = 0; id < 2; id++)
                assertTrue(
                        c.center().sub(three.at(id, tick).center()).length()
                                > PlanetSystem.RING - 1);
        }
        var step = three.at(2, 1000.5).center().sub(three.at(2, 999.5).center());
        assertEquals(
                0,
                step.sub(three.at(2, 1000).velocity()).length(),
                1e-6,
                "velocity is the motion of the centre");
        assertTrue(three.extent() >= ring + 8192);
        assertEquals(GroundTransit.SPACE_RADIUS, two.extent());
        assertTrue(three.drift() >= two.drift());
        assertEquals(two.turn(), three.turn());
        assertSame(three.more().get(0).spin(), three.spin(2));
    }

    @Test
    void aLargerModelIsSavedSentAndSpunWhole() {
        var three = PlanetSystem.model(4);
        var tag = GroundEphemerisSave.modelTag(three);
        assertEquals(2, tag.getList("more", 10).size());
        assertEquals(three, GroundEphemerisSave.model(tag));
        assertFalse(
                GroundEphemerisSave.modelTag(PlanetSystem.model()).contains("more"),
                "a pair is saved as it always was");
        var spun = GroundEphemeris.spinning(three, false, 500);
        assertEquals(4, spun.count());
        assertFalse(spun.spin(3).enabled());
        assertEquals(three.at(3, 500).center(), spun.at(3, 500).center());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Model(
                                three.center(),
                                three.axis(),
                                three.radial(),
                                three.radius(),
                                three.period(),
                                three.phase(),
                                three.epoch(),
                                three.a(),
                                three.b(),
                                three.sun(),
                                false,
                                three.more()));
    }

    @Test
    void aVesselBelongsToTheNearestOfAllPlanetsAndNamesRunOn() {
        var m = PlanetSystem.model(3);
        long tick = 1000;
        var a = m.at(0, tick).center();
        var c = m.at(2, tick).center();
        assertEquals(
                0,
                GroundDynamics.select(
                        m,
                        a.add(new dev.starfold.geometry.BentStripGeometry.Point(0, 3000, 0)),
                        0,
                        tick));
        assertEquals(
                2,
                GroundDynamics.select(
                        m,
                        c.add(new dev.starfold.geometry.BentStripGeometry.Point(0, 3000, 0)),
                        0,
                        tick));
        assertEquals(
                2,
                GroundDynamics.select(
                        m,
                        c.add(new dev.starfold.geometry.BentStripGeometry.Point(0, 3000, 0)),
                        1,
                        tick));
        var middle = a.add(c).scale(.5);
        assertEquals(
                0,
                GroundDynamics.select(m, middle, 0, tick),
                "the one held is kept at the midpoint");
        assertEquals(2, GroundDynamics.select(m, middle, 2, tick));
        var two = PlanetSystem.model();
        for (int id = 0; id < 2; id++)
            assertEquals(
                    id,
                    GroundDynamics.select(
                            two,
                            two.at(id, tick)
                                    .center()
                                    .add(
                                            new dev.starfold.geometry.BentStripGeometry.Point(
                                                    0, 2000, 0)),
                            1 - id,
                            tick));
        assertEquals("A", PlanetSystem.name(0));
        assertEquals("C", PlanetSystem.name(2));
        assertEquals("Z", PlanetSystem.name(25));
        assertEquals("P26", PlanetSystem.name(26));
    }

    @Test
    void anyPlanetOfTheRunningSystemCanBeSteeredForAndNoOther() {
        assertThrows(
                IllegalArgumentException.class, () -> new GroundGuidance.Options(2, false, false));
        PlanetShape.system(new int[] {2048, 1024, 512}, new int[] {191, 191, 191});
        try {
            var m = PlanetSystem.model(3);
            var options = new GroundGuidance.Options(2, true, false);
            assertEquals(2, options.target());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GroundGuidance.Options(3, false, false));
            var pose =
                    new PlanetShape.Pose(
                            CubeSurfaceTopology.Face.values()[0],
                            new dev.starfold.geometry.BentStripGeometry.Point(256, 400, 256));
            var state =
                    GroundDynamics.guidance(
                            GroundDynamics.local(
                                    100,
                                    2,
                                    pose,
                                    GroundEphemeris.ZERO,
                                    new org.joml.Quaterniond(),
                                    true,
                                    GroundDynamics.OFF),
                            options);
            assertEquals(2, state.planet());
            assertEquals(state, GroundNavigationSave.decode(GroundNavigationSave.encode(state)));
            var marker =
                    new Waypoint(
                            "c_base",
                            2,
                            new PlanetShape.Pose(
                                    CubeSurfaceTopology.Face.values()[0],
                                    new dev.starfold.geometry.BentStripGeometry.Point(
                                            100, 130, 100)));
            assertEquals(marker, WaypointCodec.decode(WaypointCodec.encode(marker)));
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void theTrioPresetIsAListOfThreeWithASmallThirdPlanet() throws Exception {
        var json =
                com.google.gson.JsonParser.parseString(
                                java.nio.file.Files.readString(
                                        java.nio.file.Path.of(
                                                "src/main/resources/data/starfold/worldgen/world_preset/native_trio.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("dimensions")
                        .getAsJsonObject("starfold:native_planet");
        assertEquals("starfold:native_deep", json.get("type").getAsString());
        var g =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(
                                net.minecraft.resources.RegistryOps.create(
                                        JsonOps.INSTANCE, PlanetEcologyTest.lookup),
                                json.get("generator"))
                        .getOrThrow();
        assertEquals(3, g.count());
        assertEquals(2048, g.planet(0).terrain().shape().size());
        assertEquals(1024, g.planet(1).terrain().shape().size());
        assertEquals(512, g.planet(2).terrain().shape().size());
        assertEquals(-528, g.getMinY());
        assertEquals(191, g.planet(2).generation().depth());
        assertTrue(g.planet(2).generation().coreShell());
        assertNotEquals(g.planet(1).terrain().seed(), g.planet(2).terrain().seed());
        assertFalse(
                java.nio.file.Files.exists(
                        java.nio.file.Path.of(
                                "src/main/resources/data/minecraft/tags/worldgen/world_preset/extended.json")));
    }

    @Test
    void aUniverseOfSeveralSunsKeepsEachPlanetAboutItsOwnAndLightsEachPlaceByTheNearest() {
        var one = PlanetSystem.model(3);
        assertEquals(one, PlanetSystem.model(new int[] {3}));
        assertTrue(one.stars().isEmpty());
        assertEquals(1, one.everyStar().size());
        var m = PlanetSystem.model(new int[] {2, 2});
        assertEquals(4, m.count());
        assertEquals(1, m.stars().size());
        assertEquals(2, m.everyStar().size());
        var two = PlanetSystem.model();
        for (long tick : new long[] {0, 999, 123456})
            for (int id = 0; id < 2; id++)
                assertEquals(two.at(id, tick).center(), m.at(id, tick).center());
        var far = m.stars().get(0);
        assertEquals(PlanetSystem.STAR_GAP, far.position().sub(m.sun().position()).length(), 1e-9);
        for (long tick = 0; tick < 300000; tick += 4111) {
            assertEquals(m.radius(), m.at(2, tick).center().sub(far.position()).length(), 1e-6);
            assertEquals(
                    m.radius() + PlanetSystem.RING,
                    m.at(3, tick).center().sub(far.position()).length(),
                    1e-6);
            for (int id = 0; id < 2; id++)
                assertSame(
                        m.everyStar().get(0).getClass(),
                        m.star(m.at(id, tick).center()).getClass());
            for (int id = 0; id < 2; id++)
                assertEquals(m.sun().position(), m.star(m.at(id, tick).center()).position());
            for (int id = 2; id < 4; id++) assertEquals(far, m.star(m.at(id, tick).center()));
        }
        assertTrue(m.extent() >= PlanetSystem.STAR_GAP + 8192);
        assertEquals(m, GroundEphemerisSave.model(GroundEphemerisSave.modelTag(m)));
        assertFalse(GroundEphemerisSave.modelTag(one).contains("stars"));
        assertEquals(m.stars(), GroundEphemeris.spinning(m, false, 10).stars());
        assertThrows(IllegalArgumentException.class, () -> PlanetSystem.model(new int[] {1, 3}));
        assertThrows(IllegalArgumentException.class, () -> PlanetSystem.model(new int[] {2, 0}));
        assertThrows(IllegalArgumentException.class, () -> PlanetSystem.model(new int[0]));
    }

    @Test
    void aSystemSavesHowItsPlanetsAreSharedOutAmongItsSuns() throws Exception {
        var ops = PlanetTypeTest.ops();
        var codec = SystemChunkGenerator.CODEC.codec();
        var json = PlanetTypeTest.preset("native_stars");
        var g = codec.parse(ops, json).getOrThrow();
        assertEquals(8, g.count());
        assertArrayEquals(new int[] {2, 2, 2, 2}, g.groups());
        assertEquals(768, g.planet(2).terrain().shape().size());
        assertEquals(8, PlanetSystem.model(g.groups()).count());
        var saved = codec.encodeStart(ops, g).getOrThrow().getAsJsonObject();
        assertEquals(4, saved.getAsJsonArray("stars").size());
        assertArrayEquals(new int[] {2, 2, 2, 2}, codec.parse(ops, saved).getOrThrow().groups());
        // More planets about one sun than the gap to the next has room for are refused: their
        // circles would cross.
        assertThrows(IllegalArgumentException.class, () -> PlanetSystem.model(new int[] {2, 6}));
        assertThrows(IllegalArgumentException.class, () -> PlanetSystem.model(new int[] {2, 3, 3}));
        assertEquals(5, PlanetSystem.model(new int[] {3, 2}).count());
        var plain =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        assertFalse(
                codec.encodeStart(plain, three()).getOrThrow().getAsJsonObject().has("stars"),
                "one sun is not written");
        assertArrayEquals(new int[] {3}, three().groups());
        assertArrayEquals(new int[] {2}, fixture.generator().groups());
        var wrong = saved.deepCopy();
        wrong.getAsJsonArray("stars").set(1, new com.google.gson.JsonPrimitive(3));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(ops, wrong));
        var pair = codec.encodeStart(plain, fixture.generator()).getOrThrow().getAsJsonObject();
        pair.add("stars", saved.get("stars"));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(plain, pair));
        assertFalse(
                java.nio.file.Files.exists(
                        java.nio.file.Path.of(
                                "src/main/resources/data/minecraft/tags/worldgen/world_preset/extended.json")));
    }

    @Test
    void theSystemIsToldBeforeTheFirstFrameThatNamesOneOfItsPlanets() throws Exception {
        var frames = Sources.text("ObserverFrames");
        int told = frames.indexOf("GroundServer.shapes(player)"),
                sent = frames.indexOf("new GroundFlightPackets.Frame(frame)");
        assertTrue(told > 0 && told < sent, "shapes go out before the frame");
        // What the client would otherwise refuse: a frame on a third planet while it still counts
        // two.
        assertThrows(IllegalArgumentException.class, () -> ObserverFrame.identity(2, 0));
        PlanetShape.system(new int[] {2048, 1024, 512}, new int[] {191, 191, 191});
        try {
            assertEquals(2, ObserverFrame.identity(2, 0).planet());
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void eachPlanetHasASurfaceIndexOfItsOwn() throws Exception {
        assertTrue(
                Sources.text("GroundSurfaceIndex").contains("if(Files.size(path)<4096)continue;"),
                "a region file still being made is passed over");
        assertEquals(GroundSurfaceIndex.FILE, GroundSurfaceIndex.file(0));
        assertEquals(GroundSurfaceIndex.FILE + "_planet_b", GroundSurfaceIndex.file(1));
        var names = new java.util.HashSet<String>();
        for (int id = 0; id < 40; id++)
            assertTrue(names.add(GroundSurfaceIndex.file(id)), "planet " + id);
    }
}
