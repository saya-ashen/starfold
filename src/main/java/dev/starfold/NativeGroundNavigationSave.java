package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.joml.Quaterniond;
import java.util.*;

/** Player navigation only. Native chunk/BlockEntity files remain the sole building authority. */
public final class NativeGroundNavigationSave extends SavedData {
    public static final String FILE="starfold_native_ground_navigation_v1";
    private final Map<UUID,NativeGroundDynamics.State> players=new HashMap<>();
    static NativeGroundNavigationSave fresh(boolean exists){if(exists)throw new IllegalStateException("Existing native navigation failed to load; refusing overwrite");return new NativeGroundNavigationSave();}
    public static NativeGroundNavigationSave get(ServerLevel level){if(!NativeGroundServer.is(level))throw new IllegalArgumentException("Native dimension");var storage=NativePlanetSystem.authority(level).getDataStorage();return storage.computeIfAbsent(new Factory<>(()->fresh(((dev.starfold.mixin.PlanetSpaceDataAccess)storage).starfold$spaceDataFile(FILE).exists()),NativeGroundNavigationSave::load,null),FILE);}
    public NativeGroundDynamics.State find(UUID id){return players.get(id);}
    /** Someone who died has no place to be put back in: they begin again where the world next puts them. */
    public void remove(UUID id){if(players.remove(id)!=null)setDirty();}
    public void put(UUID id,NativeGroundDynamics.State state){if(id==null||state==null)throw new IllegalArgumentException("Native navigation entry");if(!players.containsKey(id)&&players.size()>=1024)throw new IllegalStateException("Native navigation capacity");players.put(id,state);setDirty();}
    private static void require(CompoundTag t,String key,int type){if(!t.contains(key,type))throw new IllegalArgumentException("Missing native navigation: "+key);}
    private static double number(CompoundTag t,String key){require(t,key,6);return t.getDouble(key);}
    private static boolean flag(CompoundTag t,String key){require(t,key,1);return t.getBoolean(key);}
    private static CompoundTag tag(CompoundTag t,String key){require(t,key,10);return t.getCompound(key);}
    private static Point point(CompoundTag t){return new Point(number(t,"x"),number(t,"y"),number(t,"z"));}
    private static CompoundTag point(Point point){var t=new CompoundTag();t.putDouble("x",point.x());t.putDouble("y",point.y());t.putDouble("z",point.z());return t;}
    static CompoundTag encode(NativeGroundDynamics.State state){
        var t=new CompoundTag();if(state.planet()!=0)t.putInt("planet",state.planet());t.putLong("tick",state.tick());t.putBoolean("space",state.space());t.putBoolean("flying",state.flying());t.putBoolean("braking",state.braking());t.put("position",point(state.position()));t.put("velocity",point(state.velocity()));
        var q=state.orientation();var aim=point(new Point(q.x,q.y,q.z));aim.putDouble("w",q.w);t.put("orientation",aim);
        var g=new CompoundTag();if(state.guidance().target()!=0)g.putInt("target",state.guidance().target());g.putBoolean("relative_brake",state.guidance().relativeBrake());g.putBoolean("landing",state.guidance().landing());t.put("guidance",g);
        if(!state.space()){var p=new CompoundTag();p.putInt("face",state.local().face().ordinal());p.put("point",point(state.local().point()));t.put("local",p);}
        return t;
    }
    static NativeGroundDynamics.State decode(CompoundTag t){
        require(t,"tick",4);boolean space=flag(t,"space");NativeGroundTopology.Pose pose=null;
        if(space){if(t.contains("local"))throw new IllegalArgumentException("Space contains native local body");}
        else{var p=tag(t,"local");require(p,"face",3);int face=p.getInt("face");if(face<0||face>=6)throw new IllegalArgumentException("Native navigation face");pose=new NativeGroundTopology.Pose(Face.values()[face],point(tag(p,"point")));}
        var q=tag(t,"orientation");var g=tag(t,"guidance");if(g.contains("target"))require(g,"target",3);if(t.contains("planet"))require(t,"planet",3);var options=new GroundGuidance.Options(g.getInt("target"),flag(g,"relative_brake"),flag(g,"landing"));
        return new NativeGroundDynamics.State(t.getLong("tick"),space,point(tag(t,"position")),point(tag(t,"velocity")),new Quaterniond(number(q,"x"),number(q,"y"),number(q,"z"),number(q,"w")),pose,flag(t,"flying"),flag(t,"braking"),options,t.getInt("planet"));
    }
    static NativeGroundNavigationSave load(CompoundTag tag,HolderLookup.Provider lookup){require(tag,"format",3);if(tag.getInt("format")!=1)throw new IllegalArgumentException("Native navigation format");var all=tag(tag,"players");if(all.getAllKeys().size()>1024)throw new IllegalArgumentException("Native navigation capacity");var saved=new NativeGroundNavigationSave();for(String key:all.getAllKeys()){var id=UUID.fromString(key);if(!id.toString().equals(key))throw new IllegalArgumentException("Noncanonical native UUID");saved.players.put(id,decode(tag(all,key)));}return saved;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("format",1);var all=new CompoundTag();players.forEach((id,state)->all.put(id.toString(),encode(state)));tag.put("players",all);return tag;}
}
