package dev.starfold;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Explicit saved generator opt-in. Merely installing this mod never adds another planet. */
public final class NativePlanetSystem {
    // The client must opt in from matching layout metadata; a multi-planet model alone
    // is insufficient because alpha.67 also has two planets, in separate dimensions.
    private static java.util.function.Predicate<Level> clientShared=l->false;
    public static void clientShared(java.util.function.Predicate<Level> reader){clientShared=java.util.Objects.requireNonNull(reader);}
    public static boolean shared(Level level){return level instanceof ServerLevel server?server.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator:level!=null&&level.isClientSide&&clientShared.test(level);}
    public static int planet(net.minecraft.world.entity.Entity entity){return shared(entity.level())?NativePlanetStorage.planet(entity.getX(),entity.getZ()):planet(entity.level());}
    public static int planet(Level level){return level.dimension().equals(NativeGroundServer.ROCK)?1:0;}
    public static boolean terrain(Level level){return level!=null&&(level.dimension().equals(NativeGroundServer.PLANET)||level.dimension().equals(NativeGroundServer.ROCK));}
    public static ServerLevel level(ServerLevel current,int planet){NativePlanetShape.valid(planet);if(shared(current))return current;GroundCosmos.valid(planet);var result=current.getServer().getLevel(planet==0?NativeGroundServer.PLANET:NativeGroundServer.ROCK);if(result==null)throw new IllegalStateException("Missing native planet "+planet);return result;}
    public static boolean multiple(ServerLevel current){if(!terrain(current))return false;if(shared(current))return true;if(!(current.getChunkSource().getGenerator() instanceof NativePlanetChunkGenerator g)||!g.multiple())return false;for(int id=0;id<2;id++){var l=level(current,id);if(!(l.getChunkSource().getGenerator() instanceof NativePlanetChunkGenerator other)||!other.multiple()||other.version()!=(id==0?4:5))throw new IllegalStateException("Incomplete saved native system");}return true;}
    public static ServerLevel authority(ServerLevel current){return multiple(current)?level(current,0):current;}
    public static GroundEphemeris.Model model(){return model(2);}
    /** Every planet after a sun's first circles further out, a ring apart, turning as Kepler would have it; suns
     *  stand a gap apart in a row. Until the layout of a universe is generated, this is where everything is. */
    public static final double RING=12288,STAR_GAP=49152;
    public static GroundEphemeris.Model model(int count){return model(new int[]{count});}
    /** A universe of one sun for each entry, with that many planets about it, in storage order. The first sun has
     *  the pair the system began with. */
    public static GroundEphemeris.Model model(int[] groups){
        int count=0;for(int group:groups){if(group<1)throw new IllegalArgumentException("Star without planets");count+=group;}
        if(groups.length<1||groups[0]<2||count>NativePlanetStorage.MAX_PLANETS)throw new IllegalArgumentException("System planet count "+count);var m=GroundSolar.defaults();var more=new java.util.ArrayList<GroundEphemeris.Orbit>();var stars=new java.util.ArrayList<GroundEphemeris.Star>();
        // Neighbouring suns stand a gap apart: the outermost rings of both, and room between them, must fit in it.
        for(int star=1;star<groups.length;star++){double reach=m.radius()+(groups[star]-1)*RING,before=m.radius()+Math.max(0,groups[star-1]-(star==1?2:1))*RING;if(reach+before+8192>STAR_GAP)throw new IllegalArgumentException("Suns "+(star-1)+" and "+star+" have too many planets for the gap between them");}
        for(int star=0,id=0;star<groups.length;star++){var centre=m.center().add(new dev.starfold.BentStripGeometry.Point(star*STAR_GAP,0,0));if(star>0)stars.add(new GroundEphemeris.Star(centre,m.sun().radius()));
            for(int place=0;place<groups[star];place++,id++){if(id<2)continue;double radius=m.radius()+(star==0?place-1:place)*RING;more.add(new GroundEphemeris.Orbit(centre,m.axis(),m.radial(),radius,m.period()*Math.pow(radius/m.radius(),1.5),id*2.1,m.epoch(),m.b()));}}
        return new GroundEphemeris.Model(m.center(),m.axis(),m.radial(),m.radius(),m.period(),m.phase(),m.epoch(),m.a(),m.b(),m.sun(),true,more,stars);
    }
    /** How many planets this level's saved system has, and how they are shared out among its suns. */
    public static int count(ServerLevel current){return current.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system?system.count():2;}
    /** The model of everything this level holds: its home system and, where it has one, its universe beyond. */
    public static GroundEphemeris.Model model(ServerLevel current){if(!(current.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system))return model();var home=system.homeModel();var universe=NativeUniverse.of(current);return universe==null?home:universe.model(home);}
    /** A, B, C … in storage order. */
    private static volatile java.util.List<NativePlanetIdentity> identities=java.util.List.of();
    public static void identities(java.util.List<NativePlanetIdentity> values){identities=java.util.List.copyOf(values);}
    public static java.util.List<NativePlanetIdentity> identities(){return identities;}
    public static NativePlanetIdentity identity(int planet){var values=identities;return planet>=0&&planet<values.size()?values.get(planet):new NativePlanetIdentity(java.util.Optional.empty(),planet==0?"temperate":"rocky",java.util.Optional.empty());}
    private static volatile java.util.List<String> names=java.util.List.of();
    public static void names(java.util.List<String> saved){names=java.util.List.copyOf(saved);}
    public static java.util.List<String> names(){return names;}
    public static String name(int planet){var saved=names;return planet>=0&&planet<saved.size()?saved.get(planet):defaultName(planet);}
    public static String defaultName(int planet){return planet<26?String.valueOf((char)('A'+planet)):"P"+planet;}
    public static NativeGroundDynamics.Terrain terrain(ServerLevel current,net.minecraft.server.level.ServerPlayer player,GroundSpace.Config mask){return new NativeGroundDynamics.Terrain(){
        public boolean clear(NativeGroundTopology.Pose p){return clear(player==null?planet(current):planet(player),p);}
        public double clearance(NativeGroundTopology.Pose p){return clearance(player==null?planet(current):planet(player),p);}
        public boolean clear(int id,NativeGroundTopology.Pose p){return new NativeGroundTerrain(multiple(current)?level(current,id):current,player,mask,id).clear(p);}
        public double clearance(int id,NativeGroundTopology.Pose p){return new NativeGroundTerrain(multiple(current)?level(current,id):current,player,mask,id).clearance(p);}
    };}
    /** The saved shape of a planet of this level; the standard one where the level's generator does not say. */
    public static NativePlanetShape shape(ServerLevel current,int planet){NativePlanetShape.valid(planet);var source=(multiple(current)?level(current,planet):current).getChunkSource().getGenerator();return source instanceof NativeSystemChunkGenerator system?system.planet(planet).terrain().shape():source instanceof NativePlanetChunkGenerator single?single.terrain().shape():NativePlanetShape.STANDARD;}
    private NativePlanetSystem(){}
}
