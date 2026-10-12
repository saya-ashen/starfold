package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.Sources;
import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundSpace;
import dev.starfold.flight.GroundDynamics;
import dev.starfold.flight.GroundFlightPackets;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundCoordinates;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Protocol/storage/source contracts, NOT an interactive Minecraft runtime acceptance. */
class SystemRuntimeTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void selectedPresetHasExactlyOneNativePlanetLevelAndBothSavedPolicies() throws Exception {
        var dims =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/data/starfold/worldgen/world_preset/native_system.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("dimensions");
        assertEquals(4, dims.size());
        assertFalse(dims.has("starfold:native_rock"));
        var encoded = dims.getAsJsonObject("starfold:native_planet").getAsJsonObject("generator");
        assertEquals("starfold:native_system", encoded.get("type").getAsString());
        var g =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(
                                RegistryOps.create(JsonOps.INSTANCE, PlanetEcologyTest.lookup),
                                encoded)
                        .getOrThrow();
        assertEquals(4, g.planet(0).version());
        assertEquals(5, g.planet(1).version());
        assertNotEquals(g.planet(0).seed(), g.planet(1).seed());
        assertFalse(g.planet(0).multiple());
        assertFalse(g.planet(1).multiple());
        // The setup presets stay loadable by id but are not offered in the world creation screen.
        assertFalse(
                java.nio.file.Files.exists(
                        java.nio.file.Path.of(
                                "src/main/resources/data/minecraft/tags/worldgen/world_preset/extended.json")));
        var original =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/data/starfold/dimension/native_planet.json")))
                        .getAsJsonObject();
        assertEquals(2, original.getAsJsonObject("generator").get("terrain_version").getAsInt());
        var evidence = Path.of("build/native-system");
        Files.createDirectories(evidence);
        Files.writeString(
                evidence.resolve("reproduction.json"),
                "{\"gpu_verified\":false,\"runtime_verified\":false,\"single_level_preset\":true,\"old_default_version\":2,\"native_dimensions_in_preset\":1,\"storage_layout\":\"six_face_x32768_v1\"}\n");
    }

    @Test
    void setupDistinguishesSharedAndLegacyLayoutsEvenWithSameTwoPlanetModel() {
        var model = PlanetSystem.model();
        for (int id = 0; id < 2; id++) {
            var state =
                    GroundDynamics.local(
                            100,
                            id,
                            new GroundTopology.Pose(Face.TOP, new Point(1024, 1000, 1024)),
                            GroundEphemeris.ZERO,
                            new org.joml.Quaterniond(),
                            true,
                            GroundDynamics.OFF);
            for (boolean shared : new boolean[] {false, true}) {
                var packet =
                        new GroundFlightPackets.Setup(
                                23,
                                model,
                                GroundSpace.DEFAULT_MASK,
                                state,
                                true,
                                GroundAir.DEFAULT,
                                shared);
                var raw = io.netty.buffer.Unpooled.buffer();
                try {
                    var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                    GroundFlightPackets.Setup.CODEC.encode(buf, packet);
                    var copy = GroundFlightPackets.Setup.CODEC.decode(buf);
                    assertEquals(packet, copy);
                    assertEquals(shared, copy.shared());
                    assertEquals(0, buf.readableBytes());
                    assertEquals("starfold:native_ground_v4_setup", copy.type().id().toString());
                } finally {
                    raw.release();
                }
            }
        }
    }

    @Test
    void blockEditsAndSurfaceSaveDataAreIsolatedWithinOneStorageLayout() {
        var a = new GroundSurfaceIndex(0);
        var b = new GroundSurfaceIndex(1);
        var local = new BlockPos(1024, 250, 1024);
        var remote = local.offset(32768, 0, 0);
        a.mark(local);
        b.mark(remote);
        long key = GroundSurfaceIndex.key(64, 64);
        assertTrue(a.coverage().get((int) key));
        assertTrue(b.coverage().get((int) key));
        var values = new int[GroundChunkSnapshot.VALUES];
        Arrays.fill(values, Block.getId(Blocks.AIR.defaultBlockState()));
        values[16 * 16 * (250 + 64)] = Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        b.update(GroundChunkSnapshot.fromStates(0, 64, 64, 5, values));
        assertNull(a.surface(key));
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), b.surface(key).state(0, 0));
        var loaded =
                GroundSurfaceIndex.load(b.save(new net.minecraft.nbt.CompoundTag(), null), null);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), loaded.surface(key).state(0, 0));
        assertTrue(loaded.dirty(key));
        assertNull(
                GroundSurfaceIndex.load(a.save(new net.minecraft.nbt.CompoundTag(), null), null)
                        .surface(key));
        var other = new GroundSurfaceIndex(0);
        other.mark(remote);
        assertTrue(other.coverage().isEmpty());
    }

    @Test
    void invalidationStampsAndTicketsDoNotCollideBetweenPlanets() {
        var versions = new GroundViewVersions();
        var access = PlanetAccess.SHARED;
        long a = access.key(64, 64), b = access.key(2112, 64);
        assertNotEquals(a, b);
        versions.changed(a);
        long before = versions.get(a);
        versions.changed(b);
        assertEquals(before, versions.get(a));
        assertTrue(versions.get(b) > before);
        assertEquals(2112, access.chunk(b).x);
        assertThrows(IllegalArgumentException.class, () -> PlanetAccess.LEGACY.chunk(b));
    }

    @Test
    void renderingAndPhysicsChartTranslationsAreIdenticalOnBothPlanets() {
        for (var face : Face.values())
            for (int id = 0; id < 2; id++) {
                var access = PlanetAccess.SHARED;
                var point = new Point(123, 1000, 456);
                var pose = new GroundTopology.Pose(face, point);
                var world = access.feet(id, pose).add(new Point(0, .9, 0));
                assertEquals(point, world.sub(access.origin(id, face.ordinal())));
                var restored = access.body(access.feet(id, pose));
                assertEquals(pose.face(), restored.face());
                assertEquals(0, pose.point().sub(restored.point()).length(), 1e-10);
                for (var source : Face.values())
                    assertEquals(
                            GroundCoordinates.origin(source.ordinal())
                                    .sub(GroundCoordinates.origin(face.ordinal())),
                            access.origin(id, source.ordinal())
                                    .sub(access.origin(id, face.ordinal())));
            }
    }

    @Test
    void sharedFlightBranchCannotInvokeCrossLevelTeleportAndClientBindsLayoutBeforeCaches()
            throws Exception {
        var server = Sources.text("GroundFlightServer");
        int start = server.indexOf("if(access==PlanetAccess.SHARED)");
        int end = server.indexOf("else if(destination!=", start);
        assertTrue(start >= 0 && end > start);
        var branch = server.substring(start, end);
        assertTrue(branch.contains("player.connection.teleport("));
        assertFalse(branch.contains("player.teleportTo("));
        var system = Sources.text("PlanetSystem");
        assertTrue(system.contains("if(shared(current))return current"));
        var client = Sources.text("GroundFlightClient");
        assertTrue(
                client.indexOf("shared=packet.shared()")
                        < client.indexOf("GroundViewClient.bindSession(level,session)"));
        var views = Sources.text("GroundViewClient");
        assertTrue(views.contains("clear();level=world;session=id;boundPlanet="));
        assertTrue(
                Sources.text("GroundViewServer")
                        .contains("offset+face*GroundCoordinates.STRIDE/16+u"));
    }
}
