package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import dev.starfold.storage.NativeChunkKeys;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeArchitectureTest {
    private static NativeWaypoint marker() {
        return new NativeWaypoint("基地", 1,
                new NativeGroundTopology.Pose(Face.TOP, new Point(1024, 35, 1024)));
    }

    @Test void everyPersistedLocalKeyRetainsItsOriginalCoordinates() {
        for (int face = 0; face < 6; face++) {
            for (int u = 0; u < 128; u++) {
                for (int v = 0; v < 128; v++) {
                    long oldKey = u + 128L * (v + 128L * face);
                    int x = face * 256 + u;
                    assertEquals(oldKey, NativeChunkKeys.local(x, v));
                    assertEquals(x, NativeChunkKeys.chunkX(oldKey));
                    assertEquals(v, NativeChunkKeys.chunkZ(oldKey));
                    assertEquals(oldKey, NativeGroundSurfaceIndex.key(x, v));
                }
            }
        }
    }

    @Test void gapsAndExtremeCoordinatesCannotBecomeOwners() {
        for (int x : new int[]{Integer.MIN_VALUE, -1, 128, 255, 1408, 1536, Integer.MAX_VALUE}) {
            assertEquals(-1, NativeChunkKeys.local(x, 0));
        }
        assertEquals(-1, NativeChunkKeys.local(0, -1));
        assertEquals(-1, NativeChunkKeys.local(0, 128));
        for (long key : new long[]{-1, 98304, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> NativeChunkKeys.chunkX(key));
            assertThrows(IllegalArgumentException.class, () -> NativeChunkKeys.chunkZ(key));
        }
    }

    @Test void versionOneWireIsIdenticalToTheOriginalFieldSequence() {
        var expectedRaw = Unpooled.buffer();
        var actualRaw = Unpooled.buffer();
        try {
            var expected = new RegistryFriendlyByteBuf(expectedRaw, RegistryAccess.EMPTY);
            var actual = new RegistryFriendlyByteBuf(actualRaw, RegistryAccess.EMPTY);
            var tag = legacyMarker();
            expected.writeLong(42);
            expected.writeBoolean(true);
            expected.writeNbt(tag);
            NativeWaypointPackets.Target.CODEC.encode(actual, new NativeWaypointPackets.Target(42, marker()));
            assertEquals(expected, actual);
            assertEquals(new NativeWaypointPackets.Target(42, marker()),
                    NativeWaypointPackets.Target.CODEC.decode(expected));
            assertEquals("starfold:native_waypoint_v1_target",
                    NativeWaypointPackets.Target.TYPE.id().toString());
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

    @Test void originalSavedBookLoadsAndReencodesWithoutMigration() {
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
        var loaded = NativeWaypointSave.load(original, null);
        assertEquals(marker(), loaded.active(id));
        assertEquals(original, loaded.save(new CompoundTag(), null));
    }

    @Test void failedSelectionsDoNotConsumeBookCapacityOrDirtyTheSave() {
        var save = new NativeWaypointSave();
        for (int i = 0; i <= NativeWaypointSave.MAX_PLAYERS; i++) {
            var id = new UUID(0, i);
            assertThrows(IllegalArgumentException.class, () -> save.select(id, "missing"));
        }
        assertFalse(save.isDirty());
        assertTrue(save.save(new CompoundTag(), null).getCompound("players").isEmpty());
        save.add(UUID.randomUUID(), marker());
        assertTrue(save.isDirty());
    }

    @Test void separatedLayersDoNotReintroduceReverseDependencies() throws Exception {
        // Structural guard only. Behavioral compatibility is tested separately above.
        var root = Path.of("src/main/java/dev/starfold");
        for (String file : new String[]{"NativePlanetStorage.java", "NativePlanetAccess.java"}) {
            assertFalse(Files.readString(root.resolve(file)).contains("NativeGroundSurfaceIndex"), file);
        }
        String client = Files.readString(root.resolve("NativeWaypointClient.java"));
        assertFalse(client.contains("NativeWaypointServer"));
        assertFalse(client.contains("NativeWaypointSave"));
        String packet = Files.readString(root.resolve("NativeWaypointPackets.java"));
        assertFalse(packet.contains("NativeWaypointSave"));
        assertFalse(packet.contains("ServerPlayer"));
        String keys = Files.readString(root.resolve("storage/NativeChunkKeys.java"));
        assertFalse(keys.contains("net.minecraft"));
        assertFalse(keys.contains("net.fabricmc"));
    }
}
