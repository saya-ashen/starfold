package dev.starfold;
import dev.starfold.CubeSurfaceTopology.Face;
import java.util.*;

/** Bounded client-only memo of immutable generator columns/nodes. No edits are stored here.
 * The single source of a body's generated appearance for every derived far view. */
public final class NativePlanetHeightCache {
    private final NativePlanetTerrain terrain;private final LinkedHashMap<Long,int[]> columns=new LinkedHashMap<>(16,.75f,true);private final LinkedHashMap<Long,Double> nodes=new LinkedHashMap<>(16,.75f,true);private long samples;
    public NativePlanetHeightCache(NativePlanetTerrain terrain){this.terrain=Objects.requireNonNull(terrain);}
    public int ground(Face face,int u,int v){long key=u/16+128L*(v/16+128L*face.ordinal());var tile=columns.get(key);if(tile==null){tile=new int[256];Arrays.fill(tile,Integer.MIN_VALUE);columns.put(key,tile);if(columns.size()>128)columns.remove(columns.keySet().iterator().next());}int i=(u&15)+16*(v&15);if(tile[i]==Integer.MIN_VALUE){tile[i]=terrain.version()==NativePlanetTerrain.ECOLOGY_VERSION?terrain.surfaceY(face,u,v):terrain.groundY(face,u,v);samples++;}return tile[i];}
    public double node(Face face,int u,int v){long key=u+2049L*(v+2049L*face.ordinal());var value=nodes.get(key);if(value==null){value=terrain.elevation(face,u,v);samples++;nodes.put(key,value);if(nodes.size()>16384)nodes.remove(nodes.keySet().iterator().next());}return value;}
    public NativePlanetTerrain.Sea sea(){return terrain.sea();}
    public NativePlanetShape shape(){return terrain.shape();}
    public double surfaceNode(Face face,int u,int v){return surfaceHeight(node(face,u,v));}
    /** Visible height: the ground, or the body's own sea above it. A dry valley stays a valley. */
    public double surfaceHeight(double height){var sea=terrain.sea();return sea==null?height:Math.max(sea.elevation(),height);}
    public int surfaceY(Face face,int u,int v){int ground=ground(face,u,v);return terrain.ecology()==null?terrain.surfaceTop(ground):ground;}
    public net.minecraft.world.level.block.state.BlockState surfaceState(Face face,int u,int v){return terrain.surfaceState(face,u,v);}
    public long samples(){return samples;}public int columns(){return columns.size();}public int nodes(){return nodes.size();}
}
