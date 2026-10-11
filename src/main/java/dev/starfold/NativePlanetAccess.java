package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Explicit layout at the real Level boundary. Never infer a layout from a large X alone.
 * Legacy worlds retain their old interpretation, including empty storage gaps.
 */
public enum NativePlanetAccess {
    LEGACY, SHARED;

    public static NativePlanetAccess of(Level level){return NativePlanetSystem.shared(level)?SHARED:LEGACY;}
    public boolean inChart(BlockPos pos){
        if(this==LEGACY)return NativeGroundSeams.inChart(pos);
        int planet=NativePlanetStorage.find(pos.getX(),pos.getZ());return planet>=0&&NativeGroundSeams.inChart(NativePlanetShape.of(planet),NativePlanetStorage.local(planet,pos));
    }
    public NativeGroundTopology.Address address(BlockPos pos){
        return this==SHARED?NativePlanetStorage.address(pos).oriented():NativeGroundTopology.address(pos);
    }
    public Cell cell(BlockPos pos){
        return NativeGroundTopology.cell(this==SHARED?NativePlanetStorage.local(NativePlanetStorage.planet(pos),pos):pos);
    }
    public NativeGroundTopology.Notification notification(BlockPos target,BlockPos source){
        return this==SHARED?NativePlanetStorage.notification(target,source):NativeGroundTopology.notification(target,source);
    }
    public Point feet(int planet,NativeGroundTopology.Pose pose){
        NativePlanetShape.valid(planet);return this==SHARED?NativePlanetStorage.feet(planet,pose):NativeGroundTopology.feet(pose);
    }
    /** A body anchor is already a chart point, not entity feet: no 0.9 centre offset belongs here. */
    public Point bodyAnchor(int planet,NativeGroundTopology.Pose pose){NativePlanetShape.valid(planet);return origin(planet,pose.face().ordinal()).add(pose.point());}
    public NativeGroundTopology.Pose body(Point feet){
        return this==SHARED?NativePlanetStorage.body(NativePlanetStorage.planet(feet.x(),feet.z()),feet):NativeGroundTopology.body(feet);
    }
    public long key(int chunkX,int chunkZ){return this==SHARED?NativePlanetStorage.key(chunkX,chunkZ):dev.starfold.storage.NativeChunkKeys.local(chunkX,chunkZ);}
    public net.minecraft.world.level.ChunkPos chunk(long key){
        if(key<0||key>=(this==SHARED?(long)NativePlanetShape.count():1L)*NativePlanetStorage.CHUNKS_PER_PLANET)throw new IllegalArgumentException("Layout chunk key");
        return NativePlanetStorage.chunk(key);
    }
    /** The planet whose storage holds a place. A legacy level holds one. */
    public int storagePlanet(double x,double z){return this==SHARED?NativePlanetStorage.planet(x,z):0;}
    public int storagePlanet(BlockPos pos){return storagePlanet(pos.getX(),pos.getZ());}
    /** Shape of the planet whose storage holds a place. A legacy level holds one planet of the standard size. */
    public NativePlanetShape shape(double x,double z){return this==SHARED?NativePlanetShape.of(NativePlanetStorage.planet(x,z)):NativePlanetShape.STANDARD;}
    public NativePlanetShape shape(BlockPos pos){return shape(pos.getX(),pos.getZ());}
    /** The face whose chart a place is in: told from x alone, as the faces of every planet stand along x. */
    public int face(double x){return NativeGroundCoordinates.face(x-(this==SHARED?NativePlanetStorage.columnOffset(x):0));}
    public Point originAt(double x,double z){return origin(storagePlanet(x,z),face(x));}
    public Point chart(Point storage){return storage.sub(originAt(storage.x(),storage.z()));}
    public boolean samePlanet(double ax,double az,double bx,double bz){return this!=SHARED||NativePlanetStorage.find(ax,az)==NativePlanetStorage.find(bx,bz);}
    /** Where a place of another face is drawn in the chart of the face an observer is on, or null. */
    public Point image(double observerX,double observerZ,Point target){
        if(!samePlanet(observerX,observerZ,target.x(),target.z()))return null;
        var root=dev.starfold.CubeSurfaceTopology.Face.values()[face(observerX)];
        var tile=new NativeChart(shape(observerX,observerZ),root).tile(dev.starfold.CubeSurfaceTopology.Face.values()[face(target.x())]);
        return tile==null?null:originAt(observerX,observerZ).add(tile.point(chart(target)));
    }
    /** A place in the middle of a face of a planet: an observer there sees that face's chart. */
    public Point middle(int planet,int face){var o=origin(planet,face);double half=(this==SHARED?NativePlanetShape.of(planet):NativePlanetShape.STANDARD).half();return new Point(o.x()+half,o.y(),o.z()+half);}
    public Point origin(int planet,int face){return NativeGroundCoordinates.origin(face).add(this==SHARED?NativePlanetStorage.origin(planet):new Point(0,0,0));}
}
