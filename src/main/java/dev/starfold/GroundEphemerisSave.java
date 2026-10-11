package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.joml.Quaterniond;

/** Separate motion metadata: no changes to either block ledger's save format. */
public final class GroundEphemerisSave extends SavedData {
    public static final String FILE="starfold_ground_ephemeris_v1";
    public GroundEphemeris.Model model;private long tick;
    GroundEphemerisSave(GroundEphemeris.Model model,long tick){if(model==null)throw new IllegalArgumentException("Model");GroundEphemeris.time(tick);this.model=model;this.tick=tick;}
    public long tick(){return tick;}
    public void spin(boolean enabled){model=GroundEphemeris.spinning(model,enabled,tick);setDirty();}
    public void skip(long ticks){if(ticks<0||tick>GroundEphemeris.MAX_TICK-ticks)throw new IllegalStateException("Universe clock exhausted");tick+=ticks;setDirty();}
    public void advance(){if(tick>=GroundEphemeris.MAX_TICK)throw new IllegalStateException("Universe clock exhausted");tick++;setDirty();}
    static GroundEphemerisSave fresh(boolean exists){if(exists)throw new IllegalStateException("Existing ephemeris failed to load; refusing overwrite");var s=new GroundEphemerisSave(GroundSolar.defaults(),0);s.setDirty();return s;}
    private static void require(CompoundTag t,String key,int type){if(!t.contains(key,type))throw new IllegalArgumentException("Missing ephemeris field: "+key);}
    private static double number(CompoundTag t,String key){require(t,key,6);return t.getDouble(key);}
    private static long integer(CompoundTag t,String key){require(t,key,4);return t.getLong(key);}
    private static CompoundTag compound(CompoundTag t,String key){require(t,key,10);return t.getCompound(key);}
    private static Point point(CompoundTag t){return new Point(number(t,"x"),number(t,"y"),number(t,"z"));}
    private static CompoundTag point(Point p){var t=new CompoundTag();t.putDouble("x",p.x());t.putDouble("y",p.y());t.putDouble("z",p.z());return t;}
    private static GroundEphemeris.Spin spin(CompoundTag t){require(t,"enabled",1);var q=compound(t,"reference_orientation");return new GroundEphemeris.Spin(t.getBoolean("enabled"),point(compound(t,"axis")),new Quaterniond(number(q,"x"),number(q,"y"),number(q,"z"),number(q,"w")),number(t,"reference_phase"),number(t,"period_ticks"),integer(t,"reference_tick"),t.contains("frozen_orientation",10)?quaternion(t.getCompound("frozen_orientation")):new Quaterniond());}
    private static Quaterniond quaternion(CompoundTag t){return new Quaterniond(number(t,"x"),number(t,"y"),number(t,"z"),number(t,"w"));}
    private static CompoundTag quaternion(Quaterniond q){var t=new CompoundTag();t.putDouble("x",q.x);t.putDouble("y",q.y);t.putDouble("z",q.z);t.putDouble("w",q.w);return t;}
    private static CompoundTag spin(GroundEphemeris.Spin s){var t=new CompoundTag();t.putBoolean("enabled",s.enabled());t.put("frozen_orientation",quaternion(s.frozen()));t.put("axis",point(s.axis()));var q=new CompoundTag();q.putDouble("x",s.reference().x);q.putDouble("y",s.reference().y);q.putDouble("z",s.reference().z);q.putDouble("w",s.reference().w);t.put("reference_orientation",q);t.putDouble("reference_phase",s.phase());t.putDouble("period_ticks",s.period());t.putLong("reference_tick",s.epoch());return t;}
    public static CompoundTag modelTag(GroundEphemeris.Model m){var t=new CompoundTag();t.put("center",point(m.center()));t.put("axis",point(m.axis()));t.put("radial",point(m.radial()));t.putDouble("radius",m.radius());t.putDouble("period_ticks",m.period());t.putDouble("reference_phase",m.phase());t.putLong("reference_tick",m.epoch());t.put("spin_a",spin(m.a()));t.put("spin_b",spin(m.b()));var sun=new CompoundTag();sun.putBoolean("enabled",m.sun().enabled());sun.put("position",point(m.sun().position()));sun.putDouble("radius",m.sun().radius());t.put("sun",sun);if(m.multiple())t.putBoolean("multiple",true);
        if(!m.more().isEmpty()){var list=new net.minecraft.nbt.ListTag();for(var o:m.more()){var e=new CompoundTag();e.put("center",point(o.center()));e.put("axis",point(o.axis()));e.put("radial",point(o.radial()));e.putDouble("radius",o.radius());e.putDouble("period_ticks",o.period());e.putDouble("reference_phase",o.phase());e.putLong("reference_tick",o.epoch());e.put("spin",spin(o.spin()));list.add(e);}t.put("more",list);}
        if(!m.stars().isEmpty()){var list=new net.minecraft.nbt.ListTag();for(var s:m.stars()){var e=new CompoundTag();e.put("position",point(s.position()));e.putDouble("radius",s.radius());list.add(e);}t.put("stars",list);}
        return t;}
    public static GroundEphemeris.Model model(CompoundTag t){if(t.contains("multiple"))require(t,"multiple",1);return new GroundEphemeris.Model(point(compound(t,"center")),point(compound(t,"axis")),point(compound(t,"radial")),number(t,"radius"),number(t,"period_ticks"),number(t,"reference_phase"),integer(t,"reference_tick"),spin(compound(t,"spin_a")),spin(compound(t,"spin_b")),t.contains("sun")?sun(compound(t,"sun")):GroundSolar.Sun.OFF,t.getBoolean("multiple"),more(t),stars(t));}
    private static java.util.List<GroundEphemeris.Star> stars(CompoundTag t){if(!t.contains("stars"))return java.util.List.of();require(t,"stars",9);var list=t.getList("stars",10);var result=new java.util.ArrayList<GroundEphemeris.Star>();for(int i=0;i<list.size();i++){var e=list.getCompound(i);result.add(new GroundEphemeris.Star(point(compound(e,"position")),number(e,"radius")));}return result;}
    private static java.util.List<GroundEphemeris.Orbit> more(CompoundTag t){if(!t.contains("more"))return java.util.List.of();require(t,"more",9);var list=t.getList("more",10);var result=new java.util.ArrayList<GroundEphemeris.Orbit>();for(int i=0;i<list.size();i++){var e=list.getCompound(i);result.add(new GroundEphemeris.Orbit(point(compound(e,"center")),point(compound(e,"axis")),point(compound(e,"radial")),number(e,"radius"),number(e,"period_ticks"),number(e,"reference_phase"),integer(e,"reference_tick"),spin(compound(e,"spin"))));}return result;}
    private static GroundSolar.Sun sun(CompoundTag t){require(t,"enabled",1);return new GroundSolar.Sun(t.getBoolean("enabled"),point(compound(t,"position")),number(t,"radius"));}
    static GroundEphemerisSave load(CompoundTag t,HolderLookup.Provider lookup){require(t,"format",3);if(t.getInt("format")<1||t.getInt("format")>3)throw new IllegalArgumentException("Ephemeris format");var motion=compound(t,"motion");if(t.getInt("format")==3)require(motion,"sun",10);if(t.getInt("format")>=2){require(compound(motion,"spin_a"),"frozen_orientation",10);require(compound(motion,"spin_b"),"frozen_orientation",10);}return new GroundEphemerisSave(model(motion),integer(t,"tick"));}
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("format",3);t.putLong("tick",tick);t.put("motion",modelTag(model));return t;}
}
