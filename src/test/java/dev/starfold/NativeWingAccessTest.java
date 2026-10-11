package dev.starfold;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class NativeWingAccessTest {
    @Test void bothPairedFlightsAndTheirTwoBlockHeadroomClearTheShippedHull() throws Exception {
        var root=Path.of("src/main/resources/data/starfold/structure");
        var hull=NbtIo.readCompressed(root.resolve("starter_ship.nbt"),NbtAccounter.unlimitedHeap());
        var weapon=NbtIo.readCompressed(root.resolve("starter_ship_weapon.nbt"),NbtAccounter.unlimitedHeap());
        for(boolean armed:new boolean[]{false,true}){
        var nbt=armed?NativeStarterShip.mergeWeapon(hull,weapon):hull;
        var palette=nbt.getList("palette",Tag.TAG_COMPOUND);
        var solids=new ArrayList<AABB>();
        for(var entry:nbt.getList("blocks",Tag.TAG_COMPOUND)){
            var block=(net.minecraft.nbt.CompoundTag)entry;
            if(palette.getCompound(block.getInt("state")).getString("Name").equals("minecraft:air"))continue;
            var p=block.getList("pos",Tag.TAG_INT);double x=p.getInt(0)-20,y=p.getInt(1),z=p.getInt(2)-11;
            // Full cells deliberately overestimate slab and decorative-block obstruction.
            solids.add(new AABB(x,y,z,x+1,y+1,z+1));
        }
        assertEquals(armed?2370:2347,solids.size());
        for(int side:new int[]{-1,1})for(double drop:new double[]{1,2.5,5,7.5,10}){
            var hinge=new Vector3d(-4,5,side<0?-4:5);
            var flight=NativeAirstairGeometry.flight(hinge,new Vector3d(0,0,side),new Vector3d(0,-1,0),2,(x,z)->5-drop);
            assertNotNull(flight);
            for(var tread:flight.boxes()){
                var envelope=new AABB(tread.minX,tread.minY,tread.minZ,tread.maxX,tread.maxY+2,tread.maxZ);
                assertFalse(solids.stream().anyMatch(envelope::intersects),()->"Hull obstructs flight/headroom "+envelope);
            }
        }
        }
    }
}
