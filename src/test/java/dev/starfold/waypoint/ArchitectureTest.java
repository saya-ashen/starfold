package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.render.GroundSurfaceIndex;
import dev.starfold.storage.ChunkKeys;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ArchitectureTest {
    private static Waypoint marker() {
        return new Waypoint("基地", 1, new PlanetShape.Pose(Face.TOP, new Point(1024, 35, 1024)));
    }

    @Test
    void everyPersistedLocalKeyRetainsItsOriginalCoordinates() {
        for (int face = 0; face < 6; face++) {
            for (int u = 0; u < 128; u++) {
                for (int v = 0; v < 128; v++) {
                    long oldKey = u + 128L * (v + 128L * face);
                    int x = face * 256 + u;
                    assertEquals(oldKey, ChunkKeys.local(x, v));
                    assertEquals(x, ChunkKeys.chunkX(oldKey));
                    assertEquals(v, ChunkKeys.chunkZ(oldKey));
                    assertEquals(oldKey, GroundSurfaceIndex.key(x, v));
                }
            }
        }
    }

    @Test
    void gapsAndExtremeCoordinatesCannotBecomeOwners() {
        for (int x : new int[] {Integer.MIN_VALUE, -1, 128, 255, 1408, 1536, Integer.MAX_VALUE}) {
            assertEquals(-1, ChunkKeys.local(x, 0));
        }
        assertEquals(-1, ChunkKeys.local(0, -1));
        assertEquals(-1, ChunkKeys.local(0, 128));
        for (long key : new long[] {-1, 98304, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> ChunkKeys.chunkX(key));
            assertThrows(IllegalArgumentException.class, () -> ChunkKeys.chunkZ(key));
        }
    }

    @Test
    void versionOneWireIsIdenticalToTheOriginalFieldSequence() {
        var expectedRaw = Unpooled.buffer();
        var actualRaw = Unpooled.buffer();
        try {
            var expected = new RegistryFriendlyByteBuf(expectedRaw, RegistryAccess.EMPTY);
            var actual = new RegistryFriendlyByteBuf(actualRaw, RegistryAccess.EMPTY);
            var tag = legacyMarker();
            expected.writeLong(42);
            expected.writeBoolean(true);
            expected.writeNbt(tag);
            WaypointPackets.Target.CODEC.encode(actual, new WaypointPackets.Target(42, marker()));
            assertEquals(expected, actual);
            assertEquals(
                    new WaypointPackets.Target(42, marker()),
                    WaypointPackets.Target.CODEC.decode(expected));
            assertEquals(
                    "starfold:native_waypoint_v1_target",
                    WaypointPackets.Target.TYPE.id().toString());
        } finally {
            expectedRaw.release();
            actualRaw.release();
        }
    }

    private static CompoundTag legacyMarker() {
        var tag = new CompoundTag();
        tag.putString("name", "基地");
        tag.putInt("planet", 1);
        tag.putInt("face", Face.TOP.ordinal());
        tag.putDouble("u", 1024);
        tag.putDouble("h", 35);
        tag.putDouble("v", 1024);
        return tag;
    }

    @Test
    void originalSavedBookLoadsAndReencodesWithoutMigration() {
        var id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var points = new ListTag();
        points.add(legacyMarker());
        var entry = new CompoundTag();
        entry.put("points", points);
        entry.putString("active", "基地");
        var players = new CompoundTag();
        players.put(id.toString(), entry);
        var original = new CompoundTag();
        original.putInt("format", 1);
        original.put("players", players);
        var loaded = WaypointSave.load(original, null);
        assertEquals(marker(), loaded.active(id));
        assertEquals(original, loaded.save(new CompoundTag(), null));
    }

    @Test
    void failedSelectionsDoNotConsumeBookCapacityOrDirtyTheSave() {
        var save = new WaypointSave();
        for (int i = 0; i <= WaypointSave.MAX_PLAYERS; i++) {
            var id = new UUID(0, i);
            assertThrows(IllegalArgumentException.class, () -> save.select(id, "missing"));
        }
        assertFalse(save.isDirty());
        assertTrue(save.save(new CompoundTag(), null).getCompound("players").isEmpty());
        save.add(UUID.randomUUID(), marker());
        assertTrue(save.isDirty());
    }

    @Test
    void separatedLayersDoNotReintroduceReverseDependencies() throws Exception {
        // Structural guard only. Behavioral compatibility is tested separately above.
        for (String file : new String[] {"PlanetStorage.java", "PlanetAccess.java"}) {
            assertFalse(
                    Sources.text(file.substring(0, file.length() - 5))
                            .contains("GroundSurfaceIndex"),
                    file);
        }
        var client = Sources.text("WaypointClient");
        assertFalse(client.contains("WaypointServer"));
        assertFalse(client.contains("WaypointSave"));
        var packet = Sources.text("WaypointPackets");
        assertFalse(packet.contains("WaypointSave"));
        assertFalse(packet.contains("ServerPlayer"));
        var keys = Sources.text("ChunkKeys");
        assertFalse(keys.contains("net.minecraft"));
        assertFalse(keys.contains("net.fabricmc"));
    }
}
