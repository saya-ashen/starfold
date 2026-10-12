package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundTopology;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class LaunchPerPlanetTest {
    private Waypoint marker(int planet, double x) {
        return new Waypoint(
                Waypoint.LAUNCH,
                planet,
                new GroundTopology.Pose(
                        CubeSurfaceTopology.Face.TOP, new BentStripGeometry.Point(x, 30, 500)));
    }

    @Test
    void landingOnBDoesNotOverwriteAAndRetakingOnlyReplacesB() {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        var a = marker(0, 100);
        var b = marker(1, 200);
        var b2 = marker(1, 300);
        save.launch(id, a);
        save.launch(id, b);
        save.launch(id, b2);
        assertEquals(a, save.active(id, 0));
        assertEquals(b2, save.active(id, 1));
        assertEquals(b2, save.active(id, -1));
        assertEquals(b2, save.active(id, 7));
        assertNull(save.active(UUID.randomUUID(), 0));
        var loaded = WaypointSave.load(save.save(new CompoundTag(), null), null);
        assertEquals(a, loaded.active(id, 0));
        assertEquals(b2, loaded.active(id, 1));
        assertEquals(b2, loaded.active(id, -1));
        assertEquals(2, loaded.launches(id).size());
    }

    @Test
    void legacySingleLaunchMigratesByItsOwnPlanet() {
        var id = UUID.randomUUID();
        var b = marker(1, 200);
        var entry = new CompoundTag();
        entry.put("points", new ListTag());
        entry.putString("active", "");
        entry.put("launch", WaypointCodec.encode(b));
        var players = new CompoundTag();
        players.put(id.toString(), entry);
        var root = new CompoundTag();
        root.putInt("format", 1);
        root.put("players", players);
        var loaded = WaypointSave.load(root, null);
        assertEquals(b, loaded.active(id, 1));
        loaded.launch(id, marker(0, 100));
        assertEquals(b, loaded.active(id, 1));
        assertEquals(
                2,
                WaypointSave.load(loaded.save(new CompoundTag(), null), null).launches(id).size());
    }

    @Test
    void manualSelectionStillWinsAndClearRestoresContextualLaunch() {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        var a = marker(0, 100);
        var b = marker(1, 200);
        var manual = new Waypoint("base", 1, b.pose());
        save.launch(id, a);
        save.launch(id, b);
        save.add(id, manual);
        save.select(id, "base");
        assertEquals(manual, save.active(id, 0));
        save.clear(id);
        assertEquals(a, save.active(id, 0));
    }

    @Test
    void duplicatePlanetAndInvalidRecentFailClosed() {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        save.launch(id, marker(0, 100));
        var tag = save.save(new CompoundTag(), null);
        var entry = tag.getCompound("players").getCompound(id.toString());
        entry.getList("launches", Tag.TAG_COMPOUND).add(WaypointCodec.encode(marker(0, 200)));
        var duplicate = tag;
        assertThrows(IllegalArgumentException.class, () -> WaypointSave.load(duplicate, null));
        tag = save.save(new CompoundTag(), null);
        tag.getCompound("players").getCompound(id.toString()).putInt("recentLaunch", 9);
        var broken = tag;
        assertThrows(IllegalArgumentException.class, () -> WaypointSave.load(broken, null));
    }
}
