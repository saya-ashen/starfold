package dev.starfold;

import java.util.*;
import java.util.function.LongFunction;

/** Immutable descriptions of the geometry actually baked into one cover mesh (including halo). */
public final class NativeGroundCoverSnapshot {
    private final NativePlanetHeightCache heights;private final Map<Long,NativeGroundViewCache.Surface> surfaces;private final BitSet unknown;
    public NativeGroundCoverSnapshot(NativePlanetHeightCache heights,Map<Long,NativeGroundViewCache.Surface> surfaces,BitSet unknown){this.heights=heights;this.surfaces=Map.copyOf(surfaces);this.unknown=(BitSet)unknown.clone();}
    public static NativeGroundCoverSnapshot parent(NativePlanetHeightCache heights,BitSet changed){return new NativeGroundCoverSnapshot(heights,Map.of(),changed);}
    public static NativeGroundCoverSnapshot patch(NativePlanetHeightCache heights,BitSet changed,int face,int x0,int z0,LongFunction<NativeGroundViewCache.Surface> lookup){var surfaces=new HashMap<Long,NativeGroundViewCache.Surface>();var unknown=new BitSet();for(int x=x0-16;x<=x0+256;x+=16)for(int z=z0-16;z<=z0+256;z+=16){long key=NativeGroundOwnership.key(shape(heights),face,x,z);if(changed.get((int)key)){var s=lookup.apply(key);if(s==null)unknown.set((int)key);else surfaces.put(key,s);}}return new NativeGroundCoverSnapshot(heights,surfaces,unknown);}
    public NativePlanetHeightCache heights(){return heights;}
    public NativeGroundViewCache.Surface surface(long key){return surfaces.get(key);}
    public boolean unknown(long key){return unknown.get((int)key);}
    public boolean column(long key){return surfaces.containsKey(key);}
    /** A snapshot without generated heights describes a planet of the standard size. */
    private static NativePlanetShape shape(NativePlanetHeightCache heights){return heights==null?NativePlanetShape.STANDARD:heights.shape();}
    public NativePlanetShape shape(){return shape(heights);}
    public double height(int face,int columnX,int columnZ,double edgeX,double edgeZ){long key=NativeGroundOwnership.key(shape(),face,columnX,columnZ);var c=shape().topology().canonical(new CubeSurfaceTopology.Cell(CubeSurfaceTopology.Face.values()[face],columnX,0,columnZ)).cell();var s=surface(key);if(s!=null)return s.height(c.u()&15,c.v()&15)+1-128;if(unknown(key)&&heights==null)return 0;return NativeGroundTransition.generated(heights,CubeSurfaceTopology.Face.values()[face],edgeX,edgeZ);}
    private NativeGroundCoverSnapshot() {throw new AssertionError();}
}
