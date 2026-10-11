package dev.starfold;

import java.util.*;

/** One frame's owner AND mesh data, shared by native, detail and cover draws. */
public final class NativeGroundOwnership {
    public static final int SIDE=130,HEIGHT=SIDE*6;
    public enum Owner { COVER, DETAIL, NATIVE }
    public static final class Snapshot {
        private final Map<Long,NativeGroundViewCache.Surface> surfaces;private final BitSet nativeTiles;
        public Snapshot(Map<Long,NativeGroundViewCache.Surface> surfaces,BitSet nativeTiles){this.surfaces=Map.copyOf(surfaces);this.nativeTiles=(BitSet)nativeTiles.clone();for(long k:surfaces.keySet())if(k<0||k>=98304)throw new IllegalArgumentException("Native tile");for(int k=this.nativeTiles.nextSetBit(0);k>=0;k=this.nativeTiles.nextSetBit(k+1))if(!surfaces.containsKey((long)k))throw new IllegalArgumentException("Owner without surface");}
        public Owner owner(long key){return !surfaces.containsKey(key)?Owner.COVER:nativeTiles.get((int)key)?Owner.NATIVE:Owner.DETAIL;}
        public NativeGroundViewCache.Surface surface(long key){return surfaces.get(key);}
        public Set<Long> keys(){return surfaces.keySet();}
        public BitSet selected(){var out=new BitSet();for(long key:keys())out.set((int)key);return out;}
        /** Extra available meshes do not invalidate a batch; missing/changed owners do. */
        public boolean validAgainst(Snapshot available){for(long key:keys())if(owner(key)!=available.owner(key)||!same(surface(key),available.surface(key)))return false;return true;}
        /** All four possible exit strips are prebaked, so trimming an owner is border-safe. */
        public Snapshot retainCompatible(Snapshot available){if(validAgainst(available))return this;var retained=new HashMap<Long,NativeGroundViewCache.Surface>();var natives=new BitSet();for(long key:keys())if(owner(key)==available.owner(key)&&same(surface(key),available.surface(key))){retained.put(key,surface(key));if(owner(key)==Owner.NATIVE)natives.set((int)key);}return new Snapshot(retained,natives);}
        /** Leaving a chart changes its draw adapter, not the already baked canonical geometry. */
        public Snapshot rebase(int currentFace,Map<Long,NativeGroundViewCache.Surface> residentDetails){
            var kept=new HashMap<Long,NativeGroundViewCache.Surface>();var natives=new BitSet();
            for(long key:keys())if(same(surface(key),residentDetails.get(key))){
                kept.put(key,surface(key));if(owner(key)==Owner.NATIVE&&key/16384==currentFace)natives.set((int)key);
            }
            return new Snapshot(kept,natives);
        }
        public boolean sameAs(Snapshot other){return keys().equals(other.keys())&&validAgainst(other);}
        public int code(long key){return switch(owner(key)){case COVER->0;case DETAIL->127;case NATIVE->255;};}
    }
    public static boolean canCommit(boolean pending,boolean pendingReady,long expectedCover,long actualCover,long expectedStaged,long actualStaged,Snapshot requested,Snapshot available){return (pending?pendingReady&&expectedStaged==actualStaged:expectedCover==actualCover)&&requested.validAgainst(available);}
    public static Snapshot empty(){return new Snapshot(Map.of(),new BitSet());}
    public static boolean same(NativeGroundViewCache.Surface a,NativeGroundViewCache.Surface b){return a!=null&&a.sameAs(b);}
    public static long key(int face,int columnX,int columnZ){return key(NativePlanetShape.STANDARD,face,columnX,columnZ);}
    public static long key(NativePlanetShape shape,int face,int columnX,int columnZ){var c=shape.topology().canonical(new CubeSurfaceTopology.Cell(CubeSurfaceTopology.Face.values()[face],columnX,0,columnZ)).cell();return c.u()/16+128L*(c.v()/16+128L*c.face().ordinal());}
    /** One tile halo gives side-wall ownership across face seams without a second draw mask. */
    public static byte[] atlas(Snapshot snapshot){return atlas(NativePlanetShape.STANDARD,snapshot);}
    /** A planet narrower than the atlas has its halo ring directly beyond its own last tile. */
    public static byte[] atlas(NativePlanetShape shape,Snapshot snapshot){int n=shape.chunks();var out=new byte[SIDE*HEIGHT];for(long key:snapshot.keys()){int f=(int)(key/16384),x=(int)(key%128),z=(int)(key/128%128);out[x+1+SIDE*(z+1+SIDE*f)]=(byte)snapshot.code(key);}for(int f=0;f<6;f++)for(int z=-1;z<=n;z++)for(int x=-1;x<=n;x++)if(x<0||x>=n||z<0||z>=n)out[x+1+SIDE*(z+1+SIDE*f)]=(byte)snapshot.code(key(shape,f,x*16+8,z*16+8));return out;}
    /** Tiles inside the vanilla view window belong to real client chunks: no cover is drawn there,
     * whether or not the chunk has arrived yet (an unloaded vanilla chunk is simply not there). */
    public static byte[] atlas(Snapshot snapshot,Set<Long> window){return atlas(NativePlanetShape.STANDARD,snapshot,window);}
    public static byte[] atlas(NativePlanetShape shape,Snapshot snapshot,Set<Long> window){
        int n=shape.chunks();var out=atlas(shape,snapshot);if(window.isEmpty())return out;
        for(long key:window){if(key<0||key>=98304)throw new IllegalArgumentException("Native tile");int f=(int)(key/16384),x=(int)(key%128),z=(int)(key/128%128);out[x+1+SIDE*(z+1+SIDE*f)]=(byte)255;}
        for(int f=0;f<6;f++)for(int z=-1;z<=n;z++)for(int x=-1;x<=n;x++)if((x<0||x>=n||z<0||z>=n)&&window.contains(key(shape,f,x*16+8,z*16+8)))out[x+1+SIDE*(z+1+SIDE*f)]=(byte)255;
        return out;
    }
    /** Keep full native volumes (including caves); a complete detail mesh may bridge an unready tile. */
    public static int code(Snapshot snapshot,long key,int nativeFace){return key/16384==nativeFace&&snapshot.owner(key)!=Owner.DETAIL?255:snapshot.code(key);}
    public static byte[] atlas(Snapshot snapshot,int nativeFace){return atlas(NativePlanetShape.STANDARD,snapshot,nativeFace);}
    public static byte[] atlas(NativePlanetShape shape,Snapshot snapshot,int nativeFace){int n=shape.chunks();var out=atlas(shape,snapshot);if(nativeFace>=0){if(nativeFace>=6)throw new IllegalArgumentException("Native face");for(int z=0;z<n;z++)for(int x=0;x<n;x++){long key=x+128L*(z+128L*nativeFace);out[x+1+SIDE*(z+1+SIDE*nativeFace)]=(byte)code(snapshot,key,nativeFace);}for(int f=0;f<6;f++)for(int z=-1;z<=n;z++)for(int x=-1;x<=n;x++)if(x<0||x>=n||z<0||z>=n)out[x+1+SIDE*(z+1+SIDE*f)]=(byte)code(snapshot,key(shape,f,x*16+8,z*16+8),nativeFace);}return out;}
    private NativeGroundOwnership(){}
}
