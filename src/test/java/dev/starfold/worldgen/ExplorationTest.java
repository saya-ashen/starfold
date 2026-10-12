package dev.starfold.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.planet.SingleLevelStorageTest;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.waypoint.Waypoint;
import dev.starfold.waypoint.WaypointCodec;
import dev.starfold.waypoint.WaypointPackets;
import dev.starfold.waypoint.WaypointSave;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

class ExplorationTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    static final PlanetGeneration POLICY =
            new PlanetGeneration("rocky", "rocky_craters", 1, 1.2, .04, .09);

    Waypoint marker(String name, int planet) {
        return new Waypoint(
                name, planet, new PlanetShape.Pose(Face.TOP, new Point(1024, 35, 1024)));
    }

    @Test
    void impactBowlRimAndOutsideHaveSmoothBoundedProfile() {
        assertEquals(-1, PlanetCraters.profile(0));
        assertTrue(PlanetCraters.profile(1.075) > .34);
        assertEquals(0, PlanetCraters.profile(1.3));
        assertEquals(0, PlanetCraters.profile(4));
        for (double t : new double[] {.85, 1.3})
            assertEquals(PlanetCraters.profile(t - 1e-7), PlanetCraters.profile(t + 1e-7), 1e-10);
    }

    @Test
    void impactsAreSeededAndNotPerFaceOrGenerationOrder() {
        var a = new PlanetCraters(20);
        assertEquals(a.impacts(), new PlanetCraters(20).impacts());
        assertNotEquals(a.impacts(), new PlanetCraters(21).impacts());
        assertEquals(80, a.impacts().size());
        for (var p : a.impacts()) {
            assertEquals(1, p.center().length(), 1e-12);
            assertTrue(a.elevation(p.center()) < -5);
        }
    }

    @Test
    void oldRockHeightAndSurfaceAlgorithmIsUnchanged() {
        var t = new PlanetTerrain(20261002, 5);
        for (var f : Face.values())
            for (int u = 64; u < 2048; u += 128) {
                var p = PlanetTerrain.direction(f, u, 700);
                double broad = t.noise(p, 3.2, 501), ridge = 1 - Math.abs(t.noise(p, 8.5, 502));
                assertEquals(
                        Math.clamp(
                                broad * 16 + ridge * ridge * 32 - 9 + t.noise(p, 32, 503) * 3,
                                -24,
                                48),
                        t.elevation(p));
                assertSame(Blocks.STONE.defaultBlockState(), t.surfaceState(f, u, 700));
            }
    }

    @Test
    void craterTerrainHasRealSurfaceMineralBandsAndRockRegions() {
        var t = new PlanetTerrain(20261002, 5, POLICY);
        var found = new HashSet<Block>();
        int changed = 0;
        var old =
                new PlanetTerrain(
                        20261002, 5, new PlanetGeneration("rocky", "rocky", 1, 1.2, .04, .09));
        for (var f : Face.values())
            for (int u = 16; u < 2048; u += 32)
                for (int v = 16; v < 2048; v += 32) {
                    found.add(t.surfaceState(f, u, v).getBlock());
                    if (old.groundY(f, u, v) != t.groundY(f, u, v)) changed++;
                }
        assertTrue(changed > 300);
        for (var block :
                List.of(
                        Blocks.STONE,
                        Blocks.BASALT,
                        Blocks.ANDESITE,
                        Blocks.IRON_ORE,
                        Blocks.COPPER_ORE)) assertTrue(found.contains(block), block.toString());
    }

    @Test
    void craterNodesJoinAcrossAllFaces() {
        var t = new PlanetTerrain(20261002, 5, POLICY);
        for (var f : Face.values())
            for (int[] edge :
                    new int[][] {
                        {0, 900}, {2048, 900}, {900, 0}, {900, 2048}, {0, 0}, {2048, 2048}
                    }) {
                var p =
                        f.normal
                                .scale(1024)
                                .add(f.u.scale(edge[0] - 1024))
                                .add(f.v.scale(edge[1] - 1024));
                for (var g : Face.values())
                    if (g != f && p.dot(g.normal) == 1024)
                        assertEquals(
                                t.elevation(f, edge[0], edge[1]),
                                t.elevation(g, 1024 + p.dot(g.u), 1024 + p.dot(g.v)),
                                1e-10);
            }
    }

    @Test
    void newPresetCodecPinsExplorationWhileOldPresetStaysOriginal() throws Exception {
        var root = Path.of("src/main/resources/data/starfold/worldgen/world_preset");
        var json =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("native_exploration.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("dimensions")
                        .getAsJsonObject("starfold:native_planet")
                        .get("generator");
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var g = SystemChunkGenerator.CODEC.codec().parse(ops, json).getOrThrow();
        assertEquals(
                new PlanetGeneration(
                        POLICY.type(),
                        POLICY.algorithm(),
                        POLICY.algorithmVersion(),
                        POLICY.relief(),
                        POLICY.oreBias(),
                        POLICY.caveWidth(),
                        1024,
                        326,
                        PlanetGeneration.CORE_SHELL,
                        PlanetPalette.ROCK,
                        PlanetMinerals.NONE,
                        PlanetClimate.MILD,
                        false),
                g.planet(1).generation());
        assertTrue(g.planet(0).generation().coreShell());
        assertEquals(2048, g.planet(0).terrain().shape().size());
        assertEquals(652, g.planet(0).generation().depth());
        assertEquals(-528, g.getMinY());
        assertEquals(848, g.getGenDepth());
        var copy =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(
                                ops,
                                SystemChunkGenerator.CODEC.codec().encodeStart(ops, g).getOrThrow())
                        .getOrThrow();
        var fixture = new SingleLevelStorageTest();
        for (var f : Face.values()) {
            var chunk = fixture.chunk(copy, 2048 + f.ordinal() * 256 + 32, 32);
            assertTrue(
                    fixture.chunk(copy, 2048 + f.ordinal() * 256 + 64, 64)
                            .getBlockState(
                                    new BlockPos((2048 + f.ordinal() * 256 + 64) * 16, -64, 1024))
                            .isAir(),
                    "nothing is generated beyond the smaller face");
            for (int x = 0; x < 16; x += 4)
                for (int z = 0; z < 16; z += 4) {
                    int wx = chunk.getPos().getMinBlockX() + x, wz = 512 + z;
                    var col = copy.getBaseColumn(wx, wz, PlanetEcologyTest.HEIGHT, null);
                    for (int y = -64; y < 320; y++)
                        assertSame(col.getBlock(y), chunk.getBlockState(new BlockPos(wx, y, wz)));
                    int y = copy.planet(1).terrain().surfaceY(f, 512 + x, wz);
                    assertSame(
                            copy.planet(1).terrain().surfaceState(f, 512 + x, wz), col.getBlock(y));
                }
        }
    }

    @Test
    void waypointNamesAndCoordinatesFailClosed() {
        for (String name : List.of("", "has space", "../x", "a".repeat(33)))
            assertThrows(IllegalArgumentException.class, () -> marker(name, 0));
        assertEquals("基地", marker("基地", 0).name());
        var tag = WaypointCodec.encode(marker("home", 0));
        tag.putDouble("u", Double.NaN);
        var nan = tag;
        assertThrows(IllegalArgumentException.class, () -> WaypointCodec.decode(nan));
        tag = WaypointCodec.encode(marker("home", 0));
        tag.putInt("planet", 2);
        var bad = tag;
        assertThrows(IllegalArgumentException.class, () -> WaypointCodec.decode(bad));
        assertThrows(IllegalStateException.class, () -> WaypointSave.fresh(true));
    }

    @Test
    void markerBooksArePrivateAndNeverOverwriteNames() {
        var save = new WaypointSave();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        save.add(a, marker("home", 0));
        save.add(b, marker("home", 1));
        save.select(a, "home");
        assertNull(save.active(b));
        assertEquals(0, save.active(a).planet());
        assertThrows(IllegalArgumentException.class, () -> save.add(a, marker("home", 1)));
        assertEquals(0, save.list(a).get("home").planet());
        save.clear(a);
        assertNull(save.active(a));
        assertEquals(1, save.list(a).size());
        save.select(a, "home");
        save.remove(a, "home");
        assertNull(save.active(a));
        assertEquals(1, save.list(b).size());
    }

    @Test
    void markerCapacityAndUnknownSelectionAreBounded() {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        for (int i = 0; i < 64; i++) save.add(id, marker("p" + i, i % 2));
        assertThrows(IllegalArgumentException.class, () -> save.add(id, marker("overflow", 0)));
        assertThrows(IllegalArgumentException.class, () -> save.select(id, "missing"));
        assertEquals(64, save.list(id).size());
    }

    @Test
    void markerBookAndSelectedTargetSurviveCompressedDiskReload() throws Exception {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        save.add(id, marker("基地", 1));
        save.select(id, "基地");
        var path = Path.of("build/native-exploration/waypoints-test.dat");
        Files.createDirectories(path.getParent());
        NbtIo.writeCompressed(save.save(new CompoundTag(), null), path);
        var loaded =
                WaypointSave.load(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()), null);
        assertEquals(save.list(id), loaded.list(id));
        assertEquals(save.active(id), loaded.active(id));
        Files.writeString(
                path.getParent().resolve("reproduction.json"),
                "{\"gpu_verified\":false,\"runtime_verified\":false,\"marker_disk_roundtrip\":true,\"active_planet\":"
                        + loaded.active(id).planet()
                        + "}\n");
        var tag = save.save(new CompoundTag(), null);
        tag.getCompound("players").getCompound(id.toString()).putString("active", "missing");
        assertThrows(IllegalArgumentException.class, () -> WaypointSave.load(tag, null));
    }

    @Test
    void selectedMarkerUsesRotatingMovingPlanetFrameNotFrozenWorldPosition() {
        var m = GroundEphemeris.spinning(PlanetSystem.model(), true, 0);
        for (int planet = 0; planet < 2; planet++) {
            var point = marker("base", planet);
            var local = GroundTopology.planet(point.pose());
            for (long tick : new long[] {0, 20, 9000})
                assertEquals(
                        0,
                        m.at(planet, tick).local(point.world(m, tick)).sub(local).length(),
                        1e-8);
            assertTrue(point.world(m, 0).sub(point.world(m, 9000)).length() > 1);
        }
    }

    @Test
    void targetPacketRoundTripIncludesClearAndCorrectSession() {
        for (var marker : Arrays.asList(null, marker("基地", 1))) {
            var packet = new WaypointPackets.Target(42, marker);
            var raw = io.netty.buffer.Unpooled.buffer();
            try {
                var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                WaypointPackets.Target.CODEC.encode(buf, packet);
                assertEquals(packet, WaypointPackets.Target.CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
            } finally {
                raw.release();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new WaypointPackets.Target(0, null));
    }
}
