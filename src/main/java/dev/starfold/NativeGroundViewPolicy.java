package dev.starfold;

import net.minecraft.core.BlockPos;
import java.util.*;

/** Work/LOD policy only. Never changes canonical world state, picking or collision. */
public final class NativeGroundViewPolicy {
    public static final int PATCH_BLOCKS=256,PATCH_CHUNKS=16,PATCHES_PER_FACE=64,PATCH_COUNT=384,COARSE_STEP=NativeGroundSurfaceMesh.GRID;
    public static final long FRAME_BUDGET_NS=2_000_000,NEAR_BUDGET_NS=6_000_000,DETAIL_BYTES=96L*1024*1024,STAGING_BYTES=16L*1024*1024,LIGHT_REFRESH_TICKS=100;
    /** Initial six-face coverage gets the existing near-work budget; refinements keep their background share. */
    static long coverBudget(boolean parentsReady){return parentsReady?FRAME_BUDGET_NS/2:NEAR_BUDGET_NS;}
    public static int patch(long key){if(key<0||key>=98304)throw new IllegalArgumentException("Native tile");int face=(int)(key/16384),u=(int)(key%128),v=(int)(key/128%128);return face*64+u/16+8*(v/16);}
    public static int face(int patch){return patch/64;}public static int x(int patch){return patch%8*256;}public static int z(int patch){return patch/8%8*256;}
    /** Does this patch lie on a planet of this shape? A smaller planet fills only the first patches of each face. */
    public static boolean exists(NativePlanetShape shape,int patch){return x(patch)<shape.size()&&z(patch)<shape.size();}
    public static Set<Long> details(NativeGroundTopology.Pose pose,boolean controlled){return details(NativePlanetShape.STANDARD,pose,controlled);}
    public static Set<Long> details(NativePlanetShape shape,NativeGroundTopology.Pose pose,boolean controlled){
        return pose.point().y()>256?Set.of():NativeGroundStreaming.footprint(shape,pose,NativeGroundStreaming.MAX_RADIUS);
    }
    public static boolean nearSeam(NativeGroundTopology.Pose pose){return nearSeam(NativePlanetShape.STANDARD,pose);}
    public static boolean nearSeam(NativePlanetShape shape,NativeGroundTopology.Pose pose){var p=pose.point();return Math.min(Math.min(p.x(),shape.size()-p.x()),Math.min(p.z(),shape.size()-p.z()))<64;}
    public static List<Long> subscription(NativeGroundTopology.Pose pose,boolean controlled,BitSet changed,int cursor){var all=new ArrayList<>(details(pose,controlled));var out=new LinkedHashSet<Long>();for(int i=0;i<Math.min(9,all.size());i++)out.add(all.get(i));for(int i=0;i<all.size()&&out.size()<14;i++)out.add(all.get(Math.floorMod((long)cursor*5+i,all.size())));int[] edited=changed.stream().toArray();for(int i=0;i<edited.length&&i<2;i++)out.add((long)edited[Math.floorMod((long)cursor*2+i,edited.length)]);return List.copyOf(out);}
    /** Fill missing seam volumes first, but reserve refresh slots so edits/light cannot starve. */
    public static List<Long> subscription(NativeGroundTopology.Pose pose,boolean controlled,BitSet changed,int cursor,java.util.function.LongPredicate detailed){return subscription(NativePlanetShape.STANDARD,pose,controlled,changed,cursor,detailed);}
    public static List<Long> subscription(NativePlanetShape shape,NativeGroundTopology.Pose pose,boolean controlled,BitSet changed,int cursor,java.util.function.LongPredicate detailed){
        return NativeGroundStreaming.request(details(shape,pose,controlled),cursor,detailed);
    }
    public static boolean coverageChanged(BitSet before,BitSet after,int patch){var delta=(BitSet)before.clone();delta.xor(after);for(int k=delta.nextSetBit(0);k>=0;k=delta.nextSetBit(k+1))if(patch(k)==patch)return true;return false;}
    public static boolean captureNeeded(boolean resident,long currentVersion,long priorVersion,long now,long sampledAt){return !resident||currentVersion!=priorVersion||now<sampledAt||now-sampledAt>=LIGHT_REFRESH_TICKS;}
    public static final class DirtyPatches {
        private final BitSet dirty=new BitSet(PATCH_COUNT);private final LinkedHashSet<Integer> urgent=new LinkedHashSet<>();private int cursor;
        private final java.util.function.Supplier<NativePlanetShape> shape;
        public DirtyPatches(){this(()->NativePlanetShape.STANDARD);}
        /** Patches of whichever planet is being observed; only those that exist on it are ever dirty. */
        public DirtyPatches(java.util.function.Supplier<NativePlanetShape> shape){this.shape=shape;all();}
        public void all(){var now=shape.get();dirty.clear();for(int p=0;p<PATCH_COUNT;p++)if(exists(now,p))dirty.set(p);urgent.clear();}public void tile(long key){if(key>=0&&key<98304){int p=patch(key);if(!exists(shape.get(),p))return;dirty.set(p);urgent.add(p);}}
        /** Coalesces a stale resident tile without promoting it ahead of new edits each frame. */
        public void ensureTile(long key){if(key>=0&&key<98304&&exists(shape.get(),patch(key)))dirty.set(patch(key));}
        public int poll(){var now=shape.get();urgent.removeIf(p->!exists(now,p));for(int p=dirty.nextSetBit(0);p>=0;p=dirty.nextSetBit(p+1))if(!exists(now,p))dirty.clear(p);if(!urgent.isEmpty()){int p=urgent.iterator().next();urgent.remove(p);dirty.clear(p);cursor=(p+1)%PATCH_COUNT;return p;}int i=dirty.nextSetBit(cursor);if(i<0)i=dirty.nextSetBit(0);if(i>=0){dirty.clear(i);cursor=(i+1)%PATCH_COUNT;}return i;}
        public void coverage(BitSet before,BitSet after){var delta=(BitSet)before.clone();delta.xor(after);delta.stream().forEach(k->tile(k));}
        public int count(){return dirty.cardinality();}public boolean contains(int patch){return dirty.get(patch);}
    }
    private NativeGroundViewPolicy(){}
}
