package dev.starfold.waypoint;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MapDestinationTest {
    @Test void movingShipDestinationsReuseWaypointsWithoutEditingOrPersistingTheMarkerBook() {
        var player = UUID.randomUUID(); var save = WaypointSave.fresh(false);
        var home = new Waypoint("home",0,new PlanetShape.Pose(Face.TOP,new Point(300,0,400)));
        var ship = new Waypoint("SHIP-0-1",0,new PlanetShape.Pose(Face.SOUTH,new Point(500,0,900)));
        save.add(player,home); save.select(player,"home");
        var disk=save.save(new CompoundTag(),null);
        save.navigate(player,ship); assertEquals(ship,save.active(player)); assertEquals(1,save.list(player).size());
        assertEquals(disk,save.save(new CompoundTag(),null));
        assertEquals(home,WaypointSave.load(disk,null).active(player));
        save.select(player,"home"); assertEquals(home,save.active(player));
        save.navigate(player,ship); save.clear(player); assertNull(save.active(player));
        var empty=WaypointSave.fresh(false); var before=empty.save(new CompoundTag(),null);
        empty.navigate(player,null); assertEquals(before,empty.save(new CompoundTag(),null));
    }
}
